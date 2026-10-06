//! Map EDN style props onto GPUI's `Styled` builder.
//!
//! Lengths are logical pixels multiplied by the surface scale; `"50%"` and
//! `:full` are relative lengths and `:auto` leaves sizing to layout. A float
//! `:line-height` is a factor of the font size; an integer is pixels.

use gpui::{
    AbsoluteLength, BoxShadow, DefiniteLength, FontWeight, Hsla, Length, Styled, point, px,
    relative, rgba,
};

use crate::edn::Value;
use crate::fonts;

pub fn color(v: &Value) -> Option<Hsla> {
    match v {
        Value::Keyword(k) if k == "transparent" => Some(gpui::transparent_black()),
        Value::Str(s) | Value::Keyword(s) => parse_hex(s),
        Value::Vector(items) if items.len() >= 3 => {
            let c = |i: usize| items.get(i).and_then(Value::as_f64).unwrap_or(1.0) as f32;
            Some(
                gpui::Rgba {
                    r: c(0),
                    g: c(1),
                    b: c(2),
                    a: c(3),
                }
                .into(),
            )
        }
        _ => None,
    }
}

fn parse_hex(s: &str) -> Option<Hsla> {
    let hex = s.strip_prefix('#')?;
    let expand = |h: &str| h.chars().flat_map(|c| [c, c]).collect::<String>();
    let full = match hex.len() {
        3 | 4 => expand(hex),
        6 | 8 => hex.to_string(),
        _ => return None,
    };
    let n = u32::from_str_radix(&full, 16).ok()?;
    let rgba_value = if full.len() == 6 { (n << 8) | 0xff } else { n };
    Some(rgba(rgba_value).into())
}

/// A length prop: number => px * scale, "N%" => relative, :full => 100%, :auto.
pub fn length(v: &Value, scale: f32) -> Option<Length> {
    match v {
        Value::Int(_) | Value::Float(_) => Some(px(v.as_f64()? as f32 * scale).into()),
        Value::Keyword(k) if k == "full" => Some(relative(1.).into()),
        Value::Keyword(k) if k == "auto" => Some(Length::Auto),
        Value::Str(s) if s.ends_with('%') => {
            let pct: f32 = s.trim_end_matches('%').trim().parse().ok()?;
            Some(relative(pct / 100.).into())
        }
        _ => None,
    }
}

fn definite(v: &Value, scale: f32) -> Option<DefiniteLength> {
    match length(v, scale)? {
        Length::Definite(d) => Some(d),
        Length::Auto => None,
    }
}

pub fn pixels(v: &Value, scale: f32) -> Option<f32> {
    v.as_f64().map(|f| f as f32 * scale)
}

/// CSS-like edge shorthand: n | [v h] | [t r b l].
pub fn edges(v: &Value, scale: f32) -> Option<[Value; 4]> {
    let _ = scale;
    match v {
        Value::Int(_) | Value::Float(_) | Value::Keyword(_) | Value::Str(_) => {
            Some([v.clone(), v.clone(), v.clone(), v.clone()])
        }
        Value::Vector(items) => match items.as_slice() {
            [a] => Some([a.clone(), a.clone(), a.clone(), a.clone()]),
            [vert, hor] => Some([vert.clone(), hor.clone(), vert.clone(), hor.clone()]),
            [t, h, b] => Some([t.clone(), h.clone(), b.clone(), h.clone()]),
            [t, r, b, l, ..] => Some([t.clone(), r.clone(), b.clone(), l.clone()]),
            [] => None,
        },
        _ => None,
    }
}

fn font_weight(v: &Value) -> Option<FontWeight> {
    match v {
        Value::Int(_) | Value::Float(_) => Some(FontWeight(v.as_f64()? as f32)),
        Value::Keyword(k) => Some(match k.as_str() {
            "thin" => FontWeight::THIN,
            "light" => FontWeight::LIGHT,
            "normal" => FontWeight::NORMAL,
            "medium" => FontWeight::MEDIUM,
            "semibold" => FontWeight::SEMIBOLD,
            "bold" => FontWeight::BOLD,
            "extrabold" => FontWeight::EXTRA_BOLD,
            "black" => FontWeight::BLACK,
            _ => return None,
        }),
        _ => None,
    }
}

fn shadow(v: &Value, scale: f32) -> Option<Vec<BoxShadow>> {
    let preset = |blur: f32, y: f32, alpha: f32| {
        vec![BoxShadow {
            color: gpui::hsla(0., 0., 0., alpha),
            offset: point(px(0.), px(y * scale)),
            blur_radius: px(blur * scale),
            spread_radius: px(0.),
        }]
    };
    match v {
        Value::Bool(true) => Some(preset(12., 3., 0.35)),
        Value::Bool(false) | Value::Nil => Some(vec![]),
        Value::Keyword(k) => Some(match k.as_str() {
            "sm" => preset(4., 1., 0.3),
            "md" => preset(10., 3., 0.35),
            "lg" => preset(18., 6., 0.4),
            "xl" => preset(28., 10., 0.45),
            _ => vec![],
        }),
        Value::Map(_) => {
            let offset = v.get("offset").map(Value::items).unwrap_or(&[]);
            let off = |i: usize| offset.get(i).and_then(|o| pixels(o, scale)).unwrap_or(0.);
            Some(vec![BoxShadow {
                color: v
                    .get("color")
                    .and_then(color)
                    .unwrap_or(gpui::hsla(0., 0., 0., 0.35)),
                offset: point(px(off(0)), px(off(1))),
                blur_radius: px(v.get("blur").and_then(|b| pixels(b, scale)).unwrap_or(8.)),
                spread_radius: px(v.get("spread").and_then(|b| pixels(b, scale)).unwrap_or(0.)),
            }])
        }
        _ => None,
    }
}

/// Layer-shell margins position the window, not its root element. Other
/// window-only props are ignored by `apply`; margin is the overlapping key.
pub fn apply_layer<T: Styled>(el: T, props: &Value, scale: f32) -> T {
    let content = Value::Map(
        props
            .entries()
            .iter()
            .filter(|(key, _)| key.as_name() != Some("margin"))
            .cloned()
            .collect(),
    );
    apply(el, &content, scale)
}

pub fn apply<T: Styled>(mut el: T, props: &Value, scale: f32) -> T {
    for (key, v) in props.entries() {
        let Value::Keyword(key) = key else { continue };
        el = match key.as_str() {
            "width" => match length(v, scale) {
                Some(l) => el.w(l),
                None => el,
            },
            "height" => match length(v, scale) {
                Some(l) => el.h(l),
                None => el,
            },
            "size" => match length(v, scale) {
                Some(l) => el.size(l),
                None => el,
            },
            "min-width" => match length(v, scale) {
                Some(l) => el.min_w(l),
                None => el,
            },
            "min-height" => match length(v, scale) {
                Some(l) => el.min_h(l),
                None => el,
            },
            "max-width" => match length(v, scale) {
                Some(l) => el.max_w(l),
                None => el,
            },
            "max-height" => match length(v, scale) {
                Some(l) => el.max_h(l),
                None => el,
            },
            "padding" => match edges(v, scale) {
                Some([t, r, b, l]) => {
                    let s = el.style();
                    s.padding.top = definite(&t, scale);
                    s.padding.right = definite(&r, scale);
                    s.padding.bottom = definite(&b, scale);
                    s.padding.left = definite(&l, scale);
                    el
                }
                None => el,
            },
            "margin" => match edges(v, scale) {
                Some([t, r, b, l]) => {
                    let s = el.style();
                    s.margin.top = length(&t, scale);
                    s.margin.right = length(&r, scale);
                    s.margin.bottom = length(&b, scale);
                    s.margin.left = length(&l, scale);
                    el
                }
                None => el,
            },
            "gap" => match definite(v, scale) {
                Some(g) => el.gap(g),
                None => el,
            },
            "grow" => match v {
                Value::Bool(true) => el.flex_grow(),
                Value::Int(_) | Value::Float(_) => {
                    el.style().flex_grow = v.as_f64().map(|f| f as f32);
                    el
                }
                _ => el,
            },
            "shrink" => {
                el.style().flex_shrink = Some(match v {
                    Value::Bool(b) => *b as u8 as f32,
                    other => other.as_f64().unwrap_or(1.) as f32,
                });
                el
            }
            "align" => match v.as_name() {
                Some("start") => el.items_start(),
                Some("center") => el.items_center(),
                Some("end") => el.items_end(),
                Some("baseline") => el.items_baseline(),
                Some("stretch") => {
                    el.style().align_items = Some(gpui::AlignItems::Stretch);
                    el
                }
                _ => el,
            },
            "justify" => match v.as_name() {
                Some("start") => el.justify_start(),
                Some("center") => el.justify_center(),
                Some("end") => el.justify_end(),
                Some("between") => el.justify_between(),
                Some("around") => el.justify_around(),
                _ => el,
            },
            "wrap" if v.truthy() => el.flex_wrap(),
            "bg" | "background" => match color(v) {
                Some(c) => el.bg(c),
                None => el,
            },
            "color" => match color(v) {
                Some(c) => el.text_color(c),
                None => el,
            },
            "opacity" => match v.as_f64() {
                Some(o) => el.opacity(o as f32),
                None => el,
            },
            "radius" => match v {
                Value::Keyword(k) if k == "full" => el.rounded_full(),
                _ => match pixels(v, scale) {
                    Some(r) => el.rounded(px(r)),
                    None => el,
                },
            },
            "border" => match edges(v, scale) {
                Some([t, r, b, l]) => {
                    let w = |e: &Value| pixels(e, scale).map(|p| AbsoluteLength::Pixels(px(p)));
                    let s = el.style();
                    s.border_widths.top = w(&t);
                    s.border_widths.right = w(&r);
                    s.border_widths.bottom = w(&b);
                    s.border_widths.left = w(&l);
                    el
                }
                None => el,
            },
            "border-color" => match color(v) {
                Some(c) => el.border_color(c),
                None => el,
            },
            "shadow" => match shadow(v, scale) {
                Some(s) => el.shadow(s),
                None => el,
            },
            "font-size" => match pixels(v, scale) {
                Some(p) => el.text_size(px(p)),
                None => el,
            },
            "font-weight" => match font_weight(v) {
                Some(w) => el.font_weight(w),
                None => el,
            },
            "font-family" => match v.as_name() {
                Some(f) => el.font_family(fonts::resolve_family(f)),
                None => el,
            },
            "line-height" => match v {
                Value::Float(f) => el.line_height(relative(*f as f32)),
                _ => match definite(v, scale) {
                    Some(l) => el.line_height(l),
                    None => el,
                },
            },
            "text-align" => match v.as_name() {
                Some("center") => el.text_center(),
                Some("right" | "end") => el.text_right(),
                Some("left" | "start") => el.text_left(),
                _ => el,
            },
            "italic" if v.truthy() => el.italic(),
            "truncate" if v.truthy() => el.truncate(),
            "line-clamp" => match v.as_i64() {
                Some(n) if n > 0 => el.line_clamp(n as usize).overflow_hidden(),
                _ => el,
            },
            "nowrap" if v.truthy() => el.whitespace_nowrap(),
            "overflow" => match v.as_name() {
                Some("hidden") => el.overflow_hidden(),
                _ => el,
            },
            "position" => match v.as_name() {
                Some("absolute") => el.absolute(),
                Some("relative") => el.relative(),
                _ => el,
            },
            "top" => match length(v, scale) {
                Some(l) => el.top(l),
                None => el,
            },
            "right" => match length(v, scale) {
                Some(l) => el.right(l),
                None => el,
            },
            "bottom" => match length(v, scale) {
                Some(l) => el.bottom(l),
                None => el,
            },
            "left" => match length(v, scale) {
                Some(l) => el.left(l),
                None => el,
            },
            "cursor" => match v.as_name() {
                Some("pointer") => el.cursor_pointer(),
                Some("text") => el.cursor_text(),
                Some("default") => el.cursor_default(),
                _ => el,
            },
            _ => el,
        };
    }
    el
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layer_margin_only_positions_the_window() {
        let props = Value::map([("margin", Value::Int(16)), ("padding", Value::Int(8))]);
        let mut root = apply_layer(gpui::div(), &props, 1.5);
        assert_eq!(root.style().margin.top, None);
        assert_eq!(root.style().margin.right, None);
        assert_eq!(root.style().padding.top, Some(px(12.).into()));
        let mut child = apply(gpui::div(), &props, 1.5);
        assert_eq!(child.style().margin.top, Some(px(24.).into()));
    }
}
