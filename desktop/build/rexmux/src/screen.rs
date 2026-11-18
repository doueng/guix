//! The model as rows of text: the tty frontend prints these, and the
//! Bombadil specs read them. Row 0 is the tab bar, then the panes (each with
//! a title row, ▶ on the focused one), then the status line and the echo or
//! minibuffer line. The minibuffer, which-key and help overlay the panes.

use std::collections::HashMap;

use crate::model::Model;
use crate::stream::Grid;

pub struct Screen {
    pub w: usize,
    pub h: usize,
    pub rows: Vec<Vec<char>>,
}

impl Screen {
    fn new(w: usize, h: usize) -> Screen {
        Screen { w, h, rows: vec![vec![' '; w]; h] }
    }

    fn put(&mut self, x: usize, y: usize, text: &str, limit: usize) {
        if y >= self.h {
            return;
        }
        for (i, c) in text.chars().take(limit).enumerate() {
            if x + i < self.w {
                self.rows[y][x + i] = c;
            }
        }
    }

    fn line(&mut self, y: usize, text: &str) {
        if y < self.h {
            self.rows[y] = vec![' '; self.w];
            self.put(0, y, text, self.w);
        }
    }

    pub fn text(&self) -> Vec<String> {
        self.rows.iter().map(|r| r.iter().collect::<String>().trim_end().to_string()).collect()
    }
}

pub fn short(id: &str) -> &str {
    &id[id.len().saturating_sub(6)..]
}

/// The pane area of a W x H screen: rows 1 to H-3.
pub fn pane_area(w: usize, h: usize) -> (usize, usize) {
    (w, h.saturating_sub(3))
}

pub fn render(m: &Model, grids: &HashMap<String, Grid>, w: usize, h: usize) -> Screen {
    let mut s = Screen::new(w, h);
    let (pw, ph) = pane_area(w, h);
    // Tabs: the session's windows.
    let mut tabs = String::new();
    if let Some(v) = m.view() {
        tabs.push_str(&format!("{} │", if v.label.is_empty() { short(&v.session) } else { &v.label }));
        for (i, win) in v.windows.iter().enumerate() {
            let active = Some(&win.id) == v.active.as_ref() || (v.active.is_none() && i == 0);
            let name = if win.label.is_empty() { format!("{}", i + 1) } else { format!("{} {}", i + 1, win.label) };
            tabs.push_str(&if active { format!(" [{name}]") } else { format!("  {name} ") });
        }
    } else {
        tabs.push_str("no session");
    }
    s.line(0, &tabs);
    // Panes.
    let focused = m.focused_block();
    if let Some(v) = m.view() {
        for t in v.visible() {
            let x0 = (t.rect[0] * pw as f32).round() as usize;
            let y0 = 1 + (t.rect[1] * ph as f32).round() as usize;
            let tw = ((t.rect[2] * pw as f32).round() as usize).max(1);
            let th = ((t.rect[3] * ph as f32).round() as usize).max(1);
            let agent = m.agents.iter().find(|a| a.block == t.block);
            let mark = if focused.as_deref() == Some(t.block.as_str()) { "▶" } else { "─" };
            let status = agent.map(|a| format!(" {} {}", crate::model::icon(&a.state), a.app)).unwrap_or_default();
            let title = format!("{mark} {}{status} ", short(&t.block));
            let fill: String = std::iter::repeat_n('─', tw).collect();
            s.put(x0, y0, &fill, tw);
            s.put(x0, y0, &title, tw);
            if let Some(g) = grids.get(&t.block) {
                for y in 0..(th.saturating_sub(1)).min(g.rows as usize) {
                    s.put(x0, y0 + 1 + y, &g.line(y as u16), tw);
                }
            }
        }
    }
    // Status line: server, session, attention.
    let blocked = m.agents.iter().filter(|a| a.state == "blocked").count();
    let status = format!("{} │ focus {} │ agents {} │ blocked {} │ attention {}{}",
                         m.server().label,
                         focused.as_deref().map(short).unwrap_or("-"),
                         m.agents.len(), blocked, m.attention.len(),
                         if m.sidecar_up { "" } else { " │ sidecar down" });
    s.line(h.saturating_sub(2), &status);
    // Overlays and the bottom line.
    if let Some((title, text)) = &m.doc {
        let lines: Vec<&str> = text.lines().collect();
        s.line(1, &format!("── {title} (q closes) ──"));
        for (i, l) in lines.iter().take(ph.saturating_sub(1)).enumerate() {
            s.line(2 + i, l);
        }
    }
    if let Some(mb) = &m.minibuffer {
        let cands = m.candidates();
        let room = ph.min(10);
        let first = mb.selected.saturating_sub(room.saturating_sub(1));
        for (i, (label, _)) in cands.iter().skip(first).take(room).enumerate() {
            let sel = first + i == mb.selected;
            s.line(h.saturating_sub(3) - (room - 1 - i).min(h.saturating_sub(3)), &format!("{} {label}", if sel { ">" } else { " " }));
        }
        s.line(h.saturating_sub(1), &format!("{}{}", m.prompt_text(), mb.input));
    } else if !m.prefix.is_empty() {
        let conts = m.continuations();
        let room = ph.min(conts.len());
        for (i, (k, t)) in conts.iter().take(room).enumerate() {
            s.line(h.saturating_sub(2 + room) + i, &format!("  {k}  {t}"));
        }
        s.line(h.saturating_sub(1), &format!("{}-", m.prefix.join(" ")));
    } else {
        s.line(h.saturating_sub(1), &m.echo);
    }
    s
}
