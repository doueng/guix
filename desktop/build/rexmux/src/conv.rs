//! EDN (sidecar) and JSON (Rex) conversions. Keywords become strings, so an
//! effect's `{:session_id "s"}` is Rex's `{"session_id": "s"}`.

use crate::edn::Value;
use serde_json::{Map, Value as Json};

pub fn to_json(v: &Value) -> Json {
    match v {
        Value::Nil => Json::Null,
        Value::Bool(b) => Json::Bool(*b),
        Value::Int(i) => Json::from(*i),
        Value::Float(f) => serde_json::Number::from_f64(*f).map(Json::Number).unwrap_or(Json::Null),
        Value::Str(s) => Json::String(s.clone()),
        Value::Char(c) => Json::String(c.to_string()),
        Value::Keyword(k) | Value::Symbol(k) => Json::String(k.clone()),
        Value::List(xs) | Value::Vector(xs) | Value::Set(xs) => Json::Array(xs.iter().map(to_json).collect()),
        Value::Map(kvs) => {
            let mut m = Map::new();
            for (k, v) in kvs {
                let key = match k {
                    Value::Keyword(s) | Value::Symbol(s) | Value::Str(s) => s.clone(),
                    other => other.to_string(),
                };
                m.insert(key, to_json(v));
            }
            Json::Object(m)
        }
        Value::Tagged(_, inner) => to_json(inner),
    }
}

pub fn to_edn(j: &Json) -> Value {
    match j {
        Json::Null => Value::Nil,
        Json::Bool(b) => Value::Bool(*b),
        Json::Number(n) => n.as_i64().map(Value::Int).unwrap_or_else(|| Value::Float(n.as_f64().unwrap_or(0.0))),
        Json::String(s) => Value::Str(s.clone()),
        Json::Array(xs) => Value::Vector(xs.iter().map(to_edn).collect()),
        Json::Object(m) => Value::Map(m.iter().map(|(k, v)| (Value::kw(k), to_edn(v))).collect()),
    }
}

/// A map's entry by keyword or string key.
pub fn get<'a>(v: &'a Value, key: &str) -> Option<&'a Value> {
    v.get(key)
}

pub fn str_of(v: Option<&Value>) -> Option<String> {
    v.and_then(|x| x.as_str().or_else(|| x.as_name())).map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_through_json() {
        let v = crate::edn::parse("{:a 1 :b [\"x\" true nil] :c {:d 2.5}}").unwrap();
        let back = to_edn(&to_json(&v));
        assert_eq!(to_json(&v), to_json(&back));
        assert_eq!(to_json(&v)["b"][0], "x");
    }
}
