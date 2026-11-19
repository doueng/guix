//! `rexmux gui`: the client in a GPUI window. It draws the same
//! `screen::render` as the tty frontend, in a monospace font, with each pane
//! cell in its terminal colors, so the Bombadil specs over `rexmux tty` cover
//! what the window shows. Keys go to the model as `KeyPress`es.

use std::sync::mpsc;
use std::time::Duration;

use gpui::{
    App, AppContext, Bounds, Context, FocusHandle, Font, FontStyle, FontWeight, Hsla, InteractiveElement,
    IntoElement, KeyDownEvent, MouseButton, MouseDownEvent, MouseMoveEvent, MouseUpEvent, ParentElement, Pixels,
    Point, Render, Rgba, ScrollDelta, ScrollWheelEvent, StyledText, Styled, Task, TextRun, UnderlineStyle, Window,
    WindowBounds, WindowOptions, div, px, size,
};

use crate::app::{App as Client, AppEvent, Mouse, MouseKind};
use crate::keys::KeyPress;
use crate::screen::{self, CellStyle};

const FONT_SIZE: f32 = 14.0;
const LINE: f32 = 1.3;
const FG: [u8; 3] = [0xd8, 0xd8, 0xd8];
const BG: [u8; 3] = [0x16, 0x16, 0x1a];
const CHROME: [u8; 3] = [0x9a, 0xa4, 0xb4];
const SELECTED: [u8; 3] = [0x2a, 0x33, 0x48];

fn color(c: [u8; 3]) -> Hsla {
    Rgba { r: c[0] as f32 / 255.0, g: c[1] as f32 / 255.0, b: c[2] as f32 / 255.0, a: 1.0 }.into()
}

/// A GPUI key down as a KeyPress, or None for a bare modifier.
pub fn key_of(ks: &gpui::Keystroke) -> Option<KeyPress> {
    let key = ks.key.to_lowercase();
    if matches!(key.as_str(), "shift" | "control" | "alt" | "platform" | "function" | "") {
        return None;
    }
    let m = &ks.modifiers;
    // A keymap with a capital letter on its own key (as wtype makes) types it without shift.
    let one_capital = |s: &str| s.chars().count() == 1 && s.chars().all(|c| c.is_ascii_uppercase());
    let capital = one_capital(&ks.key) || ks.key_char.as_deref().is_some_and(one_capital);
    let mut k = KeyPress { key, ctrl: m.control, alt: m.alt, shift: m.shift || capital, sup: m.platform, text: None };
    // Some keymaps name a shifted symbol itself (% rather than shift-5); keep the physical key.
    if let Some(base) = crate::keys::unshifted(&k.key) {
        k.key = base;
        k.shift = true;
    }
    if !k.ctrl && !k.alt && !k.sup {
        k.text = ks.key_char.clone().or_else(|| crate::keys::typed(&k.key, k.shift));
    }
    Some(k)
}

/// The family fontconfig picks for monospace.
fn monospace() -> String {
    std::process::Command::new("fc-match")
        .args(["-f", "%{family[0]}", "monospace"])
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "DejaVu Sans Mono".to_string())
}

struct RexView {
    client: Client,
    rx: mpsc::Receiver<AppEvent>,
    focus: FocusHandle,
    font: Font,
    /// One cell's width and height in pixels, from the last render.
    cell: (f32, f32),
    /// Wheel movement not yet a whole row.
    wheel: f32,
    _poll: Task<()>,
}

fn button_name(b: MouseButton) -> &'static str {
    match b {
        MouseButton::Right => "right",
        MouseButton::Middle => "middle",
        _ => "left",
    }
}

impl RexView {
    fn new(servers: Vec<(String, Option<String>)>, family: String, window: &mut Window, cx: &mut Context<Self>) -> RexView {
        let (tx, rx) = mpsc::channel();
        let client = Client::new(servers, "rexmux", tx);
        let focus = cx.focus_handle();
        window.focus(&focus, cx);
        let poll = cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(Duration::from_millis(16)).await;
                if this.update(cx, |v, cx| v.drain(cx)).is_err() {
                    break;
                }
            }
        });
        RexView { client, rx, focus, font: gpui::font(family), cell: (8.0, FONT_SIZE * LINE), wheel: 0.0, _poll: poll }
    }

    fn drain(&mut self, cx: &mut Context<Self>) {
        while let Ok(ev) = self.rx.try_recv() {
            self.client.handle(ev);
        }
        if self.client.quit {
            cx.quit();
        } else if self.client.dirty {
            self.client.dirty = false;
            cx.notify();
        }
    }

    fn on_key(&mut self, ev: &KeyDownEvent, cx: &mut Context<Self>) {
        if std::env::var_os("REXMUX_DEBUG").is_some() {
            eprintln!("rexmux: key {:?} -> {:?}", ev.keystroke, key_of(&ev.keystroke).map(|k| k.chord()));
        }
        if let Some(k) = key_of(&ev.keystroke) {
            self.client.handle(AppEvent::Key(k));
            cx.stop_propagation();
            self.drain(cx);
            cx.notify();
        }
    }

    /// The screen cell under window position P (the view has 4px of padding).
    fn cell_at(&self, p: Point<Pixels>) -> (usize, usize) {
        let (x, y): (f32, f32) = (p.x.into(), p.y.into());
        (((x - 4.0) / self.cell.0).max(0.0) as usize, ((y - 4.0) / self.cell.1).max(0.0) as usize)
    }

    fn on_mouse(&mut self, kind: MouseKind, p: Point<Pixels>, button: &'static str, cx: &mut Context<Self>) {
        let (col, row) = self.cell_at(p);
        if std::env::var_os("REXMUX_DEBUG").is_some() {
            eprintln!("rexmux: mouse {kind:?} {button} at cell {col},{row} -> {:?}", self.client.pane_at(col, row));
        }
        self.client.handle(AppEvent::Mouse(Mouse { kind, col, row, button }));
        self.drain(cx);
        cx.notify();
    }

    fn on_wheel(&mut self, ev: &ScrollWheelEvent, cx: &mut Context<Self>) {
        // GPUI's delta is positive when the content should move down, that is
        // when scrolling up into history; Rex counts rows down as positive.
        let rows = match ev.delta {
            ScrollDelta::Lines(d) => -d.y * 3.0,
            ScrollDelta::Pixels(d) => -f32::from(d.y) / self.cell.1,
        };
        self.wheel += rows;
        let whole = self.wheel.trunc();
        if whole != 0.0 {
            self.wheel -= whole;
            self.on_mouse(MouseKind::Wheel(whole as i64), ev.position, "left", cx);
        }
    }

    fn run(&self, text: &str, style: Option<CellStyle>, cursor: bool) -> TextRun {
        let (mut fg, mut bg, bold, italic, underline) = match style {
            Some(s) => (s.fg, s.bg, s.bold, s.italic, s.underline),
            None => (CHROME, None, false, false, false),
        };
        if cursor {
            let f = fg;
            fg = bg.unwrap_or(BG);
            bg = Some(f);
        }
        let mut font = self.font.clone();
        if bold {
            font.weight = FontWeight::BOLD;
        }
        if italic {
            font.style = FontStyle::Italic;
        }
        TextRun {
            len: text.len(),
            font,
            color: color(fg),
            background_color: bg.map(color),
            underline: underline.then(|| UnderlineStyle { thickness: px(1.0), color: None, wavy: false }),
            strikethrough: None,
        }
    }
}

impl Render for RexView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let ts = window.text_system();
        let id = ts.resolve_font(&self.font);
        let cw: f32 = ts.advance(id, px(FONT_SIZE), 'm').map(|s| s.width.into()).unwrap_or(FONT_SIZE * 0.6);
        let lh = FONT_SIZE * LINE;
        self.cell = (cw, lh);
        let vp = window.viewport_size();
        let (vw, vh): (f32, f32) = (vp.width.into(), vp.height.into());
        let w = ((vw - 8.0) / cw).floor().max(20.0) as usize;
        let h = ((vh - 8.0) / lh).floor().max(6.0) as usize;
        let (pw, ph) = screen::pane_area(w, h);
        if self.client.area != (pw as u16, ph as u16) {
            self.client.handle(AppEvent::Resize(pw as u16, ph as u16));
        }
        let s = screen::render(&self.client.model, &self.client.grids, w, h);
        let mut rows = Vec::with_capacity(h);
        for y in 0..s.h {
            let mut text = String::new();
            let mut runs: Vec<TextRun> = Vec::new();
            let mut x = 0;
            while x < s.w {
                let style = s.styles[y][x];
                let cursor = s.cursor == Some((x, y));
                let mut chunk = String::new();
                while x < s.w && s.styles[y][x] == style && (s.cursor == Some((x, y))) == cursor {
                    chunk.push(s.rows[y][x]);
                    x += 1;
                    if cursor {
                        break;
                    }
                }
                runs.push(self.run(&chunk, style, cursor));
                text.push_str(&chunk);
            }
            let row = div().h(px(lh)).child(StyledText::new(text).with_runs(runs));
            rows.push(if s.selected == Some(y) { row.bg(color(SELECTED)) } else { row });
        }
        div()
            .track_focus(&self.focus)
            .on_key_down(cx.listener(|this, ev: &KeyDownEvent, _, cx| this.on_key(ev, cx)))
            .on_any_mouse_down(cx.listener(|this, ev: &MouseDownEvent, _, cx| {
                this.on_mouse(MouseKind::Press, ev.position, button_name(ev.button), cx)
            }))
            .on_mouse_up(MouseButton::Left, cx.listener(|this, ev: &MouseUpEvent, _, cx| {
                this.on_mouse(MouseKind::Release, ev.position, "left", cx)
            }))
            .on_mouse_move(cx.listener(|this, ev: &MouseMoveEvent, _, cx| {
                if ev.pressed_button == Some(MouseButton::Left) {
                    this.on_mouse(MouseKind::Drag, ev.position, "left", cx)
                }
            }))
            .on_scroll_wheel(cx.listener(|this, ev: &ScrollWheelEvent, _, cx| this.on_wheel(ev, cx)))
            .size_full()
            .p(px(4.0))
            .bg(color(BG))
            .text_color(color(FG))
            .text_size(px(FONT_SIZE))
            .line_height(px(lh))
            .font(self.font.clone())
            .children(rows)
    }
}

pub fn run(servers: Vec<(String, Option<String>)>) -> i32 {
    // Ask fontconfig before configure_fontconfig swaps in a config without aliases.
    let family = std::env::var("REXMUX_FONT").unwrap_or_else(|_| monospace());
    crate::fonts::configure_fontconfig();
    crate::fonts::configure_vulkan();
    gpui_platform::application().run(move |cx: &mut App| {
        let options = WindowOptions {
            window_bounds: Some(WindowBounds::Windowed(Bounds::centered(None, size(px(1100.0), px(720.0)), cx))),
            app_id: Some("rexmux".to_string()),
            ..Default::default()
        };
        if let Err(e) = cx.open_window(options, move |window, cx| cx.new(|cx| RexView::new(servers, family, window, cx))) {
            eprintln!("rexmux: cannot open a window: {e:#}");
            cx.quit();
        }
    });
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gpui_keys_become_rex_chords() {
        let ks = |s: &str| gpui::Keystroke::parse(s).unwrap();
        assert_eq!(key_of(&ks("ctrl-b")).unwrap().chord(), "ctrl+b");
        assert_eq!(key_of(&ks("alt-x")).unwrap().chord(), "alt+x");
        let n = key_of(&ks("shift-n")).unwrap();
        assert_eq!((n.chord().as_str(), n.text.as_deref()), ("shift+n", Some("N")));
        assert_eq!(key_of(&ks("enter")).unwrap().text.as_deref(), Some("\r"));
        assert_eq!(key_of(&ks("shift-5")).unwrap().chord(), "%");
    }
}
