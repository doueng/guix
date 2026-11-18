//! Key presses, named the way Rex keymaps name them ("ctrl+b", "alt+x",
//! "shift+n", "enter"), and their translation to client.key, which wants
//! the physical key as a W3C code in snake case.

use serde_json::{Value as Json, json};

#[derive(Clone, Debug, Default, PartialEq)]
pub struct KeyPress {
    /// Lower-case key name: a letter, digit or symbol, or a named key such as
    /// enter, escape, backspace, tab, space, up, down, left, right.
    pub key: String,
    pub ctrl: bool,
    pub alt: bool,
    pub shift: bool,
    pub sup: bool,
    /// The text the key types, if any.
    pub text: Option<String>,
}

impl KeyPress {
    pub fn named(key: &str) -> KeyPress {
        KeyPress { key: key.to_string(), ..Default::default() }
    }

    /// Parse a chord such as "ctrl+b" or "shift+n".
    pub fn parse(chord: &str) -> KeyPress {
        let mut k = KeyPress::default();
        let parts: Vec<&str> = chord.split('+').collect();
        let (mods, key) = if chord.ends_with("++") || chord == "+" {
            (&parts[..parts.len().saturating_sub(2)], "+")
        } else {
            (&parts[..parts.len() - 1], *parts.last().unwrap())
        };
        for m in mods {
            match *m {
                "ctrl" => k.ctrl = true,
                "alt" => k.alt = true,
                "shift" => k.shift = true,
                "super" | "cmd" => k.sup = true,
                _ => {}
            }
        }
        k.key = key.to_lowercase();
        if !k.ctrl && !k.alt && !k.sup {
            k.text = typed(&k.key, k.shift);
        }
        k
    }

    /// The chord name in Rex's notation.
    pub fn chord(&self) -> String {
        let mut s = String::new();
        for (on, name) in [(self.ctrl, "ctrl+"), (self.alt, "alt+"), (self.shift, "shift+"), (self.sup, "super+")] {
            if on {
                s.push_str(name);
            }
        }
        s.push_str(&self.key);
        s
    }

    fn mods(&self) -> String {
        let mut m = Vec::new();
        for (on, name) in [(self.shift, "shift"), (self.ctrl, "ctrl"), (self.alt, "alt"), (self.sup, "super")] {
            if on {
                m.push(name);
            }
        }
        m.join("+")
    }

    /// The `key` object of a client.key request.
    pub fn to_rex(&self) -> Json {
        let text = self.text.clone().unwrap_or_default();
        let unshifted = typed(&self.key, false).and_then(|t| t.chars().next()).map(|c| c as i64).unwrap_or(0);
        json!({
            "key": code(&self.key),
            "mods": self.mods(),
            "consumed_mods": if self.shift && !text.is_empty() { "shift" } else { "" },
            "text": text,
            "unshifted_codepoint": unshifted,
            "composing": false,
            "action": "press",
        })
    }
}

/// The text a key types on a US layout.
pub fn typed(key: &str, shift: bool) -> Option<String> {
    let c = match key {
        "space" => return Some(" ".into()),
        "enter" => return Some("\r".into()),
        "tab" => return Some("\t".into()),
        k if k.chars().count() == 1 => k.chars().next().unwrap(),
        _ => return None,
    };
    if !shift {
        return Some(c.to_string());
    }
    let shifted = match c {
        'a'..='z' => c.to_ascii_uppercase(),
        '1' => '!', '2' => '@', '3' => '#', '4' => '$', '5' => '%', '6' => '^', '7' => '&', '8' => '*', '9' => '(', '0' => ')',
        '-' => '_', '=' => '+', '[' => '{', ']' => '}', '\\' => '|', ';' => ':', '\'' => '"', ',' => '<', '.' => '>', '/' => '?', '`' => '~',
        other => other,
    };
    Some(shifted.to_string())
}

/// The W3C KeyboardEvent code of KEY on a US layout, in snake case.
pub fn code(key: &str) -> String {
    let named = match key {
        "enter" | "return" => "enter",
        "escape" | "esc" => "escape",
        "backspace" => "backspace",
        "tab" => "tab",
        "space" | " " => "space",
        "up" => "arrow_up",
        "down" => "arrow_down",
        "left" => "arrow_left",
        "right" => "arrow_right",
        "home" => "home",
        "end" => "end",
        "pageup" => "page_up",
        "pagedown" => "page_down",
        "delete" => "delete",
        "insert" => "insert",
        "-" => "minus",
        "=" => "equal",
        "[" => "bracket_left",
        "]" => "bracket_right",
        "\\" => "backslash",
        ";" => "semicolon",
        "'" => "quote",
        "," => "comma",
        "." => "period",
        "/" => "slash",
        "`" => "backquote",
        _ => "",
    };
    if !named.is_empty() {
        return named.to_string();
    }
    let mut chars = key.chars();
    match (chars.next(), chars.next()) {
        (Some(c @ 'a'..='z'), None) => format!("key_{c}"),
        (Some(c @ '0'..='9'), None) => format!("digit_{c}"),
        _ if key.starts_with('f') && key[1..].parse::<u8>().is_ok() => key.to_string(),
        _ => "unidentified".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chords_round_trip() {
        for c in ["ctrl+b", "alt+x", "shift+n", "enter", "ctrl+shift+tab", "n"] {
            assert_eq!(KeyPress::parse(c).chord(), c);
        }
    }

    #[test]
    fn client_key_uses_physical_codes() {
        let k = KeyPress::parse("shift+n").to_rex();
        assert_eq!(k["key"], "key_n");
        assert_eq!(k["mods"], "shift");
        assert_eq!(k["text"], "N");
        assert_eq!(k["unshifted_codepoint"], 110);
        assert_eq!(KeyPress::parse("ctrl+c").to_rex()["text"], "");
        assert_eq!(code("up"), "arrow_up");
        assert_eq!(code("7"), "digit_7");
    }
}
