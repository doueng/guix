use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use zbus::fdo::RequestNameFlags;
use zbus::zvariant::{OwnedValue, Value as ZValue};

use crate::core::CoreEvent;
use crate::edn::Value;

const PATH: &str = "/org/freedesktop/Notifications";
const IFACE: &str = "org.freedesktop.Notifications";
/// Used when a client asks for the server default (`expire_timeout = -1`).
const DEFAULT_TIMEOUT_MS: i32 = 6000;

#[derive(Clone, Copy)]
#[repr(u32)]
enum CloseReason {
    Expired = 1,
    Dismissed = 2,
    Requested = 3,
}

pub enum Command {
    Dismiss(u32),
    Invoke(u32, String),
    DismissAll,
}

#[derive(Clone)]
pub struct Handle(smol::channel::Sender<Command>);

impl Handle {
    pub fn send(&self, cmd: Command) {
        let _ = self.0.send_blocking(cmd);
    }
}

#[derive(Clone)]
struct Notification {
    id: u32,
    generation: u64,
    app_name: String,
    app_icon: String,
    summary: String,
    body: String,
    actions: Vec<(String, String)>,
    urgency: u8,
    transient: bool,
    desktop_entry: Option<String>,
    timeout_ms: i32,
    received_ms: i64,
}

impl Notification {
    fn to_value(&self) -> Value {
        let urgency = match self.urgency {
            0 => "low",
            2 => "critical",
            _ => "normal",
        };
        Value::map([
            ("id", Value::Int(self.id as i64)),
            ("app-name", Value::str(&self.app_name)),
            ("app-icon", Value::str(&self.app_icon)),
            ("summary", Value::str(&self.summary)),
            ("body", Value::str(&self.body)),
            (
                "actions",
                Value::Vector(
                    self.actions
                        .iter()
                        .map(|(k, l)| Value::Vector(vec![Value::str(k), Value::str(l)]))
                        .collect(),
                ),
            ),
            ("urgency", Value::kw(urgency)),
            ("transient?", Value::Bool(self.transient)),
            ("desktop-entry", self.desktop_entry.clone().into()),
            ("timeout", Value::Int(self.timeout_ms as i64)),
            ("received", Value::Int(self.received_ms)),
        ])
    }
}

#[derive(Default)]
struct State {
    next_id: u32,
    next_generation: u64,
    items: Vec<Notification>,
}

struct Shared {
    state: Mutex<State>,
    conn: OnceLock<zbus::Connection>,
    events: smol::channel::Sender<CoreEvent>,
}

impl Shared {
    fn publish(&self) {
        let list = {
            let state = self.state.lock().unwrap();
            Value::Vector(
                state
                    .items
                    .iter()
                    .rev()
                    .map(Notification::to_value)
                    .collect(),
            )
        };
        let _ = self
            .events
            .send_blocking(CoreEvent::Signal("notifications".into(), list));
    }

    async fn close(self: &Arc<Self>, id: u32, reason: CloseReason, only_generation: Option<u64>) {
        let removed = {
            let mut state = self.state.lock().unwrap();
            let before = state.items.len();
            state
                .items
                .retain(|n| !(n.id == id && only_generation.is_none_or(|g| g == n.generation)));
            state.items.len() != before
        };
        if removed {
            self.publish();
            self.emit("NotificationClosed", &(id, reason as u32)).await;
        }
    }

    async fn emit<B>(&self, member: &str, body: &B)
    where
        B: serde::ser::Serialize + zbus::zvariant::DynamicType,
    {
        if let Some(conn) = self.conn.get()
            && let Err(e) = conn
                .emit_signal(None::<&str>, PATH, IFACE, member, body)
                .await
        {
            eprintln!("dusk: failed to emit {member}: {e}");
        }
    }

    fn schedule_expiry(self: &Arc<Self>, id: u32, generation: u64, timeout_ms: i32) {
        if timeout_ms <= 0 {
            return;
        }
        let shared = self.clone();
        smol::spawn(async move {
            smol::Timer::after(Duration::from_millis(timeout_ms as u64)).await;
            shared
                .close(id, CloseReason::Expired, Some(generation))
                .await;
        })
        .detach();
    }
}

struct Server {
    shared: Arc<Shared>,
}

fn hint_u8(v: &OwnedValue) -> Option<u8> {
    match &**v {
        ZValue::U8(b) => Some(*b),
        ZValue::U32(u) => Some(*u as u8),
        ZValue::I32(i) => Some(*i as u8),
        _ => None,
    }
}

fn hint_bool(v: &OwnedValue) -> Option<bool> {
    match &**v {
        ZValue::Bool(b) => Some(*b),
        ZValue::U8(b) => Some(*b != 0),
        ZValue::I32(i) => Some(*i != 0),
        _ => None,
    }
}

fn hint_str(v: &OwnedValue) -> Option<String> {
    match &**v {
        ZValue::Str(s) => Some(s.to_string()),
        _ => None,
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[zbus::interface(name = "org.freedesktop.Notifications")]
impl Server {
    async fn get_capabilities(&self) -> Vec<String> {
        vec!["body".into(), "actions".into()]
    }

    #[allow(clippy::too_many_arguments)]
    async fn notify(
        &self,
        app_name: String,
        replaces_id: u32,
        app_icon: String,
        summary: String,
        body: String,
        actions: Vec<String>,
        hints: HashMap<String, OwnedValue>,
        expire_timeout: i32,
    ) -> u32 {
        let timeout_ms = if expire_timeout < 0 {
            DEFAULT_TIMEOUT_MS
        } else {
            expire_timeout
        };
        let actions = actions
            .chunks(2)
            .map(|pair| (pair[0].clone(), pair.get(1).cloned().unwrap_or_default()))
            .collect();
        let (id, generation) = {
            let mut state = self.shared.state.lock().unwrap();
            state.next_generation += 1;
            let generation = state.next_generation;
            let mut notification = Notification {
                id: 0,
                generation,
                app_name,
                app_icon,
                summary,
                body,
                actions,
                urgency: hints.get("urgency").and_then(hint_u8).unwrap_or(1),
                transient: hints.get("transient").and_then(hint_bool).unwrap_or(false),
                desktop_entry: hints.get("desktop-entry").and_then(hint_str),
                timeout_ms,
                received_ms: now_ms(),
            };
            let existing = (replaces_id != 0)
                .then(|| state.items.iter().position(|n| n.id == replaces_id))
                .flatten();
            match existing {
                Some(index) => {
                    notification.id = replaces_id;
                    state.items[index] = notification;
                }
                None => {
                    state.next_id = state.next_id.wrapping_add(1).max(1);
                    notification.id = state.next_id;
                    state.items.push(notification);
                }
            }
            (
                state
                    .items
                    .iter()
                    .find(|n| n.generation == generation)
                    .unwrap()
                    .id,
                generation,
            )
        };
        self.shared.publish();
        self.shared.schedule_expiry(id, generation, timeout_ms);
        id
    }

    async fn close_notification(&self, id: u32) {
        self.shared.close(id, CloseReason::Requested, None).await;
    }

    async fn get_server_information(&self) -> (String, String, String, String) {
        (
            "dusk".into(),
            "dusk".into(),
            env!("CARGO_PKG_VERSION").into(),
            "1.2".into(),
        )
    }
}

async fn run(shared: Arc<Shared>, commands: smol::channel::Receiver<Command>) -> zbus::Result<()> {
    let conn = zbus::connection::Builder::session()?
        .serve_at(
            PATH,
            Server {
                shared: shared.clone(),
            },
        )?
        .build()
        .await?;
    // Queue behind an existing server instead of failing, so dusk takes over
    // as soon as the previous daemon exits.
    let reply = conn
        .request_name_with_flags(
            "org.freedesktop.Notifications",
            RequestNameFlags::ReplaceExisting | RequestNameFlags::AllowReplacement,
        )
        .await?;
    eprintln!("dusk: notifications name request: {reply:?}");
    let _ = shared.conn.set(conn);
    shared.publish();

    while let Ok(cmd) = commands.recv().await {
        match cmd {
            Command::Dismiss(id) => shared.close(id, CloseReason::Dismissed, None).await,
            Command::Invoke(id, action) => {
                shared.emit("ActionInvoked", &(id, action)).await;
                shared.close(id, CloseReason::Dismissed, None).await;
            }
            Command::DismissAll => {
                let ids: Vec<u32> = shared
                    .state
                    .lock()
                    .unwrap()
                    .items
                    .iter()
                    .map(|n| n.id)
                    .collect();
                for id in ids {
                    shared.close(id, CloseReason::Dismissed, None).await;
                }
            }
        }
    }
    Ok(())
}

pub fn start(events: smol::channel::Sender<CoreEvent>) -> Handle {
    let (tx, rx) = smol::channel::unbounded();
    let shared = Arc::new(Shared {
        state: Mutex::new(State::default()),
        conn: OnceLock::new(),
        events,
    });
    std::thread::Builder::new()
        .name("dusk-notifications".into())
        .spawn(move || {
            if let Err(e) = smol::block_on(run(shared, rx)) {
                eprintln!("dusk: notification server failed: {e}");
            }
        })
        .expect("spawn notification thread");
    Handle(tx)
}
