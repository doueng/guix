use std::collections::HashMap;
use std::path::PathBuf;

use gpui::{
    AnyElement, AppContext, Bounds, Context, Div, Entity, FocusHandle, InteractiveElement,
    IntoElement, KeyDownEvent, ObjectFit, ParentElement, Pixels, Render, SharedString,
    StatefulInteractiveElement, Styled, StyledImage, Task, Window, div, img, layer_shell::*, px,
    relative, size, svg,
};

use crate::edn::Value;
use crate::fonts;
use crate::input::TextInput;
use crate::runtime::RuntimeLink;
use crate::style;

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Dim {
    Fixed(f32),
    Stretch,
    Auto,
}

/// Everything that requires re-creating the layer surface when it changes.
#[derive(Clone, Debug, PartialEq)]
pub struct WindowKey {
    pub layer: Layer,
    pub anchor: Anchor,
    pub margin: [i32; 4],
    pub keyboard: KeyboardInteractivity,
    pub namespace: String,
    pub exclusive_zone: Option<i32>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SurfaceSpec {
    pub key: WindowKey,
    pub width: Dim,
    pub height: Dim,
    pub scale: f32,
}

impl SurfaceSpec {
    pub fn from_tree(id: &str, tree: &Value) -> SurfaceSpec {
        let props = tree.get("props").unwrap_or(&Value::Nil);
        let scale = props.get("scale").and_then(Value::as_f64).unwrap_or(1.0) as f32;
        let layer = match props.get("layer").and_then(Value::as_name) {
            Some("background") => Layer::Background,
            Some("bottom") => Layer::Bottom,
            Some("top") => Layer::Top,
            _ => Layer::Overlay,
        };
        let mut anchor = Anchor::empty();
        for edge in props.get("anchor").map(Value::items).unwrap_or(&[]) {
            anchor |= match edge.as_name() {
                Some("top") => Anchor::TOP,
                Some("bottom") => Anchor::BOTTOM,
                Some("left") => Anchor::LEFT,
                Some("right") => Anchor::RIGHT,
                _ => Anchor::empty(),
            };
        }
        let mut margin = [0; 4];
        if let Some([t, r, b, l]) = props.get("margin").and_then(|m| style::edges(m, scale)) {
            for (slot, v) in margin.iter_mut().zip([t, r, b, l]) {
                *slot = style::pixels(&v, scale).unwrap_or(0.).round() as i32;
            }
        }
        let keyboard = match props.get("keyboard").and_then(Value::as_name) {
            Some("exclusive") => KeyboardInteractivity::Exclusive,
            Some("on-demand") => KeyboardInteractivity::OnDemand,
            _ => KeyboardInteractivity::None,
        };
        let namespace = props
            .get("namespace")
            .and_then(Value::as_name)
            .map(str::to_string)
            .unwrap_or_else(|| format!("dusk-{id}"));
        let exclusive_zone = props.get("exclusive-zone").and_then(|z| match z {
            Value::Int(i) if *i < 0 => Some(*i as i32),
            other => style::pixels(other, scale).map(|p| p.round() as i32),
        });
        let dim = |key: &str, a: Anchor, b: Anchor| match props.get(key) {
            Some(Value::Keyword(k)) if k == "auto" => Dim::Auto,
            Some(Value::Keyword(k)) if k == "full" => Dim::Stretch,
            Some(v) if v.as_f64().is_some() => Dim::Fixed(v.as_f64().unwrap() as f32 * scale),
            _ if anchor.contains(a | b) => Dim::Stretch,
            _ => Dim::Auto,
        };
        SurfaceSpec {
            width: dim("width", Anchor::LEFT, Anchor::RIGHT),
            height: dim("height", Anchor::TOP, Anchor::BOTTOM),
            key: WindowKey {
                layer,
                anchor,
                margin,
                keyboard,
                namespace,
                exclusive_zone,
            },
            scale,
        }
    }

    pub fn layer_options(&self) -> LayerShellOptions {
        let m = self.key.margin;
        LayerShellOptions {
            namespace: self.key.namespace.clone(),
            layer: self.key.layer,
            anchor: self.key.anchor,
            exclusive_zone: self.key.exclusive_zone.map(|z| px(z as f32)),
            exclusive_edge: None,
            margin: Some((
                px(m[0] as f32),
                px(m[1] as f32),
                px(m[2] as f32),
                px(m[3] as f32),
            )),
            keyboard_interactivity: self.key.keyboard,
        }
    }

    /// Size to request from the compositor. Stretched axes request 0, which
    /// layer-shell defines as "fill the anchored span"; GPUI's Wayland
    /// backend has no primary display to measure it ourselves.
    pub fn window_size(&self, current: Option<gpui::Size<Pixels>>) -> gpui::Size<Pixels> {
        let pick = |dim: Dim, current: Option<Pixels>, guess: f32| match dim {
            Dim::Fixed(v) => px(v),
            Dim::Stretch => px(0.),
            Dim::Auto => current.unwrap_or(px(guess)),
        };
        size(
            pick(self.width, current.map(|c| c.width), 400.),
            pick(self.height, current.map(|c| c.height), 100.),
        )
    }
}

pub struct SurfaceView {
    pub id: String,
    pub spec: SurfaceSpec,
    tree: Value,
    link: RuntimeLink,
    focus: FocusHandle,
    inputs: HashMap<String, Entity<TextInput>>,
    assets: fonts::Assets,
    _assets_task: Option<Task<()>>,
    pub closing: bool,
}

fn props(node: &Value) -> &Value {
    node.get("props").unwrap_or(&Value::Nil)
}

fn node_path(node: &Value) -> String {
    node.get("path")
        .and_then(Value::as_name)
        .unwrap_or("?")
        .to_string()
}

fn handler(node: &Value, name: &str) -> Option<String> {
    props(node)
        .get(name)
        .and_then(Value::as_str)
        .map(str::to_string)
}

fn interactive_div(el: Div, node: &Value, scale: f32) -> gpui::Stateful<Div> {
    let p = props(node);
    // Hover-only elements also need persistent state for invalidation.
    let mut el = el.id(SharedString::from(node_path(node)));
    if let Some(hover) = p
        .get("hover")
        .filter(|h| matches!(h, Value::Map(_)))
        .cloned()
    {
        el = el.hover(move |s| style::apply(s, &hover, scale));
    }
    if p.get("occlude").is_some_and(Value::truthy) {
        el = el.occlude();
    }
    el
}

fn find_inputs<'a>(node: &'a Value, out: &mut Vec<&'a Value>) {
    if node.get("type").and_then(Value::as_name) == Some("input") {
        out.push(node);
    }
    for child in node.get("children").map(Value::items).unwrap_or(&[]) {
        find_inputs(child, out);
    }
}

fn key_event_value(event: &KeyDownEvent) -> Value {
    let ks = &event.keystroke;
    let m = &ks.modifiers;
    Value::map([
        ("key", Value::str(&ks.key)),
        ("char", ks.key_char.clone().into()),
        ("ctrl?", Value::Bool(m.control)),
        ("alt?", Value::Bool(m.alt)),
        ("shift?", Value::Bool(m.shift)),
        ("super?", Value::Bool(m.platform)),
        ("held?", Value::Bool(event.is_held)),
    ])
}

impl SurfaceView {
    pub fn new(
        id: String,
        tree: Value,
        link: RuntimeLink,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let focus = cx.focus_handle();
        let spec = SurfaceSpec::from_tree(&id, &tree);
        if spec.key.keyboard != KeyboardInteractivity::None {
            window.focus(&focus, cx);
        }
        let mut view = SurfaceView {
            id,
            spec,
            tree: Value::Nil,
            link,
            focus,
            inputs: HashMap::new(),
            assets: fonts::Assets::default(),
            _assets_task: None,
            closing: false,
        };
        view.set_tree(tree, window, cx);
        view
    }

    pub fn set_tree(&mut self, tree: Value, window: &mut Window, cx: &mut Context<Self>) {
        self.spec = SurfaceSpec::from_tree(&self.id, &tree);
        let assets = fonts::Assets::for_tree(&tree);
        if assets != self.assets {
            self.assets = assets.clone();
            let prepare = cx.background_spawn(async move { assets.prepare() });
            self._assets_task = Some(cx.spawn(async move |this, cx| {
                prepare.await;
                let _ = this.update(cx, |_, cx| cx.notify());
            }));
        }
        let had_input_focus = self
            .inputs
            .values()
            .any(|input| input.read(cx).focus.is_focused(window));
        let mut inputs = Vec::new();
        find_inputs(&tree, &mut inputs);
        let mut live = HashMap::new();
        let mut first_focus = None;
        for node in inputs {
            let path = node_path(node);
            let p = props(node).clone();
            let scale = self.spec.scale;
            let input = match self.inputs.remove(&path) {
                Some(input) => {
                    input.update(cx, |input, cx| input.set_props(p, scale, cx));
                    input
                }
                None => cx.new(|cx| TextInput::new(p, scale, self.link.clone(), cx)),
            };
            first_focus.get_or_insert_with(|| input.read(cx).focus.clone());
            live.insert(path, input);
        }
        let has_input_focus = live
            .values()
            .any(|input| input.read(cx).focus.is_focused(window));
        if self.spec.key.keyboard != KeyboardInteractivity::None
            && !has_input_focus
            && (self.focus.is_focused(window) || had_input_focus)
        {
            // Auto-focus the first input on mount; preserve the user's focus
            // through updates, and recover it if that input was removed.
            window.focus(first_focus.as_ref().unwrap_or(&self.focus), cx);
        }
        self.inputs = live;
        self.tree = tree;
    }

    fn on_key(&mut self, event: &KeyDownEvent, window: &mut Window, cx: &mut Context<Self>) {
        let key = &event.keystroke;
        if self.inputs.len() > 1
            && key.key == "tab"
            && !(key.modifiers.control || key.modifiers.alt || key.modifiers.platform)
        {
            if key.modifiers.shift {
                window.focus_prev(cx);
            } else {
                window.focus_next(cx);
            }
            cx.stop_propagation();
            return;
        }
        // Only surface-level shortcuts remain raw events. TextInput handles
        // edits via platform input and scoped GPUI actions.
        if let Some(h) = handler(&self.tree, "on-key") {
            self.link.event(&h, vec![key_event_value(event)]);
        }
    }

    fn render_children(&self, node: &Value, out: &mut Vec<AnyElement>) {
        for child in node.get("children").map(Value::items).unwrap_or(&[]) {
            match child {
                Value::Str(s) => out.push(SharedString::from(s.clone()).into_any_element()),
                Value::Map(_) => out.push(self.render_node(child)),
                Value::Nil => {}
                other => out.push(SharedString::from(other.to_string()).into_any_element()),
            }
        }
    }

    fn with_interactions(&self, el: Div, node: &Value) -> AnyElement {
        let p = props(node);
        let mut el = interactive_div(el, node, self.spec.scale);
        let click = handler(node, "on-click");
        let right_click = handler(node, "on-right-click");
        let hover_handler = handler(node, "on-hover");
        if click.is_none() && right_click.is_none() && hover_handler.is_none() {
            return el.into_any_element();
        }
        if let Some(h) = click {
            let link = self.link.clone();
            if p.get("cursor").is_none() {
                el = el.cursor_pointer();
            }
            el = el.on_click(move |_, _, _| link.event(&h, vec![]));
        }
        if let Some(h) = right_click {
            let link = self.link.clone();
            el = el.on_mouse_down(gpui::MouseButton::Right, move |_, _, _| {
                link.event(&h, vec![])
            });
        }
        if let Some(h) = hover_handler {
            let link = self.link.clone();
            el = el.on_hover(move |hovered, _, _| link.event(&h, vec![Value::Bool(*hovered)]));
        }
        el.into_any_element()
    }

    fn render_node(&self, node: &Value) -> AnyElement {
        let p = props(node);
        let scale = self.spec.scale;
        let ty = node.get("type").and_then(Value::as_name).unwrap_or("box");
        match ty {
            "row" | "column" | "box" | "stack" | "button" => {
                let mut el = div().flex();
                el = match ty {
                    "row" => el.flex_row().items_center(),
                    "button" => el.flex_row().items_center().justify_center(),
                    "stack" => el.relative().flex_col(),
                    _ => el.flex_col(),
                };
                el = style::apply(el, p, scale);
                let mut children = Vec::new();
                self.render_children(node, &mut children);
                self.with_interactions(el.children(children), node)
            }
            "text" => {
                let mut text = p.get("text").map(|t| match t {
                    Value::Str(s) => s.clone(),
                    other => other.to_string(),
                });
                if text.is_none() {
                    let parts: Vec<String> = node
                        .get("children")
                        .map(Value::items)
                        .unwrap_or(&[])
                        .iter()
                        .filter(|c| !c.is_nil())
                        .map(|c| match c {
                            Value::Str(s) => s.clone(),
                            other => other.to_string(),
                        })
                        .collect();
                    text = Some(parts.concat());
                }
                let el = style::apply(div(), p, scale)
                    .child(SharedString::from(text.unwrap_or_default()));
                self.with_interactions(el, node)
            }
            "spacer" => style::apply(div().flex_grow(), p, scale).into_any_element(),
            "image" => {
                let Some(src) = p.get("src").and_then(Value::as_str) else {
                    return div().into_any_element();
                };
                let fit = match p.get("fit").and_then(Value::as_name) {
                    Some("contain") => ObjectFit::Contain,
                    Some("fill") => ObjectFit::Fill,
                    Some("none") => ObjectFit::None,
                    Some("scale-down") => ObjectFit::ScaleDown,
                    _ => ObjectFit::Cover,
                };
                let path: PathBuf = fonts::expand_home(src);
                style::apply(img(path).object_fit(fit), p, scale).into_any_element()
            }
            "icon" => {
                let name = p.get("name").and_then(Value::as_name).unwrap_or("");
                let icon_size = p
                    .get("size")
                    .and_then(|s| style::pixels(s, scale))
                    .unwrap_or(16. * scale);
                let Some(path) = fonts::resolve_icon(name) else {
                    // Reserve layout space while background discovery runs.
                    return div().size(px(icon_size)).flex_none().into_any_element();
                };
                let mut el = svg()
                    .external_path(path.display().to_string())
                    .size(px(icon_size))
                    .flex_none();
                if let Some(c) = p.get("color").and_then(style::color) {
                    el = el.text_color(c);
                }
                el.into_any_element()
            }
            "progress" => {
                let value = p
                    .get("value")
                    .and_then(Value::as_f64)
                    .unwrap_or(0.)
                    .clamp(0., 1.) as f32;
                let fill = p
                    .get("fill")
                    .and_then(style::color)
                    .unwrap_or(gpui::white());
                let track = p
                    .get("track")
                    .and_then(style::color)
                    .unwrap_or(gpui::hsla(0., 0., 1., 0.15));
                let height = p
                    .get("height")
                    .and_then(|h| style::pixels(h, scale))
                    .unwrap_or(4. * scale);
                let el = style::apply(
                    div()
                        .h(px(height))
                        .rounded_full()
                        .bg(track)
                        .overflow_hidden(),
                    p,
                    scale,
                )
                .child(div().h_full().w(relative(value)).rounded_full().bg(fill));
                self.with_interactions(el, node)
            }
            "input" => {
                let el = style::apply(
                    // TextInput inherits these styles, including hover and
                    // explicit line-height/font-size refinements.
                    div()
                        .flex()
                        .flex_row()
                        .items_center()
                        .overflow_hidden()
                        .text_size(px(14. * scale))
                        .line_height(relative(1.25)),
                    p,
                    scale,
                );
                self.with_interactions(
                    el.children(self.inputs.get(&node_path(node)).cloned()),
                    node,
                )
            }
            _ => {
                let mut children = Vec::new();
                self.render_children(node, &mut children);
                self.with_interactions(
                    style::apply(div().flex().flex_col(), p, scale).children(children),
                    node,
                )
            }
        }
    }
}

impl Render for SurfaceView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let p = props(&self.tree);
        let scale = self.spec.scale;
        let (auto_w, auto_h) = (self.spec.width == Dim::Auto, self.spec.height == Dim::Auto);
        let mut root = div().flex().flex_col();
        root = if auto_w {
            root.items_start()
        } else {
            root.w_full()
        };
        if !auto_h {
            root = root.h_full();
        }
        if let Some(family) = p.get("font-family").and_then(Value::as_name) {
            root = root.font_family(fonts::resolve_family(family));
        } else {
            root = root.font_family(fonts::resolve_family("sans-serif"));
        }
        root = style::apply_layer(root, p, scale);
        if self.spec.key.keyboard != KeyboardInteractivity::None {
            root =
                root.track_focus(&self.focus).on_key_down(cx.listener(
                    |this, event: &KeyDownEvent, window, cx| this.on_key(event, window, cx),
                ));
        }
        if (auto_w || auto_h) && !self.closing {
            let viewport = window.viewport_size();
            let padding = p.get("padding").and_then(|v| style::edges(v, scale));
            let pad = |i: usize| {
                padding
                    .as_ref()
                    .and_then(|e| style::pixels(&e[i], scale))
                    .unwrap_or(0.)
            };
            let (pad_right, pad_bottom) = (pad(1), pad(2));
            root = root.on_children_prepainted(move |bounds: Vec<Bounds<Pixels>>, window, cx| {
                if bounds.is_empty() {
                    return;
                }
                let right = bounds
                    .iter()
                    .map(|b| b.right())
                    .fold(px(0.), |a, b| a.max(b));
                let bottom = bounds
                    .iter()
                    .map(|b| b.bottom())
                    .fold(px(0.), |a, b| a.max(b));
                let want = size(
                    if auto_w {
                        (right + px(pad_right)).ceil()
                    } else {
                        viewport.width
                    },
                    if auto_h {
                        (bottom + px(pad_bottom)).ceil()
                    } else {
                        viewport.height
                    },
                );
                let delta = (want.width - viewport.width)
                    .abs()
                    .max((want.height - viewport.height).abs());
                if delta > px(0.5) && want.width > px(0.) && want.height > px(0.) {
                    window.defer(cx, move |window, _| window.resize(want));
                }
            });
        }
        let mut children = Vec::new();
        self.render_children(&self.tree, &mut children);
        root.children(children)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hover_only_div_has_a_stable_element_id() {
        let node =
            crate::edn::parse(r##"{:path "hover-only" :props {:hover {:bg "#ff0000"}}}"##).unwrap();
        let el = interactive_div(div(), &node, 1.);
        assert_eq!(
            gpui::Element::id(&el),
            Some(gpui::ElementId::Name("hover-only".into()))
        );
    }

    #[cfg(feature = "ui-tests")]
    #[gpui::test]
    fn hover_without_handlers_invalidates_and_repaints(cx: &mut gpui::TestAppContext) {
        use gpui::point;
        use std::{cell::RefCell, rc::Rc};
        struct Probe {
            colors: Rc<RefCell<Vec<gpui::Hsla>>>,
        }
        impl Render for Probe {
            fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
                let colors = self.colors.clone();
                let node = crate::edn::parse(
                    r##"{:path "hover-only" :props {:hover {:color "#ff0000"}}}"##,
                )
                .unwrap();
                interactive_div(
                    div().size(px(100.)).text_color(gpui::black()).child(
                        div()
                            .size(px(100.))
                            .on_children_prepainted(move |_, window, _| {
                                colors.borrow_mut().push(window.text_style().color);
                            }),
                    ),
                    &node,
                    1.,
                )
            }
        }
        let colors = Rc::new(RefCell::new(Vec::new()));
        let (_, cx) = cx.add_window_view(|_, _| Probe {
            colors: colors.clone(),
        });
        cx.simulate_mouse_move(point(px(200.), px(200.)), None, Default::default());
        assert_eq!(colors.borrow().last(), Some(&gpui::black()));
        cx.simulate_mouse_move(point(px(50.), px(50.)), None, Default::default());
        assert_eq!(colors.borrow().last(), Some(&gpui::red()));
        cx.simulate_mouse_move(point(px(200.), px(200.)), None, Default::default());
        assert_eq!(colors.borrow().last(), Some(&gpui::black()));
    }

    #[cfg(feature = "ui-tests")]
    #[gpui::test]
    fn multiple_inputs_keep_focus_and_route_text_independently(cx: &mut gpui::TestAppContext) {
        cx.update(crate::input::init);
        let tree = crate::edn::parse(
            r#"{:type :layer :path "form"
            :props {:keyboard :exclusive :width 300 :height 200}
            :children [{:type :input :path "first" :props {:height 40 :value ""}}
                       {:type :input :path "second" :props {:height 40 :value ""}}]}"#,
        )
        .unwrap();
        let (surface, cx) = cx.add_window_view(|window, cx| {
            SurfaceView::new(
                "form".into(),
                tree.clone(),
                RuntimeLink::default(),
                window,
                cx,
            )
        });
        cx.simulate_input("one");
        cx.simulate_keystrokes("tab");
        cx.simulate_input("two");
        let (first, second) = surface.read_with(cx, |surface, _| {
            (
                surface.inputs["first"].clone(),
                surface.inputs["second"].clone(),
            )
        });
        assert_eq!(
            first.read_with(cx, |input, _| input.test_text().to_string()),
            "one"
        );
        assert_eq!(
            second.read_with(cx, |input, _| input.test_text().to_string()),
            "two"
        );
        surface.update_in(cx, |surface, window, cx| {
            surface.set_tree(tree.clone(), window, cx)
        });
        cx.simulate_input("!");
        assert_eq!(
            second.read_with(cx, |input, _| input.test_text().to_string()),
            "two!"
        );
        cx.simulate_keystrokes("shift-tab");
        cx.simulate_input("?");
        assert_eq!(
            first.read_with(cx, |input, _| input.test_text().to_string()),
            "one?"
        );
        // Clicking either input routes future text there, not to the first
        // field unconditionally. The click is past the text, so caret = end.
        cx.simulate_click(gpui::point(px(280.), px(60.)), Default::default());
        cx.simulate_input("+");
        assert_eq!(
            second.read_with(cx, |input, _| input.test_text().to_string()),
            "two!+"
        );
        cx.simulate_click(gpui::point(px(280.), px(20.)), Default::default());
        cx.simulate_input("+");
        assert_eq!(
            first.read_with(cx, |input, _| input.test_text().to_string()),
            "one?+"
        );
    }
}
