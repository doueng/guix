//! The model's I/O: the sidecar child (restarted when it dies), one Rex
//! control connection per server, and one data stream per visible pane. Both
//! frontends feed it keys and events and draw `model` and `grids`.

use std::collections::HashMap;
use std::sync::mpsc::Sender;

use serde_json::json;

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
    Resize(u16, u16),
    Quit,
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
            model: { let mut m = Model::new(servers); m.prefer_session = std::env::var("REXMUX_SESSION").ok(); m },
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
            AppEvent::Rex(i, RexIn::Event(e)) => self.model.rex_event(i, &e),
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
            AppEvent::Key(k) => self.model.key(&k),
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
        self.sync_sizes();
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
                Out::Clipboard(text) => {
                    let _ = std::process::Command::new("wl-copy").arg(text).spawn();
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
            let cols = ((t.rect[2] * w as f32).round() as u16).max(2);
            // A pane's first row is its title.
            let rows = ((t.rect[3] * h as f32).round() as u16).saturating_sub(1).max(1);
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

