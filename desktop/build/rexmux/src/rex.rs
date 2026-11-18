//! The control connection to a Rex server: a WebSocket on its Unix socket,
//! subprotocol rex.control.v1. One I/O thread owns the socket: it reads with a
//! short timeout and writes whatever was queued in between, so requests and
//! events never wait on each other.

use std::collections::HashMap;
use std::io::ErrorKind;
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result, anyhow};
use serde_json::{Value as Json, json};
use tungstenite::client::IntoClientRequest;
use tungstenite::{Message, WebSocket};

pub const API_REVISION: i64 = 18;
pub const API_MIN_REVISION: i64 = 14;

/// The socket path of a server address: unix:///path, or a bare path. None
/// means the local default, REX_SERVER or /run/user/UID/rex/server.sock.
pub fn socket_path(address: Option<&str>) -> PathBuf {
    let addr = address
        .map(str::to_string)
        .or_else(|| std::env::var("REX_SERVER").ok())
        .unwrap_or_else(|| format!("/run/user/{}/rex/server.sock", unsafe { libc::getuid() }));
    PathBuf::from(addr.strip_prefix("unix://").unwrap_or(&addr))
}

/// Open a WebSocket at PATH on the server's socket with SUBPROTOCOL.
pub fn websocket(sock: &PathBuf, path: &str, subprotocol: &str) -> Result<WebSocket<UnixStream>> {
    let stream = UnixStream::connect(sock).with_context(|| format!("connect {}", sock.display()))?;
    let mut req = format!("ws://localhost{path}").into_client_request()?;
    req.headers_mut().insert("Sec-WebSocket-Protocol", subprotocol.parse()?);
    let (ws, _) = tungstenite::client::client(req, stream).map_err(|e| anyhow!("websocket {path}: {e}"))?;
    Ok(ws)
}

/// Something the server sent that the app should see.
#[derive(Debug)]
pub enum RexIn {
    Response { tag: u64, result: std::result::Result<Json, String> },
    Event(Json),
    Closed(String),
}

enum Reply {
    App(u64),
    Wait(Sender<std::result::Result<Json, String>>),
}

pub struct Control {
    out: Sender<String>,
    waiting: Arc<Mutex<HashMap<String, Reply>>>,
    next: Arc<Mutex<u64>>,
    pub client_id: String,
    pub api_revision: i64,
}

impl Control {
    /// Connect, say hello, and start the I/O thread. Responses to
    /// `request` and every event arrive on APP, wrapped by WRAP.
    pub fn connect<F>(address: Option<&str>, label: &str, app: F) -> Result<Control>
    where
        F: Fn(RexIn) + Send + 'static,
    {
        let sock = socket_path(address);
        let mut ws = websocket(&sock, "/api/control", "rex.control.v1")?;
        let hello = json!({"type": "hello", "api_revision": API_REVISION, "api_min_revision": API_MIN_REVISION,
                           "client": {"kind": "app", "platform": "linux", "name": "rexmux",
                                      "version": env!("CARGO_PKG_VERSION"), "label": label}});
        ws.send(Message::text(hello.to_string()))?;
        let welcome: Json = loop {
            match ws.read()? {
                Message::Text(t) => break serde_json::from_str(t.as_str())?,
                Message::Close(c) => return Err(anyhow!("server closed: {c:?}")),
                _ => {}
            }
        };
        if welcome["type"] != "welcome" {
            return Err(anyhow!("expected welcome, got {welcome}"));
        }
        let client_id = welcome["client"]["id"].as_str().unwrap_or("").to_string();
        let api_revision = welcome["api_revision"].as_i64().unwrap_or(0);
        ws.get_mut().set_read_timeout(Some(Duration::from_millis(5)))?;

        let (out, queue) = mpsc::channel::<String>();
        let waiting: Arc<Mutex<HashMap<String, Reply>>> = Arc::default();
        let w = waiting.clone();
        std::thread::spawn(move || io_loop(ws, queue, w, app));
        Ok(Control { out, waiting, next: Arc::new(Mutex::new(0)), client_id, api_revision })
    }

    fn send(&self, method: &str, payload: Json, reply: Reply) -> String {
        let id = {
            let mut n = self.next.lock().unwrap();
            *n += 1;
            n.to_string()
        };
        self.waiting.lock().unwrap().insert(id.clone(), reply);
        let msg = json!({"type": "request", "id": id, "method": method, "payload": payload});
        let _ = self.out.send(msg.to_string());
        id
    }

    /// Send a request; its response arrives on the app channel with TAG.
    pub fn request(&self, method: &str, payload: Json, tag: u64) {
        self.send(method, payload, Reply::App(tag));
    }

    /// Send a request and wait for its result.
    pub fn call(&self, method: &str, payload: Json) -> Result<Json> {
        let (tx, rx) = mpsc::channel();
        self.send(method, payload, Reply::Wait(tx));
        match rx.recv_timeout(Duration::from_secs(10)) {
            Ok(Ok(v)) => Ok(v),
            Ok(Err(e)) => Err(anyhow!("{method}: {e}")),
            Err(_) => Err(anyhow!("{method}: no response")),
        }
    }
}

fn io_loop<F: Fn(RexIn)>(mut ws: WebSocket<UnixStream>, queue: Receiver<String>, waiting: Arc<Mutex<HashMap<String, Reply>>>, app: F) {
    loop {
        loop {
            match queue.try_recv() {
                Ok(text) => {
                    if let Err(e) = ws.send(Message::text(text)) {
                        app(RexIn::Closed(e.to_string()));
                        return;
                    }
                }
                Err(mpsc::TryRecvError::Empty) => break,
                Err(mpsc::TryRecvError::Disconnected) => return,
            }
        }
        match ws.read() {
            Ok(Message::Text(t)) => {
                let Ok(v) = serde_json::from_str::<Json>(t.as_str()) else { continue };
                match v["type"].as_str() {
                    Some("response") => {
                        let id = v["id"].as_str().unwrap_or("").to_string();
                        let result = if v.get("error").is_some_and(|e| !e.is_null()) {
                            Err(format!("{} ({})", v["error"]["message"].as_str().unwrap_or("error"), v["error"]["code"].as_str().unwrap_or("")))
                        } else {
                            Ok(v.get("payload").cloned().unwrap_or(Json::Null))
                        };
                        match waiting.lock().unwrap().remove(&id) {
                            Some(Reply::App(tag)) => app(RexIn::Response { tag, result }),
                            Some(Reply::Wait(tx)) => {
                                let _ = tx.send(result);
                            }
                            None => {}
                        }
                    }
                    Some("event") => app(RexIn::Event(v["payload"].clone())),
                    _ => {}
                }
            }
            Ok(Message::Close(c)) => {
                app(RexIn::Closed(format!("{c:?}")));
                return;
            }
            Ok(_) => {}
            Err(tungstenite::Error::Io(e)) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
            Err(e) => {
                app(RexIn::Closed(e.to_string()));
                return;
            }
        }
    }
}
