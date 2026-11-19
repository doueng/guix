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
    /// Each cell's terminal style, for cells a pane drew; None for chrome.
    pub styles: Vec<Vec<Option<CellStyle>>>,
    /// The focused pane's cursor, in screen cells.
    pub cursor: Option<(usize, usize)>,
    /// The row of the selected minibuffer candidate.
    pub selected: Option<usize>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CellStyle {
    pub fg: [u8; 3],
    pub bg: Option<[u8; 3]>,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
}

impl Screen {
    fn new(w: usize, h: usize) -> Screen {
        Screen { w, h, rows: vec![vec![' '; w]; h], styles: vec![vec![None; w]; h], cursor: None, selected: None }
    }

    fn put(&mut self, x: usize, y: usize, text: &str, limit: usize) {
        if y >= self.h {
            return;
        }
        for (i, c) in text.chars().take(limit).enumerate() {
            if x + i < self.w {
                self.rows[y][x + i] = c;
                self.styles[y][x + i] = None;
            }
        }
    }

    fn line(&mut self, y: usize, text: &str) {
        if y < self.h {
            self.rows[y] = vec![' '; self.w];
            self.styles[y] = vec![None; self.w];
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

/// Where tile RECT goes in a PW x PH pane area, in cells: (x, y, width,
/// height) of its title row and content, below the tab bar. A tile that does
/// not start at the left edge gives its first column to a │ divider.
pub fn tile_cells(rect: [f32; 4], pw: usize, ph: usize) -> (usize, usize, usize, usize) {
    let left = (rect[0] * pw as f32).round() as usize;
    let right = ((rect[0] + rect[2]) * pw as f32).round() as usize;
    let x0 = if left > 0 { left + 1 } else { left };
    let y0 = 1 + (rect[1] * ph as f32).round() as usize;
    let th = ((rect[3] * ph as f32).round() as usize).max(1);
    (x0, y0, right.saturating_sub(x0).max(1), th)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn side_by_side_tiles_share_the_width_with_one_divider() {
        let (pw, ph) = (101, 30);
        let a = tile_cells([0.0, 0.0, 0.5, 1.0], pw, ph);
        let b = tile_cells([0.5, 0.0, 0.5, 1.0], pw, ph);
        assert_eq!(a.0, 0);
        assert_eq!(b.0, a.0 + a.2 + 1, "one column between them for the divider");
        assert_eq!(b.0 + b.2, pw, "together they fill the width");
        assert_eq!((a.1, a.3), (1, ph));
    }
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
            let (x0, y0, tw, th) = tile_cells(t.rect, pw, ph);
            if x0 > 0 {
                for y in y0..(y0 + th).min(s.h.saturating_sub(2)) {
                    s.put(x0 - 1, y, "│", 1);
                }
            }
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
                    let sy = y0 + 1 + y;
                    for x in 0..tw.min(g.cols as usize) {
                        if sy < s.h && x0 + x < s.w {
                            if let Some(c) = g.cells.get(y * g.cols as usize + x) {
                                s.styles[sy][x0 + x] = Some(CellStyle { fg: c.fg, bg: c.bg, bold: c.bold, italic: c.italic, underline: c.underline });
                            }
                        }
                    }
                }
                if focused.as_deref() == Some(t.block.as_str()) {
                    if let Some((cx, cy)) = g.cursor {
                        if (cx as usize) < tw && (cy as usize) + 1 < th {
                            s.cursor = Some((x0 + cx as usize, y0 + 1 + cy as usize));
                        }
                    }
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
        // The candidates sit right above the status line, the selected one marked.
        let room = ph.min(10).min(cands.len());
        let first = mb.selected.saturating_sub(room.saturating_sub(1));
        for (i, (label, _)) in cands.iter().skip(first).take(room).enumerate() {
            let sel = first + i == mb.selected;
            let y = h.saturating_sub(3) - (room - 1 - i).min(h.saturating_sub(3));
            s.line(y, &format!("{} {label}", if sel { ">" } else { " " }));
            if sel {
                s.selected = Some(y);
            }
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
