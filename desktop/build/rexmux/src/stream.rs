//! One pane's data stream: a WebSocket at /api/data (rex.data.terminal.v1),
//! a Ghostty snapshot, then the program's bytes with Rex's own messages as
//! `ESC _ rex1;<nonce>;<json> ESC \`. The thread owns the libghostty-vt
//! terminal (an FFI handle that stays on one thread) and sends the app plain
//! `Grid`s to draw.

use std::io::{ErrorKind, Read};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow};
use base64::Engine;
use libghostty_vt::render::{CellIterator, RowIterator};
use libghostty_vt::style::Underline;
use libghostty_vt::{RenderState, Terminal, snapshot};
use serde_json::Value as Json;
use tungstenite::{Message, WebSocket};

use crate::rex;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Cell {
    pub text: String,
    pub fg: [u8; 3],
    pub bg: Option<[u8; 3]>,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
}

/// What a renderer needs of one terminal screen.
#[derive(Clone, Debug, Default)]
pub struct Grid {
    pub cols: u16,
    pub rows: u16,
    pub cells: Vec<Cell>,
    pub cursor: Option<(u16, u16)>,
    pub fg: [u8; 3],
    pub bg: [u8; 3],
    pub exited: Option<i64>,
}

impl Grid {
    pub fn line(&self, y: u16) -> String {
        let w = self.cols as usize;
        let start = y as usize * w;
        let mut s: String = self.cells[start..(start + w).min(self.cells.len())]
            .iter()
            .map(|c| if c.text.is_empty() { " " } else { c.text.as_str() })
            .collect();
        s.truncate(s.trim_end().len());
        s
    }

    pub fn text(&self) -> String {
        (0..self.rows).map(|y| self.line(y)).collect::<Vec<_>>().join("\n")
    }
}

pub enum StreamEvent {
    Frame(Grid),
    Closed(String),
}

/// Reads the stream's bytes out of WebSocket messages, for the snapshot
/// decoder, keeping what a message holds past the snapshot.
struct WsReader<'a> {
    ws: &'a mut WebSocket<UnixStream>,
    buf: Vec<u8>,
    pos: usize,
}

impl Read for WsReader<'_> {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        while self.pos >= self.buf.len() {
            match self.ws.read() {
                Ok(Message::Binary(b)) => {
                    self.buf = b.to_vec();
                    self.pos = 0;
                }
                Ok(Message::Close(_)) => return Ok(0),
                Ok(_) => {}
                Err(tungstenite::Error::Io(e)) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                Err(e) => return Err(std::io::Error::other(e.to_string())),
            }
        }
        let n = out.len().min(self.buf.len() - self.pos);
        out[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
        self.pos += n;
        Ok(n)
    }
}

/// Splits Rex's APC messages out of the byte stream, across message
/// boundaries. Bytes that are not ours pass through unchanged.
pub struct Splitter {
    marker: Vec<u8>,
    pending: Vec<u8>,
}

impl Splitter {
    pub fn new(nonce: u64) -> Splitter {
        Splitter { marker: format!("\x1b_rex1;{nonce};").into_bytes(), pending: Vec::new() }
    }

    /// (bytes for the terminal, Rex messages) of INPUT.
    pub fn feed(&mut self, input: &[u8]) -> (Vec<u8>, Vec<Json>) {
        self.pending.extend_from_slice(input);
        let mut out = Vec::new();
        let mut msgs = Vec::new();
        loop {
            match find(&self.pending, &self.marker) {
                Some(i) => {
                    out.extend_from_slice(&self.pending[..i]);
                    let body_start = i + self.marker.len();
                    match find(&self.pending[body_start..], b"\x1b\\") {
                        Some(j) => {
                            if let Ok(v) = serde_json::from_slice(&self.pending[body_start..body_start + j]) {
                                msgs.push(v);
                            }
                            self.pending.drain(..body_start + j + 2);
                        }
                        None => {
                            self.pending.drain(..i);
                            break;
                        }
                    }
                }
                None => {
                    // Keep a tail that could be the start of a marker.
                    let keep = partial_suffix(&self.pending, &self.marker);
                    let cut = self.pending.len() - keep;
                    out.extend_from_slice(&self.pending[..cut]);
                    self.pending.drain(..cut);
                    break;
                }
            }
        }
        (out, msgs)
    }
}

fn find(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len()).position(|w| w == needle)
}

fn partial_suffix(hay: &[u8], marker: &[u8]) -> usize {
    (1..marker.len().min(hay.len() + 1)).rev().find(|&k| hay.ends_with(&marker[..k])).unwrap_or(0)
}

/// Copy what a renderer needs out of TERM.
pub fn grid_of<'a, 'cb>(term: &Terminal<'a, 'cb>, state: &mut RenderState<'a>, rows_it: &mut RowIterator<'a>, cells_it: &mut CellIterator<'a>) -> Result<Grid> {
    let snap = state.update(term)?;
    let colors = snap.colors()?;
    let cols = snap.cols()?;
    let rows = snap.rows()?;
    let mut grid = Grid {
        cols,
        rows,
        cells: Vec::with_capacity(cols as usize * rows as usize),
        cursor: None,
        fg: [colors.foreground.r, colors.foreground.g, colors.foreground.b],
        bg: [colors.background.r, colors.background.g, colors.background.b],
        exited: None,
    };
    if snap.cursor_visible()? {
        if let Some(c) = snap.cursor_viewport()? {
            grid.cursor = Some((c.x, c.y));
        }
    }
    let mut it = rows_it.update(&snap)?;
    while let Some(row) = it.next() {
        let mut ci = cells_it.update(row)?;
        let mut n = 0;
        while let Some(cell) = ci.next() {
            let g = cell.graphemes()?;
            let style = cell.style()?;
            let mut fg = cell.fg_color()?.map(|c| [c.r, c.g, c.b]).unwrap_or(grid.fg);
            let mut bg = cell.bg_color()?.map(|c| [c.r, c.g, c.b]);
            if style.inverse {
                let b = bg.unwrap_or(grid.bg);
                bg = Some(fg);
                fg = b;
            }
            grid.cells.push(Cell {
                text: g.into_iter().collect(),
                fg,
                bg,
                bold: style.bold,
                italic: style.italic,
                underline: style.underline != Underline::None,
            });
            n += 1;
        }
        for _ in n..cols {
            grid.cells.push(Cell::default());
        }
    }
    snap.clean()?;
    Ok(grid)
}

pub struct StreamHandle {
    stop: Arc<AtomicBool>,
}

impl StreamHandle {
    pub fn stop(&self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

impl Drop for StreamHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Open the stream for TICKET (from data.connect) on the server at SOCK,
/// sending each new screen to OUT. Stops when the handle is dropped.
pub fn spawn<F>(sock: PathBuf, ticket: String, out: F) -> StreamHandle
where
    F: Fn(StreamEvent) + Send + 'static,
{
    let stop = Arc::new(AtomicBool::new(false));
    let s = stop.clone();
    std::thread::spawn(move || {
        let why = match run(&sock, &ticket, &s, &out) {
            Ok(()) => "stopped".to_string(),
            Err(e) => e.to_string(),
        };
        out(StreamEvent::Closed(why));
    });
    StreamHandle { stop }
}

fn run<F: Fn(StreamEvent)>(sock: &PathBuf, ticket: &str, stop: &AtomicBool, out: &F) -> Result<()> {
    let raw = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(ticket)?;
    let mut ws = rex::websocket(sock, "/api/data", "rex.data.terminal.v1")?;
    let mut hello = vec![1u8];
    hello.extend_from_slice(&raw);
    ws.send(Message::binary(hello))?;
    let welcome = loop {
        match ws.read()? {
            Message::Binary(b) => break b.to_vec(),
            Message::Close(c) => return Err(anyhow!("closed before welcome: {c:?}")),
            _ => {}
        }
    };
    if welcome.first() != Some(&0x81) || welcome.len() < 32 {
        return Err(anyhow!("data handshake rejected (reason {:?})", welcome.get(1)));
    }
    let nonce = u64::from_le_bytes(welcome[24..32].try_into()?);
    ws.get_mut().set_read_timeout(Some(Duration::from_millis(8)))?;

    let mut reader = WsReader { ws: &mut ws, buf: Vec::new(), pos: 0 };
    let mut term = snapshot::Decoder::new(&mut reader)?.decode()?;
    let rest = reader.buf[reader.pos..].to_vec();
    let mut split = Splitter::new(nonce);
    let mut state = RenderState::new()?;
    let mut rows_it = RowIterator::new()?;
    let mut cells_it = CellIterator::new()?;
    let mut exited = None;
    let mut apply = |bytes: &[u8], term: &mut Terminal, exited: &mut Option<i64>| {
        let (vt, msgs) = split.feed(bytes);
        term.vt_write(&vt);
        for m in msgs {
            match m["type"].as_str() {
                Some("resize") => {
                    let c = m["cols"].as_u64().unwrap_or(80) as u16;
                    let r = m["rows"].as_u64().unwrap_or(24) as u16;
                    let _ = term.resize(c, r, m["cell_width_px"].as_u64().unwrap_or(0) as u32, m["cell_height_px"].as_u64().unwrap_or(0) as u32);
                }
                Some("child_exited") => *exited = m["exit_code"].as_i64().or(Some(0)),
                _ => {}
            }
        }
    };
    apply(&rest, &mut term, &mut exited);
    let mut dirty = true;
    let mut last = Instant::now() - Duration::from_secs(1);
    while !stop.load(Ordering::Relaxed) {
        match ws.read() {
            Ok(Message::Binary(b)) => {
                apply(&b, &mut term, &mut exited);
                dirty = true;
            }
            Ok(Message::Close(c)) => return Err(anyhow!("stream closed: {}", c.map(|c| c.reason.to_string()).unwrap_or_default())),
            Ok(_) => {}
            Err(tungstenite::Error::Io(e)) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
            Err(e) => return Err(e.into()),
        }
        // Batch repaints to about 60 a second.
        if dirty && last.elapsed() >= Duration::from_millis(16) {
            let mut g = grid_of(&term, &mut state, &mut rows_it, &mut cells_it)?;
            g.exited = exited;
            out(StreamEvent::Frame(g));
            dirty = false;
            last = Instant::now();
        }
    }
    let _ = ws.close(Some(tungstenite::protocol::CloseFrame { code: tungstenite::protocol::frame::coding::CloseCode::Normal, reason: "".into() }));
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splitter_finds_messages_across_chunks_and_skips_foreign_nonces() {
        let mut s = Splitter::new(42);
        let (a, m1) = s.feed(b"hello \x1b_rex1;4");
        let (b, m2) = s.feed(b"2;{\"type\":\"resize\",\"cols\":10}\x1b\\world");
        let (c, m3) = s.feed(b"\x1b_rex1;7;{\"type\":\"resize\"}\x1b\\");
        assert_eq!([a, b].concat(), b"hello world");
        assert!(m1.is_empty());
        assert_eq!(m2[0]["cols"], 10);
        assert!(m3.is_empty());
        assert_eq!(c, b"\x1b_rex1;7;{\"type\":\"resize\"}\x1b\\");
    }

    #[test]
    fn grid_reads_what_the_terminal_shows() {
        let mut t = Terminal::new(20, 3).unwrap();
        t.vt_write(b"\x1b[1;31mRED\x1b[0m ok\r\nnext");
        let mut st = RenderState::new().unwrap();
        let mut r = RowIterator::new().unwrap();
        let mut c = CellIterator::new().unwrap();
        let g = grid_of(&t, &mut st, &mut r, &mut c).unwrap();
        assert_eq!(g.line(0), "RED ok");
        assert_eq!(g.line(1), "next");
        assert!(g.cells[0].bold);
        assert_ne!(g.cells[0].fg, g.cells[4].fg);
    }
}
