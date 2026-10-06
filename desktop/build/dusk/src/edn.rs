use std::fmt::{self, Write as _};

#[derive(Clone, Debug, PartialEq)]
pub enum Value {
    Nil,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(String),
    Char(char),
    Keyword(String),
    Symbol(String),
    List(Vec<Value>),
    Vector(Vec<Value>),
    Set(Vec<Value>),
    Map(Vec<(Value, Value)>),
    Tagged(String, Box<Value>),
}

impl Value {
    pub fn kw(name: &str) -> Value {
        Value::Keyword(name.to_string())
    }

    pub fn str(s: impl Into<String>) -> Value {
        Value::Str(s.into())
    }

    pub fn map<I, K>(pairs: I) -> Value
    where
        I: IntoIterator<Item = (K, Value)>,
        K: AsRef<str>,
    {
        Value::Map(
            pairs
                .into_iter()
                .map(|(k, v)| (Value::kw(k.as_ref()), v))
                .collect(),
        )
    }

    pub fn get(&self, key: &str) -> Option<&Value> {
        match self {
            Value::Map(entries) => entries.iter().find_map(|(k, v)| match k {
                Value::Keyword(name) if name == key => Some(v),
                _ => None,
            }),
            _ => None,
        }
    }

    pub fn is_nil(&self) -> bool {
        matches!(self, Value::Nil)
    }

    pub fn truthy(&self) -> bool {
        !matches!(self, Value::Nil | Value::Bool(false))
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Value::Str(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_name(&self) -> Option<&str> {
        match self {
            Value::Str(s) | Value::Keyword(s) | Value::Symbol(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_f64(&self) -> Option<f64> {
        match self {
            Value::Int(i) => Some(*i as f64),
            Value::Float(f) => Some(*f),
            _ => None,
        }
    }

    pub fn as_i64(&self) -> Option<i64> {
        match self {
            Value::Int(i) => Some(*i),
            Value::Float(f) => Some(*f as i64),
            _ => None,
        }
    }

    pub fn items(&self) -> &[Value] {
        match self {
            Value::Vector(v) | Value::List(v) | Value::Set(v) => v,
            _ => &[],
        }
    }

    pub fn entries(&self) -> &[(Value, Value)] {
        match self {
            Value::Map(m) => m,
            _ => &[],
        }
    }
}

impl From<&str> for Value {
    fn from(s: &str) -> Self {
        Value::Str(s.to_string())
    }
}
impl From<String> for Value {
    fn from(s: String) -> Self {
        Value::Str(s)
    }
}
impl From<bool> for Value {
    fn from(b: bool) -> Self {
        Value::Bool(b)
    }
}
impl From<i64> for Value {
    fn from(i: i64) -> Self {
        Value::Int(i)
    }
}
impl From<u32> for Value {
    fn from(i: u32) -> Self {
        Value::Int(i as i64)
    }
}
impl From<f64> for Value {
    fn from(f: f64) -> Self {
        Value::Float(f)
    }
}
impl<T: Into<Value>> From<Option<T>> for Value {
    fn from(o: Option<T>) -> Self {
        o.map(Into::into).unwrap_or(Value::Nil)
    }
}
impl<T: Into<Value>> From<Vec<T>> for Value {
    fn from(v: Vec<T>) -> Self {
        Value::Vector(v.into_iter().map(Into::into).collect())
    }
}

fn write_str_escaped(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

fn write_seq(out: &mut String, open: &str, close: &str, items: &[Value]) {
    out.push_str(open);
    for (i, item) in items.iter().enumerate() {
        if i > 0 {
            out.push(' ');
        }
        write_value(out, item);
    }
    out.push_str(close);
}

fn write_value(out: &mut String, v: &Value) {
    match v {
        Value::Nil => out.push_str("nil"),
        Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Value::Int(i) => {
            let _ = write!(out, "{i}");
        }
        Value::Float(f) => {
            if f.is_finite() {
                let s = format!("{f:?}");
                out.push_str(&s);
            } else if f.is_nan() {
                out.push_str("##NaN");
            } else if *f > 0.0 {
                out.push_str("##Inf");
            } else {
                out.push_str("##-Inf");
            }
        }
        Value::Str(s) => write_str_escaped(out, s),
        Value::Char(c) => match c {
            '\n' => out.push_str("\\newline"),
            ' ' => out.push_str("\\space"),
            '\t' => out.push_str("\\tab"),
            '\r' => out.push_str("\\return"),
            c => {
                out.push('\\');
                out.push(*c);
            }
        },
        Value::Keyword(k) => {
            out.push(':');
            out.push_str(k);
        }
        Value::Symbol(s) => out.push_str(s),
        Value::List(items) => write_seq(out, "(", ")", items),
        Value::Vector(items) => write_seq(out, "[", "]", items),
        Value::Set(items) => write_seq(out, "#{", "}", items),
        Value::Map(entries) => {
            out.push('{');
            for (i, (k, v)) in entries.iter().enumerate() {
                if i > 0 {
                    out.push_str(", ");
                }
                write_value(out, k);
                out.push(' ');
                write_value(out, v);
            }
            out.push('}');
        }
        Value::Tagged(tag, v) => {
            out.push('#');
            out.push_str(tag);
            out.push(' ');
            write_value(out, v);
        }
    }
}

impl fmt::Display for Value {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut s = String::new();
        write_value(&mut s, self);
        f.write_str(&s)
    }
}

#[derive(Debug)]
pub struct ParseError {
    pub pos: usize,
    pub msg: String,
}

impl fmt::Display for ParseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "EDN parse error at {}: {}", self.pos, self.msg)
    }
}

impl std::error::Error for ParseError {}

struct Reader<'a> {
    src: &'a [u8],
    text: &'a str,
    pos: usize,
}

fn is_delimiter(b: u8) -> bool {
    b.is_ascii_whitespace()
        || matches!(
            b,
            b',' | b'(' | b')' | b'[' | b']' | b'{' | b'}' | b'"' | b';'
        )
}

impl<'a> Reader<'a> {
    fn err<T>(&self, msg: impl Into<String>) -> Result<T, ParseError> {
        Err(ParseError {
            pos: self.pos,
            msg: msg.into(),
        })
    }

    fn peek(&self) -> Option<u8> {
        self.src.get(self.pos).copied()
    }

    fn skip_ws(&mut self) -> Result<(), ParseError> {
        loop {
            match self.peek() {
                Some(b) if b.is_ascii_whitespace() || b == b',' => self.pos += 1,
                Some(b';') => {
                    while let Some(b) = self.peek() {
                        self.pos += 1;
                        if b == b'\n' {
                            break;
                        }
                    }
                }
                Some(b'#') if self.src.get(self.pos + 1) == Some(&b'_') => {
                    self.pos += 2;
                    self.read()?;
                }
                _ => return Ok(()),
            }
        }
    }

    fn token(&mut self) -> &'a str {
        let start = self.pos;
        while let Some(b) = self.peek() {
            if is_delimiter(b) {
                break;
            }
            self.pos += 1;
        }
        &self.text[start..self.pos]
    }

    fn read_seq(&mut self, close: u8) -> Result<Vec<Value>, ParseError> {
        let mut items = Vec::new();
        loop {
            self.skip_ws()?;
            match self.peek() {
                None => return self.err("unexpected end of input in collection"),
                Some(b) if b == close => {
                    self.pos += 1;
                    return Ok(items);
                }
                Some(_) => items.push(self.read()?),
            }
        }
    }

    fn read_string(&mut self) -> Result<Value, ParseError> {
        self.pos += 1;
        let mut out = String::new();
        loop {
            let Some(c) = self.text[self.pos..].chars().next() else {
                return self.err("unterminated string");
            };
            self.pos += c.len_utf8();
            match c {
                '"' => return Ok(Value::Str(out)),
                '\\' => {
                    let Some(e) = self.text[self.pos..].chars().next() else {
                        return self.err("unterminated escape");
                    };
                    self.pos += e.len_utf8();
                    match e {
                        'n' => out.push('\n'),
                        't' => out.push('\t'),
                        'r' => out.push('\r'),
                        'b' => out.push('\u{8}'),
                        'f' => out.push('\u{c}'),
                        '0' => out.push('\0'),
                        '"' => out.push('"'),
                        '\\' => out.push('\\'),
                        'u' => {
                            let hex = self.text.get(self.pos..self.pos + 4).unwrap_or("");
                            let code = u32::from_str_radix(hex, 16).map_err(|_| ParseError {
                                pos: self.pos,
                                msg: "bad \\u escape".into(),
                            })?;
                            self.pos += 4;
                            out.push(char::from_u32(code).unwrap_or('\u{fffd}'));
                        }
                        other => out.push(other),
                    }
                }
                c => out.push(c),
            }
        }
    }

    fn read_char(&mut self) -> Result<Value, ParseError> {
        self.pos += 1;
        let Some(first) = self.text[self.pos..].chars().next() else {
            return self.err("unterminated character");
        };
        self.pos += first.len_utf8();
        let rest_start = self.pos;
        while let Some(b) = self.peek() {
            if is_delimiter(b) {
                break;
            }
            self.pos += 1;
        }
        let name = format!("{first}{}", &self.text[rest_start..self.pos]);
        let c = match name.as_str() {
            "newline" => '\n',
            "space" => ' ',
            "tab" => '\t',
            "return" => '\r',
            "backspace" => '\u{8}',
            "formfeed" => '\u{c}',
            n if n.starts_with('u') && n.len() == 5 => {
                char::from_u32(u32::from_str_radix(&n[1..], 16).unwrap_or(0xfffd))
                    .unwrap_or('\u{fffd}')
            }
            _ if name.chars().count() == 1 => first,
            _ => return self.err(format!("unknown character name \\{name}")),
        };
        Ok(Value::Char(c))
    }

    fn read_atom(&mut self) -> Result<Value, ParseError> {
        let start = self.pos;
        let tok = self.token();
        if tok.is_empty() {
            self.pos = start;
            return self.err(format!(
                "unexpected character {:?}",
                self.peek().map(|b| b as char)
            ));
        }
        match tok {
            "nil" => return Ok(Value::Nil),
            "true" => return Ok(Value::Bool(true)),
            "false" => return Ok(Value::Bool(false)),
            _ => {}
        }
        if let Some(k) = tok.strip_prefix(':') {
            return Ok(Value::Keyword(k.to_string()));
        }
        let first = tok.as_bytes()[0];
        let numeric = first.is_ascii_digit()
            || ((first == b'-' || first == b'+')
                && tok.len() > 1
                && tok.as_bytes()[1].is_ascii_digit());
        if numeric {
            let t = tok.trim_end_matches(['N', 'M']);
            if let Ok(i) = t.parse::<i64>() {
                return Ok(Value::Int(i));
            }
            if let Ok(f) = t.parse::<f64>() {
                return Ok(Value::Float(f));
            }
            if let Some((n, d)) = t.split_once('/')
                && let (Ok(n), Ok(d)) = (n.parse::<f64>(), d.parse::<f64>())
            {
                return Ok(Value::Float(n / d));
            }
            self.pos = start;
            return self.err(format!("invalid number {tok:?}"));
        }
        Ok(Value::Symbol(tok.to_string()))
    }

    fn read(&mut self) -> Result<Value, ParseError> {
        self.skip_ws()?;
        match self.peek() {
            None => self.err("unexpected end of input"),
            Some(b'(') => {
                self.pos += 1;
                Ok(Value::List(self.read_seq(b')')?))
            }
            Some(b'[') => {
                self.pos += 1;
                Ok(Value::Vector(self.read_seq(b']')?))
            }
            Some(b'{') => {
                self.pos += 1;
                let items = self.read_seq(b'}')?;
                if items.len() % 2 != 0 {
                    return self.err("map with odd number of forms");
                }
                let mut entries = Vec::with_capacity(items.len() / 2);
                let mut it = items.into_iter();
                while let (Some(k), Some(v)) = (it.next(), it.next()) {
                    entries.push((k, v));
                }
                Ok(Value::Map(entries))
            }
            Some(b'"') => self.read_string(),
            Some(b'\\') => self.read_char(),
            Some(b'#') => {
                self.pos += 1;
                match self.peek() {
                    Some(b'{') => {
                        self.pos += 1;
                        Ok(Value::Set(self.read_seq(b'}')?))
                    }
                    Some(b'#') => {
                        self.pos += 1;
                        match self.token() {
                            "Inf" => Ok(Value::Float(f64::INFINITY)),
                            "-Inf" => Ok(Value::Float(f64::NEG_INFINITY)),
                            "NaN" => Ok(Value::Float(f64::NAN)),
                            t => self.err(format!("unknown symbolic value ##{t}")),
                        }
                    }
                    _ => {
                        let tag = self.token().to_string();
                        if tag.is_empty() {
                            return self.err("invalid dispatch");
                        }
                        let v = self.read()?;
                        Ok(Value::Tagged(tag, Box::new(v)))
                    }
                }
            }
            Some(b')' | b']' | b'}') => self.err("unbalanced closing delimiter"),
            Some(_) => self.read_atom(),
        }
    }
}

pub fn parse(text: &str) -> Result<Value, ParseError> {
    let mut r = Reader {
        src: text.as_bytes(),
        text,
        pos: 0,
    };
    let v = r.read()?;
    r.skip_ws()?;
    if r.pos != text.len() {
        return r.err("trailing characters after value");
    }
    Ok(v)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let src = r#"{:op :render, :surfaces {"osd" {:type :layer, :props {:anchor #{:top :right}, :width 12.5}, :children [nil true -3 "a\"b\n" \x sym/ns]}}}"#;
        let v = parse(src).unwrap();
        assert_eq!(v.get("op"), Some(&Value::kw("render")));
        let printed = v.to_string();
        assert_eq!(parse(&printed).unwrap(), v);
    }

    #[test]
    fn comments_discard_and_tags() {
        let v = parse("[1 ; comment\n #_ 2 3 #inst \"2020\" ##Inf 1/2]").unwrap();
        assert_eq!(v.items().len(), 5);
        assert_eq!(v.items()[0], Value::Int(1));
        assert_eq!(v.items()[1], Value::Int(3));
        assert!(matches!(v.items()[2], Value::Tagged(ref t, _) if t == "inst"));
        assert_eq!(v.items()[3], Value::Float(f64::INFINITY));
        assert_eq!(v.items()[4], Value::Float(0.5));
    }

    #[test]
    fn unicode() {
        let v = parse(r#""🔊 caf\u00e9 😀""#).unwrap();
        assert_eq!(v.as_str(), Some("🔊 café 😀"));
        assert_eq!(parse(&v.to_string()).unwrap(), v);
    }

    #[test]
    fn chars() {
        assert_eq!(parse(r"\newline").unwrap(), Value::Char('\n'));
        assert!(parse(r"\abc").is_err());
        assert_eq!(
            parse(r"[\a \b]").unwrap(),
            Value::Vector(vec![Value::Char('a'), Value::Char('b')])
        );
    }
}
