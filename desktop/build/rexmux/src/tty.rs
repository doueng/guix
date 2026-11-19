//! `rexmux tty`: the client in a terminal. It draws `screen::render` as
//! plain text and reads keys from stdin in raw mode. Bombadil's terminal
//! driver tests the window's rules through it (test/bombadil/*.ts).

use std::io::{Read, Write};
use std::sync::mpsc;
use std::time::Duration;

use crate::app::{App, AppEvent};
use crate::keys::KeyPress;
use crate::screen;

struct Raw(libc::termios);

impl Raw {
    fn enter() -> Option<Raw> {
        unsafe {
            let mut t: libc::termios = std::mem::zeroed();
            if libc::tcgetattr(0, &mut t) != 0 {
                return None;
            }
            let old = t;
            libc::cfmakeraw(&mut t);
            libc::tcsetattr(0, libc::TCSANOW, &t);
            Some(Raw(old))
        }
    }
}

impl Drop for Raw {
    fn drop(&mut self) {
        unsafe {
            libc::tcsetattr(0, libc::TCSANOW, &self.0);
        }
        print!("\x1b[?1049l\x1b[?25h");
        let _ = std::io::stdout().flush();
    }
}

fn size() -> (u16, u16) {
    unsafe {
        let mut ws: libc::winsize = std::mem::zeroed();
        if libc::ioctl(1, libc::TIOCGWINSZ, &mut ws) == 0 && ws.ws_col > 0 {
            (ws.ws_col, ws.ws_row)
        } else {
            (100, 30)
        }
    }
}

/// Keys from raw terminal bytes: ESC x is alt+x, control bytes are ctrl+letter,
/// CSI sequences are arrows and friends, and a lone ESC is escape.
pub fn parse_keys(bytes: &[u8]) -> Vec<KeyPress> {
    let mut out = Vec::new();
    let text = String::from_utf8_lossy(bytes);
    let chars: Vec<char> = text.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        i += 1;
        let k = match c {
            '\x1b' => {
                if i < chars.len() && chars[i] == '[' {
                    let mut j = i + 1;
                    while j < chars.len() && !chars[j].is_ascii_alphabetic() && chars[j] != '~' {
                        j += 1;
                    }
                    let fin = chars.get(j).copied().unwrap_or(' ');
                    let params: String = chars[i + 1..j.min(chars.len())].iter().collect();
                    i = j + 1;
                    let name = match (fin, params.as_str()) {
                        ('A', _) => "up",
                        ('B', _) => "down",
                        ('C', _) => "right",
                        ('D', _) => "left",
                        ('H', _) => "home",
                        ('F', _) => "end",
                        ('Z', _) => {
                            out.push(KeyPress::parse("shift+tab"));
                            continue;
                        }
                        ('~', "3") => "delete",
                        ('~', "5") => "pageup",
                        ('~', "6") => "pagedown",
                        _ => continue,
                    };
                    KeyPress::named(name)
                } else if i < chars.len() {
                    let mut k = char_key(chars[i]);
                    i += 1;
                    k.alt = true;
                    k.text = None;
                    k
                } else {
                    KeyPress::named("escape")
                }
            }
            c => char_key(c),
        };
        out.push(k);
    }
    out
}

fn char_key(c: char) -> KeyPress {
    match c {
        '\r' | '\n' => KeyPress { key: "enter".into(), text: Some("\r".into()), ..Default::default() },
        '\t' => KeyPress { key: "tab".into(), text: Some("\t".into()), ..Default::default() },
        '\x7f' | '\x08' => KeyPress::named("backspace"),
        '\x00' => KeyPress { key: "space".into(), ctrl: true, ..Default::default() },
        c if (c as u32) < 27 => KeyPress { key: ((b'a' + c as u8 - 1) as char).to_string(), ctrl: true, ..Default::default() },
        ' ' => KeyPress { key: "space".into(), text: Some(" ".into()), ..Default::default() },
        c if c.is_ascii_uppercase() => KeyPress { key: c.to_ascii_lowercase().to_string(), shift: true, text: Some(c.to_string()), ..Default::default() },
        c => match crate::keys::unshifted(&c.to_string()) {
            Some(base) => KeyPress { key: base, shift: true, text: Some(c.to_string()), ..Default::default() },
            None => KeyPress { key: c.to_string(), text: Some(c.to_string()), ..Default::default() },
        },
    }
}

pub fn run(servers: Vec<(String, Option<String>)>) -> i32 {
    let _raw = Raw::enter();
    print!("\x1b[?1049h\x1b[?25l");
    let (tx, rx) = mpsc::channel();
    let mut app = App::new(servers, "rexmux-tty", tx.clone());
    let (w, h) = size();
    let (pw, ph) = screen::pane_area(w as usize, h as usize);
    app.handle(AppEvent::Resize(pw as u16, ph as u16));
    std::thread::spawn(move || {
        let mut stdin = std::io::stdin();
        let mut buf = [0u8; 256];
        loop {
            match stdin.read(&mut buf) {
                Ok(0) | Err(_) => {
                    let _ = tx.send(AppEvent::Quit);
                    return;
                }
                Ok(n) => {
                    for k in parse_keys(&buf[..n]) {
                        if tx.send(AppEvent::Key(k)).is_err() {
                            return;
                        }
                    }
                }
            }
        }
    });
    let mut last = Vec::new();
    while !app.quit {
        match rx.recv_timeout(Duration::from_millis(16)) {
            Ok(ev) => {
                app.handle(ev);
                while let Ok(ev) = rx.try_recv() {
                    app.handle(ev);
                }
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(_) => break,
        }
        let (w, h) = size();
        let (pw, ph) = screen::pane_area(w as usize, h as usize);
        if app.area != (pw as u16, ph as u16) {
            app.handle(AppEvent::Resize(pw as u16, ph as u16));
        }
        if app.dirty {
            app.dirty = false;
            let lines = screen::render(&app.model, &app.grids, w as usize, h as usize).text();
            if lines != last {
                let mut out = String::from("\x1b[H");
                for (y, l) in lines.iter().enumerate() {
                    out.push_str(&format!("\x1b[{};1H\x1b[2K{l}", y + 1));
                }
                // The cursor sits where the minibuffer types, and is hidden at
                // the top left otherwise, so tests can tell the two apart.
                match &app.model.minibuffer {
                    Some(mb) => {
                        let col = app.model.prompt_text().chars().count() + mb.input.chars().count() + 1;
                        out.push_str(&format!("\x1b[{};{}H\x1b[?25h", h, col));
                    }
                    None => out.push_str("\x1b[?25l\x1b[1;1H"),
                }
                print!("{out}");
                let _ = std::io::stdout().flush();
                last = lines;
            }
        }
    }
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raw_bytes_become_keys() {
        let ks: Vec<String> = parse_keys(b"a\x1bx\x02n\x1b[A\rN%").iter().map(KeyPress::chord).collect();
        assert_eq!(ks, ["a", "alt+x", "ctrl+b", "n", "up", "enter", "shift+n", "%"]);
        assert_eq!(parse_keys(b"%")[0].to_rex()["key"], "digit_5");
    }
}
