//! The window's state and its rules, with no I/O: keys, sidecar messages
//! and Rex results go in, and `Out`s come back for the app to perform. The
//! GPUI window and the tty frontend both draw this model, so the Bombadil
//! specs that drive the tty frontend check the same rules the window runs.

use std::collections::{BTreeMap, HashMap};

use serde_json::{Value as Json, json};

use crate::conv;
use crate::edn::Value;
use crate::keys::KeyPress;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct ArgSpec {
    pub name: String,
    pub kind: String,
    pub choices: Vec<String>,
    pub required: bool,
    pub complete: Option<String>,
    pub doc: String,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Cmd {
    pub id: String,
    pub title: String,
    pub category: String,
    pub doc: String,
    pub action: String,
    pub enabled: bool,
    pub keys: Vec<Vec<String>>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct AgentRow {
    pub block: String,
    pub session: String,
    pub server: Option<String>,
    pub state: String,
    pub app: String,
    pub msg: String,
}

#[derive(Clone, Debug, PartialEq)]
pub enum MiniMode {
    Mx,
    Prompt { command: String, specs: Vec<ArgSpec>, index: usize, partial: Vec<(String, Json)> },
    Pick { title: String, items: Vec<(String, Json)>, then: String, arg: String },
}

#[derive(Clone, Debug, PartialEq)]
pub struct Mini {
    pub mode: MiniMode,
    pub input: String,
    pub selected: usize,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Tile {
    pub block: String,
    pub rect: [f32; 4],
    pub floating: bool,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Win {
    pub id: String,
    pub label: String,
    pub focused: Option<String>,
    pub zoomed: Option<String>,
    pub tiles: Vec<Tile>,
    /// The tiled layer's layout tree, as session.view gives it, for resizing.
    pub layout: Json,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct View {
    pub session: String,
    pub label: String,
    pub revision: i64,
    pub active: Option<String>,
    pub windows: Vec<Win>,
}

impl View {
    pub fn from_json(v: &Json) -> View {
        let windows = v["windows"].as_array().cloned().unwrap_or_default().iter().map(|w| {
            let mut tiles = Vec::new();
            let layout = w["layers"].as_array().and_then(|ls| ls.iter().find(|l| l["kind"] == "tiled"))
                .map(|l| l["layout"].clone()).unwrap_or(Json::Null);
            for layer in w["layers"].as_array().cloned().unwrap_or_default() {
                if layer["state"] == "minimized" {
                    continue;
                }
                leaves(&layer["layout"], layer["kind"] != "tiled", &mut tiles);
            }
            Win {
                id: s(&w["window_id"]),
                label: s(&w["label"]),
                focused: w["focused_block_id"].as_str().map(str::to_string),
                zoomed: w["zoomed_block_id"].as_str().map(str::to_string),
                tiles,
                layout,
            }
        }).collect();
        View {
            session: s(&v["session_id"]),
            label: s(&v["label"]),
            revision: v["revision"].as_i64().unwrap_or(0),
            active: v["active_window_id"].as_str().map(str::to_string),
            windows,
        }
    }

    pub fn active_window(&self) -> Option<&Win> {
        self.windows.iter().find(|w| Some(&w.id) == self.active.as_ref()).or(self.windows.first())
    }

    /// The tiles to draw: the zoomed block alone, else every tile.
    pub fn visible(&self) -> Vec<Tile> {
        match self.active_window() {
            Some(w) => match &w.zoomed {
                Some(z) => vec![Tile { block: z.clone(), rect: [0.0, 0.0, 1.0, 1.0], floating: false }],
                None => w.tiles.clone(),
            },
            None => vec![],
        }
    }
}

/// The session.resize_pane payload (less session_id) that moves the border
/// nearest BLOCK of the split DIRECTION crosses by DELTA of that split:
/// left and right move a vertical border, up and down a horizontal one.
pub fn resize_request(layout: &Json, block: &str, direction: &str, delta: f64) -> Option<Json> {
    let (orient, sign) = match direction {
        "left" => ("horizontal", -1.0),
        "right" => ("horizontal", 1.0),
        "up" => ("vertical", -1.0),
        "down" => ("vertical", 1.0),
        _ => return None,
    };
    fn contains(node: &Json, block: &str) -> bool {
        node["block_id"].as_str() == Some(block)
            || (!node["split"].is_null() && (contains(&node["split"]["before"], block) || contains(&node["split"]["after"], block)))
    }
    fn first_leaf(node: &Json) -> Option<String> {
        node["block_id"].as_str().map(str::to_string).or_else(|| first_leaf(&node["split"]["before"]))
    }
    // The deepest split of ORIENT on the way to BLOCK.
    fn find<'a>(node: &'a Json, block: &str, orient: &str, best: Option<&'a Json>) -> Option<&'a Json> {
        let sp = &node["split"];
        if sp.is_null() {
            return best;
        }
        let best = if sp["direction"] == orient { Some(sp) } else { best };
        for side in ["before", "after"] {
            if contains(&sp[side], block) {
                return find(&sp[side], block, orient, best);
            }
        }
        best
    }
    let sp = find(layout, block, orient, None)?;
    let ratio = (sp["ratio"].as_f64().unwrap_or(0.5) + sign * delta).clamp(0.1, 0.9);
    Some(json!({"direction": orient, "before_block_id": first_leaf(&sp["before"])?,
                "after_block_id": first_leaf(&sp["after"])?, "ratio": ratio}))
}

fn s(v: &Json) -> String {
    v.as_str().unwrap_or("").to_string()
}

fn leaves(node: &Json, floating: bool, out: &mut Vec<Tile>) {
    if node.is_null() {
        return;
    }
    if let Some(b) = node["block_id"].as_str() {
        let r = &node["rect"];
        let f = |i: usize| r[i].as_f64().unwrap_or(0.0) as f32;
        out.push(Tile { block: b.to_string(), rect: [f(0), f(1), f(2), f(3)], floating });
    } else if !node["split"].is_null() {
        leaves(&node["split"]["before"], floating, out);
        leaves(&node["split"]["after"], floating, out);
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Server {
    pub label: String,
    pub address: Option<String>,
    pub client_id: String,
    pub view: Option<View>,
    pub sessions: Vec<(String, String)>,
}

/// What a Rex response is for.
#[derive(Clone, Debug, PartialEq)]
pub enum Tag {
    View { server: usize },
    Effect,
    Created { server: usize },
    Ticket { server: usize, block: String },
    Sessions { server: usize },
    Perform { server: usize, request: String },
    /// A startup tab's process, to see whether a restart left it a bare shell.
    Revive { server: usize, block: String, window: String },
    /// The sessions, to show LABEL's or make it with the startup tabs in CWD.
    Project { server: usize, label: String, cwd: Option<String> },
    Ignore,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Out {
    Sidecar(Value),
    Rex { server: usize, method: String, payload: Json, tag: Tag },
    OpenStream { server: usize, block: String, ticket: String },
    CloseStream { block: String },
    Clipboard(String),
    /// Open a web URL in the browser (tiny fingers).
    Open(String),
    /// Paste the clipboard into BLOCK (the app reads it).
    Paste { server: usize, session: String, block: String },
    Quit,
}

/// Tiny fingers over one pane (hints.rs).
#[derive(Clone, Debug, PartialEq)]
pub struct HintMode {
    pub block: String,
    pub session: String,
    pub targets: Vec<crate::hints::Target>,
    pub input: String,
}

#[derive(Clone, Debug, PartialEq)]
pub enum StreamState {
    Asked,
    Open,
}

#[derive(Debug, Default)]
pub struct Model {
    pub servers: Vec<Server>,
    pub current: usize,
    pub catalogue: Vec<Cmd>,
    pub bindings: Vec<(Vec<String>, String)>,
    pub keymap_layer: Json,
    pub keymap_actions: Json,
    pub prefix: Vec<String>,
    pub minibuffer: Option<Mini>,
    pub echo: String,
    pub doc: Option<(String, String)>,
    pub agents: Vec<AgentRow>,
    pub attention: Vec<String>,
    pub streams: BTreeMap<String, StreamState>,
    pub sidecar_up: bool,
    /// The session to show first, by label or id (REXMUX_SESSION); else the newest.
    pub prefer_session: Option<String>,
    /// The session to create on a server with none (config :startup), as
    /// {label, windows: [{label, command: [argv]}]}; it is also the one shown first.
    pub startup: Option<Json>,
    /// The shell new blocks run (config :shell), such as fish.
    pub shell: Option<String>,
    /// Tiny fingers, while open.
    pub hints: Option<HintMode>,
    /// A command asked for hints; the app opens them with the pane's text.
    pub hints_wanted: bool,
    /// The session shown before this one, for Alt+Space TAB `.
    pub previous_session: Option<String>,
    /// Text selected with the mouse: (block, anchor, end) in pane cells.
    pub selection: Option<(String, (usize, usize), (usize, usize))>,
    /// The startup session was found at launch: once its view arrives, check
    /// each tab for a program the Rex server lost in a restart.
    pub revive_pending: bool,
    /// A session was just made: show its first tab once its view arrives.
    pub focus_first_pending: bool,
    next_id: i64,
    /// Requests sent to the sidecar and not answered, with where they came from.
    pub pending: HashMap<i64, Option<(usize, String)>>,
    /// The command each request runs, so a prompt can ask again for it.
    invoked: HashMap<i64, String>,
    last_ctx: Option<Json>,
}

pub const MX_KEY: &str = "alt+x";

impl Model {
    pub fn new(servers: Vec<(String, Option<String>)>) -> Model {
        Model {
            servers: servers.into_iter().map(|(label, address)| Server { label, address, ..Default::default() }).collect(),
            ..Default::default()
        }
    }

    pub fn server(&self) -> &Server {
        &self.servers[self.current]
    }

    pub fn view(&self) -> Option<&View> {
        self.server().view.as_ref()
    }

    pub fn focused_block(&self) -> Option<String> {
        self.view().and_then(|v| v.active_window()).and_then(|w| w.focused.clone())
    }

    /// The context the sidecar evaluates commands in.
    pub fn context(&self) -> Json {
        let mut c = serde_json::Map::new();
        if let Some(v) = self.view() {
            c.insert("session-id".into(), json!(v.session));
            c.insert("window-ids".into(), json!(v.windows.iter().map(|w| w.id.clone()).collect::<Vec<_>>()));
            c.insert("window-labels".into(), json!(v.windows.iter().map(|w| w.label.clone()).collect::<Vec<_>>()));
            if let Some(w) = v.active_window() {
                c.insert("window-id".into(), json!(w.id));
                if let Some(b) = &w.focused {
                    c.insert("block-id".into(), json!(b));
                }
            }
        }
        if !self.server().client_id.is_empty() {
            c.insert("client-id".into(), json!(self.server().client_id));
        }
        c.insert("server".into(), json!(self.server().label));
        Json::Object(c)
    }

    fn ctx_edn(&self) -> Value {
        conv::to_edn(&self.context())
    }

    // ---- sidecar link ----

    pub fn hello(&mut self) -> Vec<Out> {
        self.sidecar_up = true;
        self.last_ctx = Some(self.context());
        vec![Out::Sidecar(Value::map([
            ("op", Value::kw("hello")),
            ("client-id", Value::str(self.server().client_id.clone())),
            ("ctx", self.ctx_edn()),
        ]))]
    }

    /// The sidecar died: every request waiting on it fails, once.
    pub fn sidecar_down(&mut self) -> Vec<Out> {
        self.sidecar_up = false;
        let mut outs = Vec::new();
        for (_, origin) in self.pending.drain() {
            if let Some((server, request)) = origin {
                outs.push(perform_reply(server, &request, Err("sidecar restarted".into())));
            }
        }
        self.echo = "sidecar restarted; commands that were running failed".into();
        outs
    }

    fn context_changed(&mut self) -> Vec<Out> {
        let ctx = self.context();
        if self.last_ctx.as_ref() == Some(&ctx) || !self.sidecar_up {
            return vec![];
        }
        self.last_ctx = Some(ctx);
        vec![Out::Sidecar(Value::map([("op", Value::kw("context")), ("ctx", self.ctx_edn())]))]
    }

    /// Ask the sidecar to run command ID with ARGS.
    pub fn invoke(&mut self, id: &str, args: Vec<(String, Json)>, origin: Option<(usize, String)>) -> Vec<Out> {
        if !self.sidecar_up {
            self.echo = "sidecar is not running".into();
            return origin.map(|(s, r)| vec![perform_reply(s, &r, Err("sidecar is not running".into()))]).unwrap_or_default();
        }
        self.next_id += 1;
        let n = self.next_id;
        self.pending.insert(n, origin);
        self.invoked.insert(n, id.to_string());
        let args = Value::Map(args.iter().map(|(k, v)| (Value::kw(k), conv::to_edn(v))).collect());
        vec![Out::Sidecar(Value::map([
            ("op", Value::kw("commands/invoke")),
            ("id", Value::Int(n)),
            ("command", Value::str(id)),
            ("args", args),
            ("ctx", self.ctx_edn()),
        ]))]
    }

    /// One message from the sidecar, already accepted by the link.
    pub fn sidecar(&mut self, msg: &Value) -> Vec<Out> {
        match msg.get("op").and_then(Value::as_name) {
            Some("ready") => self.hello(),
            Some("commands/changed") => {
                self.catalogue = msg.get("commands").map(Value::items).unwrap_or(&[]).iter().map(cmd_of).collect();
                let layer = msg.get("keymap").cloned().unwrap_or(Value::Nil);
                self.bindings = layer.get("bindings").map(Value::items).unwrap_or(&[]).iter().filter_map(|b| {
                    let keys: Vec<String> = b.get("keys")?.items().iter().filter_map(|k| k.as_str().map(str::to_string)).collect();
                    Some((keys, b.get("action")?.as_str()?.to_string()))
                }).collect();
                self.keymap_layer = conv::to_json(&layer);
                self.keymap_actions = conv::to_json(msg.get("actions").unwrap_or(&Value::Nil));
                self.send_keymap()
            }
            Some("agents") => {
                self.agents = msg.get("rows").map(Value::items).unwrap_or(&[]).iter().map(|r| AgentRow {
                    block: conv::str_of(r.get("block")).unwrap_or_default(),
                    session: conv::str_of(r.get("session")).unwrap_or_default(),
                    server: conv::str_of(r.get("server")),
                    state: conv::str_of(r.get("state")).unwrap_or_default(),
                    app: conv::str_of(r.get("app")).unwrap_or_default(),
                    msg: conv::str_of(r.get("msg")).unwrap_or_default(),
                }).collect();
                self.attention = msg.get("attention").map(Value::items).unwrap_or(&[]).iter().filter_map(|b| b.as_str().map(str::to_string)).collect();
                vec![]
            }
            Some("reply") => self.reply(msg),
            Some("effects") => self.apply_all(msg.get("effects").map(Value::items).unwrap_or(&[])),
            Some("log") => {
                self.echo = conv::str_of(msg.get("text")).unwrap_or_default();
                vec![]
            }
            _ => vec![],
        }
    }

    fn reply(&mut self, msg: &Value) -> Vec<Out> {
        let id = msg.get("id").and_then(Value::as_i64).unwrap_or(-1);
        // A reply nobody waits for (late, or after a restart) is dropped.
        let Some(origin) = self.pending.remove(&id) else { return vec![] };
        let mut outs = Vec::new();
        if let Some(err) = conv::str_of(msg.get("error")) {
            let detail: Vec<String> = msg.get("invalid").map(Value::items).unwrap_or(&[]).iter()
                .map(|i| format!("{}: {}", i.get("path").map(|p| p.to_string()).unwrap_or_default(), conv::str_of(i.get("error")).unwrap_or_default()))
                .collect();
            self.echo = if detail.is_empty() { err.clone() } else { format!("{err} ({})", detail.join("; ")) };
            if let Some((s, r)) = origin {
                outs.push(perform_reply(s, &r, Err(err)));
            }
            return outs;
        }
        if let Some(prompt) = msg.get("prompt") {
            let specs: Vec<ArgSpec> = prompt.items().iter().map(spec_of).collect();
            let partial = match msg.get("partial").map(conv::to_json) {
                Some(Json::Object(m)) => m.into_iter().collect(),
                _ => vec![],
            };
            let command = self.invoked.remove(&id).unwrap_or_default();
            if let Some((s, r)) = origin {
                outs.push(perform_reply(s, &r, Err("the command needs arguments".into())));
                return outs;
            }
            self.minibuffer = Some(Mini { mode: MiniMode::Prompt { command, specs, index: 0, partial }, input: String::new(), selected: 0 });
            return outs;
        }
        self.invoked.remove(&id);
        outs.extend(self.apply_all(msg.get("effects").map(Value::items).unwrap_or(&[])));
        if let Some((s, r)) = origin {
            outs.push(perform_reply(s, &r, Ok(json!({}))));
        }
        outs
    }
}

/// session.create's payload for a :startup spec: one window per entry, each
/// a shell block running its command (or a login shell when it has none).
/// SHELL (config :shell) is the shell blocks start, since Rex otherwise uses
/// the server's $SHELL, which is bash when a script autostarted it.
pub fn startup_request(spec: &Json, shell: Option<&str>) -> Json {
    let windows: Vec<Json> = spec["windows"].as_array().cloned().unwrap_or_default().iter().map(|w| {
        let mut block = json!({"flavor": "com.superlogical.terminal.shell"});
        if let Some(l) = w["label"].as_str() {
            block["label"] = json!(l);
        }
        let mut options = serde_json::Map::new();
        if w["command"].is_array() {
            options.insert("command".into(), w["command"].clone());
        }
        if let Some(sh) = shell {
            options.insert("shell".into(), json!(sh));
        }
        if let Some(cwd) = spec["cwd"].as_str() {
            options.insert("cwd".into(), json!(cwd));
        }
        if !options.is_empty() {
            block["options"] = Json::Object(options);
        }
        json!({"window_label": w["label"].as_str().unwrap_or(""), "layout": {"block": block}})
    }).collect();
    json!({"label": spec["label"].as_str().unwrap_or("main"), "initial_windows": windows})
}

/// S quoted for a POSIX shell or fish: bare when it is plain, else in single quotes.
pub fn shell_quote(s: &str) -> String {
    if !s.is_empty() && s.chars().all(|c| c.is_ascii_alphanumeric() || "-_./=:@+,".contains(c)) {
        s.to_string()
    } else {
        // '"'"' closes the quote, adds a quoted ', and reopens: bash and fish both read it.
        format!("'{}'", s.replace('\'', "'\"'\"'"))
    }
}

/// Give every new shell block in a request's layout SHELL, unless it names
/// one, so tabs, splits and layers run the configured shell rather than the
/// Rex server's $SHELL.
pub fn with_shell(v: &mut Json, shell: &str) {
    with_options(v, &[("shell", json!(shell))]);
}

/// Give every new shell block in V's layout each of OPTS it does not set.
pub fn with_options(v: &mut Json, opts: &[(&str, Json)]) {
    match v {
        Json::Object(m) => {
            if m.get("flavor").and_then(Json::as_str) == Some("com.superlogical.terminal.shell") {
                let o = m.entry("options").or_insert_with(|| json!({}));
                if o.is_null() {
                    *o = json!({});
                }
                if let Json::Object(o) = o {
                    for (k, val) in opts {
                        o.entry(k.to_string()).or_insert_with(|| val.clone());
                    }
                }
            }
            for (_, child) in m.iter_mut() {
                with_options(child, opts);
            }
        }
        Json::Array(a) => a.iter_mut().for_each(|c| with_options(c, opts)),
        _ => {}
    }
}

/// The agent picker's icon for an OSC 7501 state.
pub fn icon(state: &str) -> &'static str {
    match state {
        "blocked" => "!",
        "error" => "✗",
        "done" => "✓",
        "working" => "●",
        "idle" => "○",
        _ => "?",
    }
}

fn perform_reply(server: usize, request: &str, result: Result<Json, String>) -> Out {
    let payload = match result {
        Ok(v) => json!({"call_id": request, "result": v}),
        Err(e) => json!({"call_id": request, "error": {"code": "refused", "message": e}}),
    };
    Out::Rex { server, method: "client.reply".into(), payload, tag: Tag::Ignore }
}

fn cmd_of(v: &Value) -> Cmd {
    Cmd {
        id: conv::str_of(v.get("id")).unwrap_or_default(),
        title: conv::str_of(v.get("title")).unwrap_or_default(),
        category: conv::str_of(v.get("category")).unwrap_or_default(),
        doc: conv::str_of(v.get("doc")).unwrap_or_default(),
        action: conv::str_of(v.get("action")).unwrap_or_default(),
        enabled: v.get("enabled").map(Value::truthy).unwrap_or(true),
        keys: v.get("keys").map(Value::items).unwrap_or(&[]).iter()
            .map(|ks| ks.items().iter().filter_map(|k| k.as_str().map(str::to_string)).collect())
            .collect(),
    }
}

fn spec_of(v: &Value) -> ArgSpec {
    let kind = match v.get("type") {
        Some(Value::Vector(ts)) => ts.iter().filter_map(|t| t.as_str()).find(|t| *t != "null").unwrap_or("string").to_string(),
        other => conv::str_of(other).unwrap_or_else(|| "string".into()),
    };
    ArgSpec {
        name: conv::str_of(v.get("name")).unwrap_or_default(),
        kind,
        choices: v.get("enum").map(Value::items).unwrap_or(&[]).iter().filter_map(|e| e.as_str().map(str::to_string)).collect(),
        required: v.get("required").map(Value::truthy).unwrap_or(false),
        complete: conv::str_of(v.get("complete")),
        doc: conv::str_of(v.get("doc")).unwrap_or_default(),
    }
}

/// Read minibuffer TEXT as an argument of SPEC's type.
pub fn coerce(spec: &ArgSpec, text: &str) -> Json {
    let t = text.trim();
    match spec.kind.as_str() {
        "integer" => t.parse::<i64>().map(Json::from).unwrap_or_else(|_| json!(t)),
        "number" => t.parse::<f64>().map(Json::from).unwrap_or_else(|_| json!(t)),
        "boolean" => match t {
            "true" | "yes" | "y" => json!(true),
            "false" | "no" | "n" => json!(false),
            _ => json!(t),
        },
        "object" | "array" => serde_json::from_str(t).unwrap_or_else(|_| json!(t)),
        _ => json!(text),
    }
}

/// Fuzzy score of QUERY in TEXT: every query character in order, with a
/// bonus for runs and word starts. None when it does not match.
pub fn fuzzy(query: &str, text: &str) -> Option<i64> {
    if query.is_empty() {
        return Some(0);
    }
    let t: Vec<char> = text.to_lowercase().chars().collect();
    let mut score = 0i64;
    let mut ti = 0;
    let mut prev: Option<usize> = None;
    for qc in query.to_lowercase().chars() {
        if qc == ' ' {
            continue;
        }
        let i = (ti..t.len()).find(|&i| t[i] == qc)?;
        score += 1;
        if prev == Some(i.wrapping_sub(1)) {
            score += 5;
        }
        if i == 0 || !t[i - 1].is_alphanumeric() {
            score += 3;
        }
        prev = Some(i);
        ti = i + 1;
    }
    Some(score * 100 - t.len() as i64)
}

impl Model {
    fn send_keymap(&self) -> Vec<Out> {
        if self.keymap_layer.is_null() {
            return vec![];
        }
        (0..self.servers.len()).filter(|&i| !self.servers[i].client_id.is_empty()).map(|server| Out::Rex {
            server,
            method: "client.keymap".into(),
            payload: json!({"defaults": [self.keymap_layer], "actions": self.keymap_actions, "server_actions": "none"}),
            tag: Tag::Ignore,
        }).collect()
    }

    fn session_id(&self) -> Option<String> {
        self.view().map(|v| v.session.clone())
    }

    /// What every new shell block gets unless it says otherwise: the
    /// configured shell.
    pub fn block_options(&self) -> Vec<(&'static str, Json)> {
        let mut o = Vec::new();
        if let Some(sh) = &self.shell {
            o.push(("shell", json!(sh)));
        }
        o
    }

    pub fn session_id_pub(&self) -> Option<String> {
        self.session_id()
    }

    /// A Rex request on the current server whose answer nobody waits for.
    pub fn rex_out(&self, method: &str, payload: Json) -> Out {
        self.rex(method, payload, Tag::Effect)
    }

    fn rex(&self, method: &str, mut payload: Json, tag: Tag) -> Out {
        with_options(&mut payload, &self.block_options());
        Out::Rex { server: self.current, method: method.into(), payload, tag }
    }

    fn apply_all(&mut self, effects: &[Value]) -> Vec<Out> {
        effects.iter().flat_map(|e| self.apply(e)).collect()
    }

    /// Do one effect: [kind arg ...].
    pub fn apply(&mut self, effect: &Value) -> Vec<Out> {
        let items = effect.items();
        let kind = items.first().and_then(Value::as_name).unwrap_or("");
        let arg = items.get(1).cloned().unwrap_or(Value::Nil);
        let j = conv::to_json(&arg);
        match kind {
            "rex/call" => {
                let method = arg.as_str().unwrap_or("").to_string();
                let payload = items.get(2).map(conv::to_json).unwrap_or(json!({}));
                vec![self.rex(&method, payload, Tag::Effect)]
            }
            "rex/key" => {
                let Some(sid) = self.session_id() else { return vec![] };
                j.as_array().cloned().unwrap_or_default().iter().filter_map(|k| k.as_str()).map(|k| {
                    self.rex("client.key", json!({"session_id": sid, "key": KeyPress::parse(k).to_rex()}), Tag::Ignore)
                }).collect()
            }
            "pane/write" => {
                let sid = j["session"].as_str().map(str::to_string).or_else(|| self.session_id()).unwrap_or_default();
                vec![self.rex("com.superlogical.terminal.write",
                              json!({"session_id": sid, "block_id": j["block"], "args": {"data": j["text"]}}), Tag::Effect)]
            }
            "focus/block" => self.focus_block(&j),
            "focus/window" => {
                let Some(sid) = self.session_id() else { return vec![] };
                vec![self.rex("session.focus_window", json!({"session_id": sid, "window_id": j["window"]}), Tag::Effect)]
            }
            "focus/session" => self.attach(self.current, j["session"].as_str().unwrap_or("")),
            "focus/server" => {
                let label = j["server"].as_str().unwrap_or("");
                match self.servers.iter().position(|s| s.label == label) {
                    Some(i) => {
                        self.current = i;
                        self.echo = format!("server {label}");
                        let mut outs = self.refresh_streams();
                        outs.extend(self.context_changed());
                        // A server shown for the first time attaches its newest session.
                        if self.servers[i].view.is_none() {
                            outs.push(Out::Rex { server: i, method: "session.list".into(), payload: json!({}), tag: Tag::Sessions { server: i } });
                        }
                        outs
                    }
                    None => {
                        self.echo = format!("no server {label}");
                        vec![]
                    }
                }
            }
            "session/create" => vec![self.rex("session.create", json!({"label": j["label"]}), Tag::Created { server: self.current })],
            "layer/float" => {
                let Some(sid) = self.session_id() else { return vec![] };
                let size = j["size"].as_array().cloned().unwrap_or_else(|| vec![json!(0.8), json!(0.7)]);
                let (w, h) = (size[0].as_f64().unwrap_or(0.8), size[1].as_f64().unwrap_or(0.7));
                // The layer runs in the Rex server's environment; give it this
                // window's Wayland display, so a copy inside it reaches the clipboard.
                let mut argv = j["argv"].clone();
                if let (Ok(wd), Some(a)) = (std::env::var("WAYLAND_DISPLAY"), argv.as_array()) {
                    let mut with_env = vec![json!("env"), json!(format!("WAYLAND_DISPLAY={wd}"))];
                    with_env.extend(a.iter().cloned());
                    argv = json!(with_env);
                }
                let mut options = json!({"command": argv});
                if let Some(cwd) = j["cwd"].as_str() {
                    options["cwd"] = json!(cwd);
                }
                if j["close-on-exit"] == json!(true) {
                    options["exit"] = json!({"on_completion": true});
                }
                vec![self.rex("session.new_layer", json!({
                    "session_id": sid, "bounds": [(1.0 - w) / 2.0, (1.0 - h) / 2.0, w, h], "focus": true,
                    "layout": {"block": {"flavor": "com.superlogical.terminal.shell", "options": options}}}), Tag::Effect)]
            }
            "ui/message" => {
                self.echo = arg.as_str().map(str::to_string).unwrap_or_else(|| arg.to_string());
                vec![]
            }
            "ui/doc" => {
                self.doc = Some((j["title"].as_str().unwrap_or("").into(), j["text"].as_str().unwrap_or("").into()));
                vec![]
            }
            "ui/pick" => {
                let items = j["items"].as_array().cloned().unwrap_or_default().iter()
                    .map(|i| (i["label"].as_str().unwrap_or("").to_string(), i["value"].clone())).collect();
                self.minibuffer = Some(Mini {
                    mode: MiniMode::Pick {
                        title: j["title"].as_str().unwrap_or("Pick").into(),
                        items,
                        then: j["then"].as_str().unwrap_or("").into(),
                        arg: j["arg"].as_str().unwrap_or("value").into(),
                    },
                    input: String::new(),
                    selected: 0,
                });
                vec![]
            }
            "clipboard/copy" => vec![Out::Clipboard(arg.as_str().unwrap_or("").to_string())],
            "hints/open" => {
                self.hints_wanted = true;
                vec![]
            }
            "clipboard/paste" => match (self.session_id(), self.focused_block()) {
                (Some(session), Some(block)) => vec![Out::Paste { server: self.current, session, block }],
                _ => vec![],
            },
            "session/last" => match self.previous_session.clone() {
                // A session that is gone fails to attach, and Rex says so.
                Some(p) => self.attach(self.current, &p),
                _ => {
                    self.echo = "No previous session.".into();
                    vec![]
                }
            },
            "session/startup" => {
                // Ask for the sessions first: rexmux keeps no live list of them.
                let server = self.current;
                vec![Out::Rex { server, method: "session.list".into(), payload: json!({}),
                                tag: Tag::Project { server, label: j["label"].as_str().unwrap_or("main").into(),
                                                    cwd: j["cwd"].as_str().map(str::to_string) } }]
            }
            "pane/resize" => {
                let (Some(sid), Some(block)) = (self.session_id(), self.focused_block()) else { return vec![] };
                let layout = self.view().and_then(|v| v.active_window()).map(|w| w.layout.clone()).unwrap_or(Json::Null);
                match resize_request(&layout, &block, j["direction"].as_str().unwrap_or(""), j["delta"].as_f64().unwrap_or(0.05)) {
                    Some(mut p) => {
                        p["session_id"] = json!(sid);
                        vec![self.rex("session.resize_pane", p, Tag::Effect)]
                    }
                    None => {
                        self.echo = "No border to move that way.".into();
                        vec![]
                    }
                }
            }
            "keymap/layer" => self.send_keymap(),
            other => {
                self.echo = format!("unknown effect {other}");
                vec![]
            }
        }
    }

    fn focus_block(&mut self, j: &Json) -> Vec<Out> {
        let mut outs = Vec::new();
        if let Some(label) = j["server"].as_str() {
            if let Some(i) = self.servers.iter().position(|s| s.label == label) {
                self.current = i;
            }
        }
        let block = j["block"].as_str().unwrap_or("").to_string();
        let session = j["session"].as_str().map(str::to_string).or_else(|| self.session_id()).unwrap_or_default();
        if self.session_id().as_deref() != Some(session.as_str()) {
            outs.extend(self.attach(self.current, &session));
        }
        outs.push(self.rex("session.focus_block", json!({"session_id": session, "block_id": block}), Tag::Effect));
        outs
    }

    fn startup_window(&self, label: &str) -> Option<Json> {
        self.startup.as_ref()?["windows"].as_array()?.iter().find(|w| w["label"] == label).cloned()
    }

    /// Ask every pane what it runs (session persistence). A startup tab's
    /// first pane is asked under its tab's label, so it can get its program
    /// back; any other pane under none, so it only gets the configured shell.
    fn revive_checks(&self, server: usize) -> Vec<Out> {
        let Some(v) = self.servers[server].view.as_ref() else { return vec![] };
        v.windows.iter().flat_map(|w| {
            let program = self.startup_window(&w.label).is_some();
            w.tiles.iter().filter(|t| !t.floating).enumerate().map(move |(i, t)| Out::Rex {
                server, method: "block.method".into(),
                payload: json!({"session_id": v.session, "block_id": t.block, "method": "process"}),
                tag: Tag::Revive { server, block: t.block.clone(), window: if program && i == 0 { w.label.clone() } else { String::new() } },
            })
        }).collect()
    }

    /// Rex keeps sessions across a reboot but starts each block's shell anew,
    /// in the server's $SHELL, without its program. A startup tab whose
    /// foreground is a bare shell gets its program again (its :restore, such
    /// as pi --continue, else its :command) and the configured shell, as
    /// Herdr's resume_agents_on_restore did. A program's tab otherwise closes
    /// when the program exits, so a bare shell there means a restart. Any
    /// other pane left a bare shell other than the configured one (a renamed
    /// tab, a split) gets the configured shell, as Herdr's default_shell. A
    /// shell the user started inside another is not bare: the block's own
    /// process (child) is then not the foreground one.
    pub fn revive(&mut self, server: usize, block: &str, window: &str, process: &Json) -> Vec<Out> {
        let spec = self.startup_window(window);
        let fg = process["foreground"]["name"].as_str().unwrap_or("");
        let shells = ["bash", "sh", "zsh", "fish", "dash"];
        let bare = process["child"]["pid"].is_null() || process["child"]["pid"] == process["foreground"]["pid"];
        if !shells.contains(&fg) || !bare {
            return vec![];
        }
        let shell = self.shell.clone().unwrap_or_else(|| "fish".into());
        let argv = spec.as_ref().and_then(|s| s["restore"].as_array().or(s["command"].as_array()).cloned());
        let line = match argv {
            Some(a) => {
                let words: Vec<String> = a.iter().filter_map(|x| x.as_str()).map(shell_quote).collect();
                format!("exec {shell} -l -c {}\r", shell_quote(&words.join(" ")))
            }
            // A plain shell tab only needs the right shell.
            None if fg != shell.rsplit('/').next().unwrap_or(&shell) => format!("exec {shell} -l\r"),
            None => return vec![],
        };
        let Some(sid) = self.servers[server].view.as_ref().map(|v| v.session.clone()) else { return vec![] };
        self.echo = if window.is_empty() { "restored a pane's shell".into() } else { format!("restored tab {window}") };
        vec![Out::Rex { server, method: "com.superlogical.terminal.write".into(),
                        payload: json!({"session_id": sid, "block_id": block, "args": {"data": line}}), tag: Tag::Effect }]
    }

    /// Show SESSION of SERVER: attach, and read its view.
    pub fn attach(&mut self, server: usize, session: &str) -> Vec<Out> {
        if session.is_empty() {
            return vec![];
        }
        if let Some(old) = self.servers[server].view.as_ref().map(|v| v.session.clone()) {
            if old != session {
                self.previous_session = Some(old);
            }
        }
        self.current = server;
        vec![Out::Rex { server, method: "session.attach".into(), payload: json!({"session_id": session}), tag: Tag::View { server } }]
    }

    // ---- Rex ----

    pub fn connected(&mut self, server: usize, client_id: String) -> Vec<Out> {
        self.servers[server].client_id = client_id;
        let mut outs = vec![Out::Rex { server, method: "session.list".into(), payload: json!({}), tag: Tag::Sessions { server } }];
        outs.extend(self.send_keymap().into_iter().filter(|o| matches!(o, Out::Rex { server: s, .. } if *s == server)));
        outs
    }

    pub fn rex_response(&mut self, tag: Tag, result: Result<Json, String>) -> Vec<Out> {
        let v = match result {
            Ok(v) => v,
            Err(e) => {
                if let Tag::Ticket { block, .. } = &tag {
                    self.streams.remove(block);
                }
                self.echo = e;
                return vec![];
            }
        };
        match tag {
            Tag::View { server } => {
                let view = if v.get("view").is_some() { &v["view"] } else { &v };
                self.set_view(server, View::from_json(view))
            }
            Tag::Sessions { server } => {
                let list: Vec<(String, String)> = v["sessions"].as_array().cloned().unwrap_or_default().iter()
                    .map(|s| (s["session_id"].as_str().unwrap_or("").to_string(), s["label"].as_str().unwrap_or("").to_string()))
                    .collect();
                self.servers[server].sessions = list.clone();
                if self.servers[server].view.is_none() && server == self.current {
                    let want = self.prefer_session.clone()
                        .or_else(|| self.startup.as_ref().and_then(|s| s["label"].as_str().map(str::to_string)));
                    let found = want.as_ref()
                        .and_then(|w| list.iter().find(|(id, l)| id == w || l == w || id.ends_with(w.as_str())));
                    // The startup session is made when it is missing, even beside
                    // other sessions, which may be dead leftovers.
                    let startup_missing = self.prefer_session.is_none() && found.is_none();
                    if startup_missing || list.is_empty() {
                        if let Some(create) = self.startup.as_ref().map(|s| startup_request(s, self.shell.as_deref())) {
                            self.echo = "starting the startup session".into();
                            return vec![Out::Rex { server, method: "session.create".into(), payload: create, tag: Tag::Created { server } }];
                        }
                    }
                    self.revive_pending = found.is_some() && self.prefer_session.is_none();
                    let pick = found.or(list.last());
                    if let Some((sid, _)) = pick {
                        return self.attach(server, &sid.clone());
                    }
                }
                vec![]
            }
            Tag::Created { server } => {
                let sid = v["session_id"].as_str().unwrap_or("").to_string();
                // Rex shows a new session's last tab; start on the first, the shell.
                self.focus_first_pending = true;
                self.attach(server, &sid)
            }
            Tag::Ticket { server, block } => match v["ticket"].as_str() {
                Some(t) if self.streams.contains_key(&block) => {
                    self.streams.insert(block.clone(), StreamState::Open);
                    vec![Out::OpenStream { server, block, ticket: t.to_string() }]
                }
                _ => vec![],
            },
            Tag::Revive { server, block, window } => self.revive(server, &block, &window, &v),
            Tag::Project { server, label, cwd } => {
                let found = v["sessions"].as_array().and_then(|ss| ss.iter().find(|s| s["label"] == label.as_str()))
                    .and_then(|s| s["session_id"].as_str()).map(str::to_string);
                if let Some(sid) = found {
                    return self.attach(server, &sid);
                }
                let mut spec = self.startup.clone().unwrap_or_else(|| json!({"windows": [{"label": "shell"}]}));
                spec["label"] = json!(label);
                if let Some(cwd) = cwd {
                    spec["cwd"] = json!(cwd);
                }
                let mut payload = startup_request(&spec, self.shell.as_deref());
                with_options(&mut payload, &self.block_options());
                vec![Out::Rex { server, method: "session.create".into(), payload, tag: Tag::Created { server } }]
            }
            Tag::Effect | Tag::Perform { .. } | Tag::Ignore => vec![],
        }
    }

    pub fn set_view(&mut self, server: usize, view: View) -> Vec<Out> {
        if let Some(old) = &self.servers[server].view {
            if old.session == view.session && old.revision > view.revision {
                return vec![];
            }
        }
        self.servers[server].view = Some(view);
        let mut outs = if server == self.current { self.refresh_streams() } else { vec![] };
        if std::mem::take(&mut self.revive_pending) {
            outs.extend(self.revive_checks(server));
        }
        if std::mem::take(&mut self.focus_first_pending) {
            if let Some(v) = self.servers[server].view.as_ref() {
                if let Some(first) = v.windows.first().filter(|w| Some(&w.id) != v.active.as_ref()) {
                    outs.push(Out::Rex { server, method: "session.focus_window".into(),
                                         payload: json!({"session_id": v.session, "window_id": first.id}), tag: Tag::Effect });
                }
            }
        }
        outs.extend(self.context_changed());
        outs
    }

    /// Open a stream for each visible pane that has none, and close the rest,
    /// so a pane never has two streams (verify/tla, OneStreamPerPane).
    pub fn refresh_streams(&mut self) -> Vec<Out> {
        let Some(view) = self.view().cloned() else { return vec![] };
        let visible: Vec<String> = view.visible().into_iter().map(|t| t.block).collect();
        let mut outs = Vec::new();
        let stale: Vec<String> = self.streams.keys().filter(|b| !visible.contains(b)).cloned().collect();
        for b in stale {
            self.streams.remove(&b);
            outs.push(Out::CloseStream { block: b });
        }
        for b in visible {
            if !self.streams.contains_key(&b) {
                self.streams.insert(b.clone(), StreamState::Asked);
                outs.push(self.rex("data.connect", json!({"session_id": view.session, "block_id": b,
                                                           "data_ref_id": "terminal", "subprotocol": "rex.data.terminal.v1"}),
                                   Tag::Ticket { server: self.current, block: b.clone() }));
            }
        }
        outs
    }

    /// A stream ended; the next refresh opens a new one if the pane is still shown.
    pub fn stream_closed(&mut self, block: &str) -> Vec<Out> {
        self.streams.remove(block);
        self.refresh_streams()
    }

    pub fn rex_event(&mut self, server: usize, ev: &Json) -> Vec<Out> {
        let (kind, payload) = (ev["type"].as_str().unwrap_or(""), &ev["payload"]);
        match kind {
            "session_event" => {
                let inner = &payload["event"];
                let sid = payload["session_id"].as_str().unwrap_or("");
                let shown = self.servers[server].view.as_ref().is_some_and(|v| v.session == sid);
                match inner["type"].as_str().unwrap_or("") {
                    "session_view_changed" | "active_window_changed" | "window_created" | "window_closed" | "block_closed" if shown => {
                        vec![Out::Rex { server, method: "session.view".into(), payload: json!({"session_id": sid}), tag: Tag::View { server } }]
                    }
                    _ => vec![],
                }
            }
            "session_created" | "session_destroyed" => {
                vec![Out::Rex { server, method: "session.list".into(), payload: json!({}), tag: Tag::Sessions { server } }]
            }
            "client_perform_requested" => {
                let action = payload["action"].as_str().unwrap_or("");
                let request = payload["call_id"].as_str().unwrap_or("").to_string();
                match self.catalogue.iter().find(|c| c.action == action).map(|c| c.id.clone()) {
                    Some(id) => {
                        let args = match &payload["args"] {
                            Json::Object(m) => m.clone().into_iter().collect(),
                            _ => vec![],
                        };
                        self.invoke(&id, args, Some((server, request)))
                    }
                    None => vec![perform_reply(server, &request, Err(format!("no action {action}")))],
                }
            }
            _ => vec![],
        }
    }

    // ---- keys ----

    /// The commands bound under the pending prefix, for the which-key line.
    pub fn continuations(&self) -> Vec<(String, String)> {
        let n = self.prefix.len();
        let mut out: Vec<(String, String)> = self.bindings.iter()
            .filter(|(ks, _)| ks.len() > n && ks[..n] == self.prefix[..])
            .map(|(ks, a)| (ks[n..].join(" "), self.catalogue.iter().find(|c| &c.action == a).map(|c| c.title.clone()).unwrap_or_else(|| a.clone())))
            .collect();
        out.sort();
        out.dedup();
        out
    }

    /// Open tiny fingers on BLOCK, whose screen shows LINES and soft-wraps at WIDTH.
    pub fn open_hints(&mut self, block: &str, lines: &[String], width: usize) {
        self.hints_wanted = false;
        let targets = crate::hints::targets(lines, width);
        if targets.is_empty() {
            self.echo = "Nothing to copy on this pane.".into();
            return;
        }
        self.echo = format!("{} hints: type one to copy, Shift to paste, Esc to quit", targets.len());
        self.hints = Some(HintMode { block: block.into(), session: self.session_id().unwrap_or_default(), targets, input: String::new() });
    }

    fn hint_key(&mut self, k: &KeyPress) -> Vec<Out> {
        let Some(h) = self.hints.as_mut() else { return vec![] };
        if k.key == "escape" || (k.ctrl && (k.key == "c" || k.key == "g")) {
            self.hints = None;
            self.echo.clear();
            return vec![];
        }
        if k.key == "backspace" {
            h.input.pop();
            return vec![];
        }
        let letter = k.key.chars().count() == 1 && k.key.chars().all(|c| c.is_ascii_lowercase()) && !k.ctrl && !k.alt && !k.sup;
        if !letter {
            return vec![];
        }
        let input = format!("{}{}", h.input, k.key);
        match crate::hints::step(&h.targets, &input) {
            Err(()) => {
                h.input.clear();
                vec![]
            }
            Ok(None) => {
                h.input = input;
                vec![]
            }
            Ok(Some(t)) => {
                let text = t.text.clone();
                let (block, session) = (h.block.clone(), h.session.clone());
                self.hints = None;
                let short: String = text.chars().take(40).collect();
                if k.shift {
                    self.echo = format!("Pasted: {short}");
                    return vec![self.rex("block.event", json!({"session_id": session, "block_id": block,
                                                                "event": {"type": "paste", "payload": {"text": text}}}), Tag::Ignore)];
                }
                self.echo = format!("Copied: {short}");
                let mut outs = vec![Out::Clipboard(text.clone())];
                if crate::hints::web_url(&text) {
                    outs.push(Out::Open(text));
                }
                outs
            }
        }
    }

    pub fn key(&mut self, k: &KeyPress) -> Vec<Out> {
        if self.hints.is_some() {
            return self.hint_key(k);
        }
        if self.doc.is_some() && (k.key == "escape" || k.key == "q" || (k.ctrl && k.key == "g")) {
            self.doc = None;
            return vec![];
        }
        if self.minibuffer.is_some() {
            return self.mini_key(k);
        }
        let chord = k.chord();
        if self.prefix.is_empty() && chord == MX_KEY {
            self.minibuffer = Some(Mini { mode: MiniMode::Mx, input: String::new(), selected: 0 });
            return vec![];
        }
        if !self.prefix.is_empty() && (chord == "escape" || chord == "ctrl+g") {
            self.prefix.clear();
            self.echo = "Quit".into();
            return vec![];
        }
        let mut seq = self.prefix.clone();
        seq.push(chord.clone());
        if let Some((_, action)) = self.bindings.iter().find(|(ks, _)| *ks == seq) {
            self.prefix.clear();
            self.echo.clear();
            let action = action.clone();
            return match self.catalogue.iter().find(|c| c.action == action).map(|c| c.id.clone()) {
                Some(id) => self.invoke(&id, vec![], None),
                None => {
                    self.echo = format!("{action} is not a command");
                    vec![]
                }
            };
        }
        if self.bindings.iter().any(|(ks, _)| ks.len() > seq.len() && ks[..seq.len()] == seq[..]) {
            self.prefix = seq;
            self.echo = format!("{}-", self.prefix.join(" "));
            return vec![];
        }
        if !self.prefix.is_empty() {
            self.echo = format!("{} is undefined", seq.join(" "));
            self.prefix.clear();
            return vec![];
        }
        // Not ours: type it into the focused pane through Rex, so init.lua's
        // bindings get their turn.
        let Some(sid) = self.session_id() else { return vec![] };
        let mut payload = json!({"session_id": sid, "key": k.to_rex()});
        if let Some(b) = self.focused_block() {
            payload["block_id"] = json!(b);
        }
        vec![self.rex("client.key", payload, Tag::Ignore)]
    }

    /// The minibuffer's candidates: (label, value), best first.
    pub fn candidates(&self) -> Vec<(String, Json)> {
        let Some(m) = &self.minibuffer else { return vec![] };
        let items: Vec<(String, Json)> = match &m.mode {
            MiniMode::Mx => self.catalogue.iter().filter(|c| c.enabled)
                .map(|c| (format!("{}  ({})", c.title, c.id), json!(c.id))).collect(),
            MiniMode::Pick { items, .. } => items.clone(),
            MiniMode::Prompt { specs, index, .. } => {
                let spec = &specs[*index];
                if !spec.choices.is_empty() {
                    spec.choices.iter().map(|c| (c.clone(), json!(c))).collect()
                } else {
                    match spec.complete.as_deref() {
                        Some("commands") => self.catalogue.iter().map(|c| (c.id.clone(), json!(c.id))).collect(),
                        Some("agents") => self.agents.iter().map(|a| (format!("{} {} {}", a.state, a.app, a.msg), json!(a.block))).collect(),
                        Some("session") => self.server().sessions.iter().map(|(id, l)| (if l.is_empty() { id.clone() } else { l.clone() }, json!(id))).collect(),
                        _ => vec![],
                    }
                }
            }
        };
        let mut scored: Vec<(i64, usize, (String, Json))> = items.into_iter().enumerate()
            .filter_map(|(i, it)| fuzzy(&m.input, &it.0).map(|s| (s, i, it)))
            .collect();
        if !m.input.is_empty() {
            scored.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
        }
        scored.into_iter().map(|(_, _, it)| it).collect()
    }

    pub fn prompt_text(&self) -> String {
        match self.minibuffer.as_ref().map(|m| &m.mode) {
            Some(MiniMode::Mx) => "M-x ".into(),
            Some(MiniMode::Pick { title, .. }) => format!("{title}: "),
            Some(MiniMode::Prompt { specs, index, command, .. }) => {
                let s = &specs[*index];
                format!("{command} {}{}: ", s.name, if s.kind.is_empty() { String::new() } else { format!(" ({})", s.kind) })
            }
            None => String::new(),
        }
    }

    fn mini_key(&mut self, k: &KeyPress) -> Vec<Out> {
        let chord = k.chord();
        let count = self.candidates().len();
        let m = self.minibuffer.as_mut().unwrap();
        match chord.as_str() {
            "escape" | "ctrl+g" => {
                self.minibuffer = None;
                self.echo = "Quit".into();
                // Quitting also drops commands typed here that have not
                // answered yet, so a late prompt cannot reopen the minibuffer.
                self.pending.retain(|_, origin| origin.is_some());
                let pending = &self.pending;
                self.invoked.retain(|id, _| pending.contains_key(id));
                return vec![];
            }
            "backspace" => {
                m.input.pop();
                m.selected = 0;
            }
            "ctrl+u" => {
                m.input.clear();
                m.selected = 0;
            }
            "down" | "ctrl+n" | "tab" => m.selected = if count == 0 { 0 } else { (m.selected + 1) % count },
            "up" | "ctrl+p" | "shift+tab" => m.selected = if count == 0 { 0 } else { (m.selected + count - 1) % count },
            "enter" => return self.mini_accept(),
            _ => {
                if let Some(t) = k.text.as_ref().filter(|_| !k.ctrl && !k.alt && !k.sup) {
                    if t != "\r" && t != "\t" {
                        m.input.push_str(t);
                        m.selected = 0;
                    }
                }
            }
        }
        vec![]
    }

    fn mini_accept(&mut self) -> Vec<Out> {
        let cands = self.candidates();
        let m = self.minibuffer.take().unwrap();
        let chosen = cands.get(m.selected.min(cands.len().saturating_sub(1))).cloned();
        match m.mode {
            MiniMode::Mx => match chosen {
                Some((_, id)) => {
                    let id = id.as_str().unwrap_or("").to_string();
                    self.invoke(&id, vec![], None)
                }
                None => {
                    self.echo = format!("no command matches {}", m.input);
                    vec![]
                }
            },
            MiniMode::Pick { then, arg, .. } => match chosen {
                Some((_, v)) => {
                    self.invoke(&then, vec![(arg, v)], None)
                }
                None => vec![],
            },
            MiniMode::Prompt { command, specs, index, mut partial } => {
                let spec = &specs[index];
                let value = if !spec.choices.is_empty() || spec.complete.is_some() {
                    match chosen {
                        Some((_, v)) => v,
                        None if spec.complete.is_some() && !m.input.is_empty() => coerce(spec, &m.input),
                        None => {
                            self.echo = format!("{} must be one of {}", spec.name, spec.choices.join(", "));
                            self.minibuffer = Some(Mini { mode: MiniMode::Prompt { command, specs, index, partial }, input: m.input, selected: 0 });
                            return vec![];
                        }
                    }
                } else {
                    coerce(spec, &m.input)
                };
                partial.push((spec.name.clone(), value));
                if index + 1 < specs.len() {
                    self.minibuffer = Some(Mini { mode: MiniMode::Prompt { command, specs, index: index + 1, partial }, input: String::new(), selected: 0 });
                    vec![]
                } else {
                    self.invoke(&command, partial, None)
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn with_startup() -> Model {
        let mut m = Model::new(vec![("t".into(), None)]);
        m.startup = Some(json!({"label": "main", "windows": [
            {"label": "emacs", "command": ["emacs-project-start"]},
            {"label": "bbh", "command": ["emacs-bbh"]},
            {"label": "shell"}]}));
        m
    }

    #[test]
    fn a_server_with_no_sessions_gets_the_startup_session() {
        let mut m = with_startup();
        let outs = m.rex_response(Tag::Sessions { server: 0 }, Ok(json!({"sessions": []})));
        let Some(Out::Rex { method, payload, .. }) = outs.first() else { panic!("no request") };
        assert_eq!(method, "session.create");
        assert_eq!(payload["label"], "main");
        let ws = payload["initial_windows"].as_array().unwrap();
        assert_eq!(ws.len(), 3);
        assert_eq!(ws[1]["window_label"], "bbh");
        assert_eq!(ws[1]["layout"]["block"]["options"]["command"], json!(["emacs-bbh"]));
        assert!(ws[2]["layout"]["block"].get("options").is_none(), "no command is a login shell");
    }

    #[test]
    fn a_server_with_only_other_sessions_still_gets_the_startup_session() {
        let mut m = with_startup();
        m.shell = Some("fish".into());
        let outs = m.rex_response(Tag::Sessions { server: 0 }, Ok(json!({"sessions": [
            {"session_id": "session:x", "label": "spike"}, {"session_id": "session:y", "label": "w242"}]})));
        let Some(Out::Rex { method, payload, .. }) = outs.first() else { panic!("no request") };
        assert_eq!(method, "session.create");
        for w in payload["initial_windows"].as_array().unwrap() {
            assert_eq!(w["layout"]["block"]["options"]["shell"], "fish");
        }
    }

    #[test]
    fn new_shell_blocks_get_the_configured_shell() {
        let mut v = json!({"layout": {"split": {"before": {"block": {"flavor": "com.superlogical.terminal.shell"}},
                                                "after": {"block": {"flavor": "com.superlogical.terminal.shell", "options": {"shell": "none"}}}}}});
        with_shell(&mut v, "fish");
        assert_eq!(v["layout"]["split"]["before"]["block"]["options"]["shell"], "fish");
        assert_eq!(v["layout"]["split"]["after"]["block"]["options"]["shell"], "none", "an explicit shell stays");
    }

    #[test]
    fn a_restored_bare_shell_gets_its_program_back_in_the_configured_shell() {
        let mut m = with_startup();
        m.shell = Some("fish".into());
        m.startup.as_mut().unwrap()["windows"][1]["restore"] = json!(["emacs-bbh", "--resume", "it's"]);
        m.servers[0].view = Some(View { session: "session:a".into(), ..Default::default() });
        let bare = json!({"foreground": {"name": "bash"}});
        let Some(Out::Rex { method, payload, .. }) = m.revive(0, "block:b", "bbh", &bare).pop() else { panic!("nothing typed") };
        assert_eq!(method, "com.superlogical.terminal.write");
        let line = payload["args"]["data"].as_str().unwrap();
        let quoted = line.strip_prefix("exec fish -l -c ").and_then(|l| l.strip_suffix('\r')).expect("exec fish -l -c ...");
        // What the restored shell hands fish -c: the command line, quoted once more.
        let said = std::process::Command::new("sh").args(["-c", &format!("printf %s {quoted}")]).output().unwrap().stdout;
        assert_eq!(String::from_utf8(said).unwrap(), "emacs-bbh --resume 'it'\"'\"'s'");
        // A running program, or a fish shell tab, is left alone; a bash shell tab becomes fish.
        assert!(m.revive(0, "block:b", "bbh", &json!({"foreground": {"name": "emacs"}})).is_empty());
        assert!(m.revive(0, "block:s", "shell", &json!({"foreground": {"name": "fish"}})).is_empty());
        let Some(Out::Rex { payload, .. }) = m.revive(0, "block:s", "shell", &bare).pop() else { panic!() };
        assert_eq!(payload["args"]["data"], "exec fish -l\r");
        // A split or renamed tab's bash pane gets fish too, but not a bash the user started in fish.
        let Some(Out::Rex { payload, .. }) = m.revive(0, "block:t", "", &bare).pop() else { panic!("a bare bash pane stays bash") };
        assert_eq!(payload["args"]["data"], "exec fish -l\r");
        let nested = json!({"child": {"pid": 1, "name": "fish"}, "foreground": {"pid": 2, "name": "bash"}});
        assert!(m.revive(0, "block:t", "", &nested).is_empty());
        assert!(m.revive(0, "block:b", "bbh", &nested).is_empty());
    }

    #[test]
    fn a_perform_request_is_answered_under_its_call_id() {
        // Rex's client_perform_requested names the call call_id; client.reply must echo it.
        let mut m = Model::new(vec![("t".into(), None)]);
        let ev = json!({"type": "client_perform_requested", "payload": {"call_id": "7", "action": "mx.nothing"}});
        let Some(Out::Rex { method, payload, .. }) = m.rex_event(0, &ev).pop() else { panic!("no reply") };
        assert_eq!(method, "client.reply");
        assert_eq!(payload["call_id"], "7");
    }

    #[test]
    fn resizing_moves_the_nearest_border_of_the_right_kind() {
        let layout = json!({"split": {"direction": "horizontal", "ratio": 0.5,
            "before": {"block_id": "block:a"},
            "after": {"split": {"direction": "vertical", "ratio": 0.5,
                "before": {"block_id": "block:b"}, "after": {"block_id": "block:c"}}}}});
        let r = resize_request(&layout, "block:c", "left", 0.05).unwrap();
        assert_eq!((r["direction"].as_str(), r["before_block_id"].as_str(), r["after_block_id"].as_str()),
                   (Some("horizontal"), Some("block:a"), Some("block:b")));
        assert!((r["ratio"].as_f64().unwrap() - 0.45).abs() < 1e-9);
        let r = resize_request(&layout, "block:b", "down", 0.05).unwrap();
        assert_eq!((r["before_block_id"].as_str(), r["after_block_id"].as_str()), (Some("block:b"), Some("block:c")));
        assert!(resize_request(&layout, "block:a", "up", 0.05).is_none(), "no horizontal border above a");
    }

    #[test]
    fn tiny_fingers_copy_open_or_paste_the_picked_text() {
        let mut m = Model::new(vec![("t".into(), None)]);
        m.servers[0].view = Some(View { session: "session:a".into(), ..Default::default() });
        let lines = vec!["see https://example.com/x and /tmp/f".to_string()];
        m.open_hints("block:a", &lines, 80);
        assert_eq!(m.hints.as_ref().unwrap().targets.len(), 2);
        assert_eq!(m.key(&KeyPress::parse("a")), vec![Out::Clipboard("https://example.com/x".into()), Out::Open("https://example.com/x".into())]);
        assert!(m.hints.is_none());
        m.open_hints("block:a", &lines, 80);
        let outs = m.key(&KeyPress::parse("shift+s"));
        let Some(Out::Rex { method, payload, .. }) = outs.first() else { panic!("no paste") };
        assert_eq!((method.as_str(), &payload["event"]["payload"]["text"]), ("block.event", &json!("/tmp/f")));
        m.open_hints("block:a", &lines, 80);
        assert!(m.key(&KeyPress::parse("escape")).is_empty());
        assert!(m.hints.is_none(), "Escape leaves tiny fingers");
    }

    #[test]
    fn the_startup_session_is_shown_first_when_it_exists() {
        let mut m = with_startup();
        let outs = m.rex_response(Tag::Sessions { server: 0 }, Ok(json!({"sessions": [
            {"session_id": "session:a", "label": "main"}, {"session_id": "session:b", "label": "other"}]})));
        let Some(Out::Rex { method, payload, .. }) = outs.first() else { panic!("no request") };
        assert_eq!((method.as_str(), &payload["session_id"]), ("session.attach", &json!("session:a")));
    }
}
