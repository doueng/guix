//! The model's I/O: the sidecar child (restarted when it dies), one Rex
//! control connection per server, and one data stream per visible pane. Both
//! frontends feed it keys and events and draw `model` and `grids`.

use std::collections::HashMap;
use std::sync::mpsc::Sender;

use serde_json::{Value as Json, json};

use crate::keys::KeyPress;
use crate::link::{Incoming, Link, SidecarCommand, Verdict};
use crate::model::{Model, Out};
use crate::rex::{self, Control, RexIn};
use crate::stream::{self, Grid, StreamEvent, StreamHandle};

pub enum AppEvent {
    Sidecar(u64, Incoming),
    Rex(usize, RexIn),
    Stream(String, u64, StreamEvent),
    Key(KeyPress),
    Mouse(Mouse),
    Resize(u16, u16),
    Quit,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum MouseKind {
    Press,
    Release,
    Drag,
    /// Rows to scroll, down positive.
    Wheel(i64),
}

/// A mouse event at a screen cell (column, row), as screen::render lays it out.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Mouse {
    pub kind: MouseKind,
    pub col: usize,
    pub row: usize,
    /// left, middle or right.
    pub button: &'static str,
}

/// The text of SEL on GRID: rows from the earlier end to the later, lines
/// joined with newlines and trailing blanks dropped.
pub fn selected_text(g: &Grid, a: (usize, usize), b: (usize, usize)) -> String {
    let ((c0, r0), (c1, r1)) = if (a.1, a.0) <= (b.1, b.0) { (a, b) } else { (b, a) };
    let mut out = Vec::new();
    for r in r0..=r1.min(g.rows.saturating_sub(1) as usize) {
        let line: Vec<char> = g.line(r as u16).chars().collect();
        let from = if r == r0 { c0 } else { 0 };
        let to = if r == r1 { (c1 + 1).min(line.len()) } else { line.len() };
        out.push(line.get(from.min(to)..to).map(|s| s.iter().collect::<String>()).unwrap_or_default());
    }
    out.join("\n")
}

pub struct App {
    pub model: Model,
    pub grids: HashMap<String, Grid>,
    link: Option<Link>,
    link_gen: u64,
    sidecar: SidecarCommand,
    controls: Vec<Option<Control>>,
    streams: HashMap<String, (u64, StreamHandle)>,
    stream_gen: u64,
    sizes: HashMap<String, (u16, u16)>,
    tags: Tags,
    /// The pane area in cells, for terminal sizes.
    pub area: (u16, u16),
    tx: Sender<AppEvent>,
    pub dirty: bool,
    pub quit: bool,
    pub label: String,
}

impl App {
    pub fn new(servers: Vec<(String, Option<String>)>, label: &str, tx: Sender<AppEvent>) -> App {
        let n = servers.len();
        let mut app = App {
            model: {
                let mut m = Model::new(servers);
                m.prefer_session = std::env::var("REXMUX_SESSION").ok();
                m.startup = crate::startup();
                m.shell = crate::shell();
                m
            },
            grids: HashMap::new(),
            link: None,
            link_gen: 0,
            sidecar: SidecarCommand::from_env(),
            controls: (0..n).map(|_| None).collect(),
            streams: HashMap::new(),
            stream_gen: 0,
            sizes: HashMap::new(),
            tags: Tags::default(),
            area: (80, 24),
            tx,
            dirty: true,
            quit: false,
            label: label.to_string(),
        };
        for i in 0..n {
            app.connect(i);
        }
        app.start_sidecar();
        app
    }

    fn connect(&mut self, i: usize) {
        let tx = self.tx.clone();
        let address = self.model.servers[i].address.clone();
        // The local server starts on demand, as the rex CLI does, so it runs
        // with this window's environment (Wayland, PATH) like Herdr's server.
        if address.is_none() && std::env::var_os("REX_SERVER").is_none() && !rex::socket_path(None).exists() {
            let rex = std::env::var("REXMUX_REX").unwrap_or_else(|_| "rex".into());
            let started = std::process::Command::new(&rex).args(["server", "start"])
                .stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null())
                .status();
            if !matches!(started, Ok(s) if s.success()) {
                self.model.echo = format!("{rex} server start failed");
            }
        }
        match Control::connect(address.as_deref(), &self.label, move |ev| {
            let _ = tx.send(AppEvent::Rex(i, ev));
        }) {
            Ok(c) => {
                let id = c.client_id.clone();
                self.controls[i] = Some(c);
                let outs = self.model.connected(i, id);
                self.perform(outs);
            }
            Err(e) => self.model.echo = format!("{}: {e:#}", self.model.servers[i].label),
        }
    }

    fn start_sidecar(&mut self) {
        self.link_gen += 1;
        let generation = self.link_gen;
        let (tx, rx) = std::sync::mpsc::channel();
        match Link::spawn(&self.sidecar, tx) {
            Ok(l) => {
                self.link = Some(l);
                let app_tx = self.tx.clone();
                std::thread::spawn(move || {
                    for inc in rx {
                        if app_tx.send(AppEvent::Sidecar(generation, inc)).is_err() {
                            return;
                        }
                    }
                });
            }
            Err(e) => self.model.echo = format!("sidecar: {e}"),
        }
    }

    pub fn handle(&mut self, ev: AppEvent) {
        self.dirty = true;
        let outs = match ev {
            AppEvent::Sidecar(generation, _) if generation != self.link_gen => vec![],
            AppEvent::Sidecar(_, Incoming::Msg(v)) => {
                let verdict = self.link.as_mut().map(|l| l.rx.accept(&v)).unwrap_or(Verdict::Invalid);
                match verdict {
                    Verdict::Deliver => self.model.sidecar(&v),
                    Verdict::Gap(a, b) => {
                        let mut outs = self.model.sidecar(&v);
                        self.model.echo = format!("lost sidecar messages {a} to {b}");
                        outs
                    }
                    Verdict::Duplicate | Verdict::Invalid => vec![],
                }
            }
            AppEvent::Sidecar(_, Incoming::Down(why)) => {
                eprintln!("rexmux: sidecar down: {why}");
                self.link = None;
                let outs = self.model.sidecar_down();
                self.perform(outs);
                std::thread::sleep(std::time::Duration::from_millis(200));
                self.start_sidecar();
                vec![]
            }
            AppEvent::Rex(_, RexIn::Response { tag, result }) => {
                let t = self.tags.map.remove(&tag).unwrap_or(crate::model::Tag::Ignore);
                self.model.rex_response(t, result)
            }
            AppEvent::Rex(i, RexIn::Event(e)) => {
                if std::env::var_os("REXMUX_DEBUG").is_some() {
                    eprintln!("rexmux: event {e}");
                }
                self.model.rex_event(i, &e)
            }
            AppEvent::Rex(i, RexIn::Closed(why)) => {
                self.controls[i] = None;
                self.model.echo = format!("{} disconnected: {why}", self.model.servers[i].label);
                vec![]
            }
            AppEvent::Stream(block, generation, ev) => {
                if self.streams.get(&block).map(|(g, _)| *g) != Some(generation) {
                    vec![]
                } else {
                    match ev {
                        StreamEvent::Frame(g) => {
                            self.grids.insert(block, g);
                            vec![]
                        }
                        StreamEvent::Closed(why) => {
                            self.streams.remove(&block);
                            self.model.echo = format!("stream closed: {why}");
                            self.model.stream_closed(&block)
                        }
                    }
                }
            }
            AppEvent::Key(k) => {
                // Typing follows the output again and drops a selection, as a terminal does.
                self.model.selection = None;
                if self.model.minibuffer.is_none() && self.model.hints.is_none() {
                    if let Some((_, h)) = self.model.focused_block().and_then(|b| self.streams.get(&b)) {
                        h.bottom();
                    }
                }
                self.model.key(&k)
            }
            AppEvent::Mouse(m) => self.mouse(m),
            AppEvent::Resize(c, r) => {
                self.area = (c, r);
                vec![]
            }
            AppEvent::Quit => {
                self.quit = true;
                vec![]
            }
        };
        self.perform(outs);
        if self.model.hints_wanted {
            self.open_hints();
        }
        self.sync_sizes();
    }

    /// The visible pane under screen cell (COL, ROW): (block, pane column,
    /// pane row), the row None on its title line.
    pub fn pane_at(&self, col: usize, row: usize) -> Option<(String, usize, Option<usize>)> {
        let view = self.model.view()?;
        let (w, h) = (self.area.0 as usize, self.area.1 as usize);
        view.visible().into_iter().rev().find_map(|t| {
            let (x0, y0, tw, th) = crate::screen::tile_cells(t.rect, w, h);
            (col >= x0 && col < x0 + tw && row >= y0 && row < y0 + th)
                .then(|| (t.block.clone(), col - x0, (row > y0).then(|| row - y0 - 1)))
        })
    }

    /// Clicks focus a pane and start a selection; dragging extends it and
    /// releasing copies it. The wheel scrolls the pane's history. A program
    /// that asked for mouse reports, such as Vim or jjui, gets them instead.
    fn mouse(&mut self, m: Mouse) -> Vec<Out> {
        let Some(sid) = self.model.session_id_pub() else { return vec![] };
        let hit = self.pane_at(m.col, m.row);
        let mut outs = Vec::new();
        if let (MouseKind::Press, Some((block, _, _))) = (m.kind, &hit) {
            if self.model.focused_block().as_deref() != Some(block.as_str()) {
                outs.push(self.model.rex_out("session.focus_block", json!({"session_id": sid, "block_id": block})));
            }
        }
        let Some((block, x, Some(y))) = hit else {
            if m.kind == MouseKind::Release {
                self.finish_selection(&mut outs);
            }
            return outs;
        };
        let reports = self.grids.get(&block).is_some_and(|g| g.mouse);
        if reports {
            let event = match m.kind {
                MouseKind::Wheel(rows) => json!({"type": "wheel", "payload": {"x": x, "y": y, "delta_y": rows, "delta_mode": "line"}}),
                MouseKind::Press => json!({"type": "mouse", "payload": {"action": "press", "button": m.button, "x": x, "y": y}}),
                MouseKind::Release => json!({"type": "mouse", "payload": {"action": "release", "button": m.button, "x": x, "y": y}}),
                MouseKind::Drag => json!({"type": "mouse", "payload": {"action": "motion", "button": m.button, "buttons": Json::Object([(m.button.to_string(), json!(true))].into_iter().collect()), "x": x, "y": y}}),
            };
            outs.push(self.model.rex_out("block.event", json!({"session_id": sid, "block_id": block, "event": event})));
            return outs;
        }
        match m.kind {
            MouseKind::Wheel(rows) => {
                if let Some((_, h)) = self.streams.get(&block) {
                    h.scroll(rows);
                }
            }
            MouseKind::Press if m.button == "left" => self.model.selection = Some((block, (x, y), (x, y))),
            MouseKind::Press if m.button == "middle" => {
                outs.push(Out::Paste { server: self.model.current, session: sid, block });
            }
            MouseKind::Drag => {
                if let Some(sel) = self.model.selection.as_mut().filter(|s| s.0 == block) {
                    sel.2 = (x, y);
                }
            }
            MouseKind::Release => self.finish_selection(&mut outs),
            _ => {}
        }
        outs
    }

    fn finish_selection(&mut self, outs: &mut Vec<Out>) {
        let Some((block, a, b)) = self.model.selection.clone() else { return };
        if a == b {
            self.model.selection = None;
            return;
        }
        if let Some(g) = self.grids.get(&block) {
            let text = selected_text(g, a, b);
            self.model.echo = format!("Copied {} characters", text.chars().count());
            outs.push(Out::Clipboard(text));
        }
    }

    /// Tiny fingers on the focused pane, from the screen its stream shows.
    fn open_hints(&mut self) {
        self.model.hints_wanted = false;
        let Some(block) = self.model.focused_block() else { return };
        match self.grids.get(&block) {
            Some(g) => {
                let lines: Vec<String> = (0..g.rows).map(|y| g.line(y)).collect();
                self.model.open_hints(&block, &lines, g.cols as usize);
            }
            None => self.model.echo = "The focused pane shows nothing yet.".into(),
        }
    }

    pub fn perform(&mut self, outs: Vec<Out>) {
        for o in outs {
            match o {
                Out::Sidecar(v) => {
                    if let Some(l) = self.link.as_mut() {
                        let _ = l.send(v);
                    }
                }
                Out::Rex { server, method, payload, tag } => match self.controls.get(server).and_then(Option::as_ref) {
                    Some(c) => {
                        let t = encode_tag(&mut self.tags, tag);
                        c.request(&method, payload, t);
                    }
                    None => self.model.echo = format!("{method}: not connected"),
                },
                Out::OpenStream { server, block, ticket } => {
                    self.stream_gen += 1;
                    let generation = self.stream_gen;
                    let tx = self.tx.clone();
                    let b = block.clone();
                    let sock = rex::socket_path(self.model.servers[server].address.as_deref());
                    let h = stream::spawn(sock, ticket, move |ev| {
                        let _ = tx.send(AppEvent::Stream(b.clone(), generation, ev));
                    });
                    // Replacing a handle drops it, which stops the old stream first.
                    self.streams.insert(block, (generation, h));
                }
                Out::CloseStream { block } => {
                    self.streams.remove(&block);
                    self.grids.remove(&block);
                    self.sizes.remove(&block);
                }
                // Tests set REXMUX_CLIPBOARD_FILE and REXMUX_OPEN_FILE, so a copy or a
                // URL lands in a file rather than on the desktop.
                Out::Clipboard(text) => match std::env::var_os("REXMUX_CLIPBOARD_FILE") {
                    Some(f) => {
                        let _ = std::fs::write(f, text);
                    }
                    None => {
                        let _ = std::process::Command::new("wl-copy").arg("--").arg(text).spawn();
                    }
                },
                Out::Paste { server, session, block } => {
                    let text = match std::env::var_os("REXMUX_CLIPBOARD_FILE") {
                        Some(f) => std::fs::read_to_string(f).ok(),
                        None => std::process::Command::new("wl-paste").arg("--no-newline").output().ok()
                            .filter(|o| o.status.success()).map(|o| String::from_utf8_lossy(&o.stdout).into_owned()),
                    };
                    match text {
                        Some(t) if !t.is_empty() => self.perform(vec![Out::Rex {
                            server,
                            method: "block.event".into(),
                            payload: json!({"session_id": session, "block_id": block, "event": {"type": "paste", "payload": {"text": t}}}),
                            tag: crate::model::Tag::Ignore,
                        }]),
                        _ => self.model.echo = "The clipboard is empty.".into(),
                    }
                }
                Out::Open(url) if std::env::var_os("REXMUX_OPEN_FILE").is_some() => {
                    let _ = std::fs::write(std::env::var_os("REXMUX_OPEN_FILE").unwrap(), url);
                }
                Out::Open(url) => {
                    // As tiny-fingers did: xdg-open (Chrome, by mimeapps), then focus Chrome.
                    let _ = std::process::Command::new("xdg-open").arg(&url)
                        .stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).spawn();
                    let _ = std::process::Command::new("hyprctl").args(["dispatch", "hl.dsp.focus({ window = \"class:google-chrome\" })"])
                        .stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).spawn();
                }
                Out::Quit => self.quit = true,
            }
        }
    }

    /// Tell Rex each visible terminal's size in cells, when it changes.
    fn sync_sizes(&mut self) {
        let Some(view) = self.model.view().cloned() else { return };
        let (w, h) = self.area;
        let server = self.model.current;
        let Some(c) = self.controls.get(server).and_then(Option::as_ref) else { return };
        for t in view.visible() {
            // A pane's first row is its title, and a divider may take its first column.
            let (_, _, tw, th) = crate::screen::tile_cells(t.rect, w as usize, h as usize);
            let cols = (tw as u16).max(2);
            let rows = (th as u16).saturating_sub(1).max(1);
            if self.sizes.get(&t.block) != Some(&(cols, rows)) {
                self.sizes.insert(t.block.clone(), (cols, rows));
                c.request("session.own_block", json!({"session_id": view.session, "block_ids": [t.block], "mode": "take"}), 0);
                c.request("com.superlogical.terminal.resize",
                          json!({"session_id": view.session, "block_id": t.block, "args": {"columns": cols, "rows": rows}}), 0);
            }
        }
    }

    pub fn connected_servers(&self) -> usize {
        self.controls.iter().filter(|c| c.is_some()).count()
    }
}

/// Rex request tags are numbers; the app keeps the model's tag for each.
#[derive(Default)]
pub struct Tags {
    next: u64,
    map: HashMap<u64, crate::model::Tag>,
}

fn encode_tag(tags: &mut Tags, tag: crate::model::Tag) -> u64 {
    tags.next += 1;
    tags.map.insert(tags.next, tag);
    tags.next
}

