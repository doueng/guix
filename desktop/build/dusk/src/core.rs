use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use gpui::{
    App, AppContext, Bounds, WindowBackgroundAppearance, WindowBounds, WindowHandle, WindowKind,
    WindowOptions, point, px,
};

use crate::edn::Value;
use crate::ipc;
use crate::runtime::{Runtime, RuntimeLink, RuntimeOptions};
use crate::services::notifications::{self, Command as NotifyCommand};
use crate::surface::{SurfaceSpec, SurfaceView, WindowKey};

pub enum CoreEvent {
    FromRuntime(Value),
    RuntimeExited,
    RestartRuntime,
    Signal(String, Value),
    Ipc {
        request: Value,
        reply: mpsc::Sender<Value>,
    },
    ConfigChanged(Vec<PathBuf>),
    CloseErrorSurface(u64),
}

struct Surface {
    window: WindowHandle<SurfaceView>,
    key: WindowKey,
}

pub struct Core {
    events: smol::channel::Sender<CoreEvent>,
    link: RuntimeLink,
    runtime: Option<Runtime>,
    runtime_opts: RuntimeOptions,
    runtime_started: Instant,
    restart_delay: Duration,
    signals: BTreeMap<String, Value>,
    surfaces: HashMap<String, Surface>,
    pending: HashMap<i64, mpsc::Sender<Value>>,
    next_request: i64,
    notifications: Option<notifications::Handle>,
    error_serial: u64,
}

const ERROR_SURFACE: &str = "dusk/error";

fn kw_map<const N: usize>(pairs: [(&str, Value); N]) -> Value {
    Value::map(pairs)
}

impl Core {
    pub fn new(
        runtime_opts: RuntimeOptions,
        events: smol::channel::Sender<CoreEvent>,
        notifications: Option<notifications::Handle>,
    ) -> Core {
        Core {
            events,
            link: RuntimeLink::default(),
            runtime: None,
            runtime_opts,
            runtime_started: Instant::now(),
            restart_delay: Duration::from_millis(500),
            signals: BTreeMap::new(),
            surfaces: HashMap::new(),
            pending: HashMap::new(),
            next_request: 0,
            notifications,
            error_serial: 0,
        }
    }

    pub fn start_runtime(&mut self) {
        match Runtime::spawn(&self.runtime_opts, &self.link, self.events.clone()) {
            Ok(rt) => {
                self.runtime = Some(rt);
                self.runtime_started = Instant::now();
                let signals = Value::Map(
                    self.signals
                        .iter()
                        .map(|(k, v)| (Value::kw(k), v.clone()))
                        .collect(),
                );
                self.link.send(&kw_map([
                    ("op", Value::kw("init")),
                    (
                        "config",
                        Value::str(self.runtime_opts.config.display().to_string()),
                    ),
                    ("signals", signals),
                ]));
            }
            Err(e) => {
                eprintln!("dusk: {e}");
                self.schedule_restart();
            }
        }
    }

    fn schedule_restart(&mut self) {
        if self.runtime_started.elapsed() > Duration::from_secs(30) {
            self.restart_delay = Duration::from_millis(500);
        } else {
            self.restart_delay = (self.restart_delay * 2).min(Duration::from_secs(30));
        }
        let delay = self.restart_delay;
        let events = self.events.clone();
        std::thread::spawn(move || {
            std::thread::sleep(delay);
            let _ = events.send_blocking(CoreEvent::RestartRuntime);
        });
    }

    pub fn handle(&mut self, event: CoreEvent, cx: &mut App) {
        match event {
            CoreEvent::FromRuntime(msg) => self.on_runtime_message(msg, cx),
            CoreEvent::RuntimeExited => {
                let status = self.runtime.as_mut().and_then(Runtime::reap);
                self.runtime = None;
                eprintln!("dusk: runtime exited ({status:?}); restarting");
                for (_, reply) in self.pending.drain() {
                    let _ = reply.send(ipc::error("runtime exited"));
                }
                self.schedule_restart();
            }
            CoreEvent::RestartRuntime => {
                if self.runtime.is_none() {
                    self.start_runtime();
                }
            }
            CoreEvent::Signal(name, value) => {
                self.link.send(&kw_map([
                    ("op", Value::kw("signal")),
                    ("id", Value::kw(&name)),
                    ("value", value.clone()),
                ]));
                self.signals.insert(name, value);
            }
            CoreEvent::ConfigChanged(files) => {
                let files = files
                    .iter()
                    .map(|f| Value::str(f.display().to_string()))
                    .collect();
                self.link.send(&kw_map([
                    ("op", Value::kw("reload")),
                    ("files", Value::Vector(files)),
                ]));
            }
            CoreEvent::Ipc { request, reply } => self.on_ipc(request, reply, cx),
            CoreEvent::CloseErrorSurface(serial) => {
                if serial == self.error_serial {
                    self.close_surface(ERROR_SURFACE, cx);
                }
            }
        }
    }

    fn on_ipc(&mut self, request: Value, reply: mpsc::Sender<Value>, cx: &mut App) {
        let op = request.get("op").and_then(Value::as_name).unwrap_or("");
        match op {
            "status" => {
                let _ = reply.send(ipc::ok(Value::str(if self.link.connected() {
                    "running"
                } else {
                    "running (runtime restarting)"
                })));
            }
            "signals" => {
                let map = self
                    .signals
                    .iter()
                    .map(|(k, v)| (Value::kw(k), v.clone()))
                    .collect();
                let _ = reply.send(ipc::ok(Value::Map(map)));
            }
            "surfaces" => {
                let mut ids: Vec<Value> = self.surfaces.keys().map(Value::str).collect();
                ids.sort_by_key(|v| v.to_string());
                let _ = reply.send(ipc::ok(Value::Vector(ids)));
            }
            "reload" => {
                let config = Value::str(self.runtime_opts.config.display().to_string());
                self.link.send(&kw_map([
                    ("op", Value::kw("reload")),
                    ("files", Value::Vector(vec![config])),
                ]));
                let _ = reply.send(ipc::ok(Value::Nil));
            }
            "quit" => {
                let _ = reply.send(ipc::ok(Value::Nil));
                cx.quit();
            }
            "eval" | "request" => {
                if !self.link.connected() {
                    let _ = reply.send(ipc::error("runtime is not running"));
                    return;
                }
                self.next_request += 1;
                let id = self.next_request;
                self.pending.insert(id, reply);
                let mut msg = vec![
                    (Value::kw("op"), Value::kw(op)),
                    (Value::kw("id"), Value::Int(id)),
                ];
                for key in ["code", "name", "args"] {
                    if let Some(v) = request.get(key) {
                        msg.push((Value::kw(key), v.clone()));
                    }
                }
                self.link.send(&Value::Map(msg));
            }
            other => {
                let _ = reply.send(ipc::error(format!("unknown op {other:?}")));
            }
        }
    }

    fn on_runtime_message(&mut self, msg: Value, cx: &mut App) {
        let op = msg.get("op").and_then(Value::as_name).unwrap_or("");
        match op {
            "render" => {
                for (id, tree) in msg.get("surfaces").map(Value::entries).unwrap_or(&[]) {
                    let Some(id) = id.as_name() else { continue };
                    if tree.is_nil() {
                        self.close_surface(id, cx);
                    } else {
                        self.apply_surface(id, tree.clone(), cx);
                    }
                }
            }
            "reply" => {
                let Some(id) = msg.get("id").and_then(Value::as_i64) else {
                    return;
                };
                if let Some(reply) = self.pending.remove(&id) {
                    let value = match msg.get("error").and_then(Value::as_str) {
                        Some(err) => ipc::error(err),
                        None => ipc::ok(msg.get("value").cloned().unwrap_or(Value::Nil)),
                    };
                    let _ = reply.send(value);
                }
            }
            "call" => self.on_call(&msg, cx),
            "error" => {
                let text = msg
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or("unknown error");
                eprintln!("dusk: runtime error: {text}");
                self.show_error(text, cx);
            }
            "reloaded" => {
                eprintln!("dusk: config reloaded");
                self.error_serial += 1;
                self.close_surface(ERROR_SURFACE, cx);
            }
            "ready" => eprintln!("dusk: runtime ready"),
            other => eprintln!("dusk: unknown runtime op {other:?}"),
        }
    }

    fn on_call(&mut self, msg: &Value, cx: &mut App) {
        let name = msg.get("fn").and_then(Value::as_name).unwrap_or("");
        let args = msg.get("args").map(Value::items).unwrap_or(&[]);
        let arg_u32 = |i: usize| args.get(i).and_then(Value::as_i64).map(|n| n as u32);
        match name {
            "notifications/dismiss" => {
                if let (Some(n), Some(id)) = (&self.notifications, arg_u32(0)) {
                    n.send(NotifyCommand::Dismiss(id));
                }
            }
            "notifications/invoke" => {
                if let (Some(n), Some(id)) = (&self.notifications, arg_u32(0)) {
                    let action = args.get(1).and_then(Value::as_name).unwrap_or("default");
                    n.send(NotifyCommand::Invoke(id, action.to_string()));
                }
            }
            "notifications/dismiss-all" => {
                if let Some(n) = &self.notifications {
                    n.send(NotifyCommand::DismissAll);
                }
            }
            "exec" => spawn_detached(args),
            "quit" => cx.quit(),
            other => eprintln!("dusk: unknown call {other:?}"),
        }
    }

    fn apply_surface(&mut self, id: &str, tree: Value, cx: &mut App) {
        let spec = SurfaceSpec::from_tree(id, &tree);
        if let Some(existing) = self.surfaces.get(id) {
            if existing.key == spec.key {
                let updated = existing.window.update(cx, |view, window, cx| {
                    let old = view.spec.clone();
                    view.set_tree(tree.clone(), window, cx);
                    if old.width != spec.width || old.height != spec.height {
                        let current = window.viewport_size();
                        let want = spec.window_size(Some(current));
                        if want != current {
                            window.resize(want);
                        }
                    }
                    cx.notify();
                });
                if updated.is_ok() {
                    return;
                }
            }
            self.close_surface(id, cx);
        }
        let initial = spec.window_size(None);
        let options = WindowOptions {
            titlebar: None,
            window_bounds: Some(WindowBounds::Windowed(Bounds {
                origin: point(px(0.), px(0.)),
                size: initial,
            })),
            app_id: Some("dusk".to_string()),
            window_background: WindowBackgroundAppearance::Transparent,
            kind: WindowKind::LayerShell(spec.layer_options()),
            focus: spec.key.keyboard != gpui::layer_shell::KeyboardInteractivity::None,
            is_movable: false,
            ..Default::default()
        };
        let link = self.link.clone();
        let surface_id = id.to_string();
        match cx.open_window(options, move |window, cx| {
            cx.new(|cx| SurfaceView::new(surface_id, tree, link, window, cx))
        }) {
            Ok(window) => {
                self.surfaces.insert(
                    id.to_string(),
                    Surface {
                        window,
                        key: spec.key,
                    },
                );
            }
            Err(e) => eprintln!("dusk: failed to open surface {id}: {e:#}"),
        }
    }

    /// GPUI's Wayland `resize` runs as a spawned task that panics if the
    /// window was destroyed before it ran. Stop the view from issuing new
    /// resizes, then remove the window once pending ones have drained.
    fn close_surface(&mut self, id: &str, cx: &mut App) {
        let Some(surface) = self.surfaces.remove(id) else {
            return;
        };
        let window = surface.window;
        let _ = window.update(cx, |view, _, _| view.closing = true);
        cx.spawn(async move |cx| {
            cx.background_executor()
                .timer(Duration::from_millis(100))
                .await;
            let _ = window.update(cx, |_, window, _| window.remove_window());
        })
        .detach();
    }

    fn show_error(&mut self, message: &str, cx: &mut App) {
        let text = |s: &str, extra: Value| {
            let mut props = vec![(Value::kw("font-size"), Value::Int(13))];
            props.extend(extra.entries().iter().cloned());
            Value::map([
                ("type", Value::kw("text")),
                ("path", Value::str("dusk/error/t")),
                ("props", Value::Map(props)),
                ("children", Value::Vector(vec![Value::str(s)])),
            ])
        };
        let card = Value::map([
            ("type", Value::kw("column")),
            ("path", Value::str("dusk/error/card")),
            (
                "props",
                Value::map([
                    ("bg", Value::str("#f38ba8f2")),
                    ("color", Value::str("#11111b")),
                    ("padding", Value::Int(14)),
                    ("gap", Value::Int(6)),
                    ("radius", Value::Int(12)),
                    ("shadow", Value::kw("lg")),
                ]),
            ),
            (
                "children",
                Value::Vector(vec![
                    text(
                        "dusk: config error (previous UI kept)",
                        Value::map([("font-weight", Value::kw("bold"))]),
                    ),
                    text(
                        message,
                        Value::map([
                            ("font-family", Value::str("monospace")),
                            ("line-clamp", Value::Int(12)),
                        ]),
                    ),
                ]),
            ),
        ]);
        let surface = Value::map([
            ("type", Value::kw("layer")),
            ("path", Value::str(ERROR_SURFACE)),
            (
                "props",
                Value::map([
                    ("layer", Value::kw("overlay")),
                    ("anchor", Value::Set(vec![Value::kw("top")])),
                    (
                        "margin",
                        Value::Vector(vec![
                            Value::Int(16),
                            Value::Int(0),
                            Value::Int(0),
                            Value::Int(0),
                        ]),
                    ),
                    ("width", Value::Int(960)),
                    ("height", Value::kw("auto")),
                    ("padding", Value::Int(20)),
                    ("scale", Value::Float(1.4)),
                ]),
            ),
            ("children", Value::Vector(vec![card])),
        ]);
        self.apply_surface(ERROR_SURFACE, surface, cx);
        self.error_serial += 1;
        let serial = self.error_serial;
        let events = self.events.clone();
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_secs(15));
            let _ = events.send_blocking(CoreEvent::CloseErrorSurface(serial));
        });
    }
}

fn spawn_detached(args: &[Value]) {
    let argv: Vec<String> = args
        .iter()
        .flat_map(|a| match a {
            Value::Vector(items) | Value::List(items) => items.to_vec(),
            other => vec![other.clone()],
        })
        .filter_map(|a| a.as_name().map(str::to_string))
        .collect();
    let mut cmd = match argv.as_slice() {
        [] => return,
        [single] => {
            let mut c = Command::new("sh");
            c.arg("-c").arg(single);
            c
        }
        [program, rest @ ..] => {
            let mut c = Command::new(program);
            c.args(rest);
            c
        }
    };
    cmd.stdin(Stdio::null());
    for var in crate::fonts::injected_env() {
        cmd.env_remove(var);
    }
    match cmd.spawn() {
        Ok(mut child) => {
            std::thread::spawn(move || {
                let _ = child.wait();
            });
        }
        Err(e) => eprintln!("dusk: exec {argv:?} failed: {e}"),
    }
}
