//! Single-line editing through GPUI's platform input handler, not raw keys.
//! The Element/InputHandler integration follows GPUI's Apache-2.0 input example:
//! https://github.com/zed-industries/zed/blob/7f7520b98ed1a0ce03dab1d5ddcb87163f1bcdb8/crates/gpui/examples/input.rs
//! See licenses/GPUI-Apache-2.0.txt.

use std::ops::Range;

use gpui::{
    App, Bounds, ClipboardItem, Context, ElementId, ElementInputHandler, Entity,
    EntityInputHandler, FocusHandle, Focusable, GlobalElementId, LayoutId, MouseButton,
    MouseDownEvent, PaintQuad, Pixels, Point, Render, ShapedLine, Style, TextRun, UTF16Selection,
    UnderlineStyle, Window, actions, div, fill, point, prelude::*, px, relative, rgba, size,
};
use unicode_segmentation::UnicodeSegmentation;

use crate::{edn::Value, runtime::RuntimeLink, style};

const KEY_CONTEXT: &str = "DuskInput";
actions!(
    dusk_input,
    [
        Backspace,
        Delete,
        Left,
        Right,
        SelectLeft,
        SelectRight,
        SelectAll,
        Home,
        End,
        SelectHome,
        SelectEnd,
        Paste,
        Copy,
        Cut,
        DeleteWord,
        Clear
    ]
);

pub fn init(cx: &mut App) {
    use gpui::KeyBinding;
    cx.bind_keys([
        KeyBinding::new("backspace", Backspace, Some(KEY_CONTEXT)),
        KeyBinding::new("delete", Delete, Some(KEY_CONTEXT)),
        KeyBinding::new("left", Left, Some(KEY_CONTEXT)),
        KeyBinding::new("right", Right, Some(KEY_CONTEXT)),
        KeyBinding::new("shift-left", SelectLeft, Some(KEY_CONTEXT)),
        KeyBinding::new("shift-right", SelectRight, Some(KEY_CONTEXT)),
        KeyBinding::new("home", Home, Some(KEY_CONTEXT)),
        KeyBinding::new("end", End, Some(KEY_CONTEXT)),
        KeyBinding::new("shift-home", SelectHome, Some(KEY_CONTEXT)),
        KeyBinding::new("shift-end", SelectEnd, Some(KEY_CONTEXT)),
        KeyBinding::new("ctrl-a", SelectAll, Some(KEY_CONTEXT)),
        KeyBinding::new("ctrl-v", Paste, Some(KEY_CONTEXT)),
        KeyBinding::new("ctrl-c", Copy, Some(KEY_CONTEXT)),
        KeyBinding::new("ctrl-x", Cut, Some(KEY_CONTEXT)),
        KeyBinding::new("ctrl-w", DeleteWord, Some(KEY_CONTEXT)),
        KeyBinding::new("ctrl-backspace", DeleteWord, Some(KEY_CONTEXT)),
        KeyBinding::new("alt-backspace", DeleteWord, Some(KEY_CONTEXT)),
        KeyBinding::new("ctrl-u", Clear, Some(KEY_CONTEXT)),
    ]);
}

#[derive(Default, Debug)]
struct EditBuffer {
    text: String,
    selection: Range<usize>,
    reversed: bool,
    marked: Option<Range<usize>>,
    tree_value: String,
    sent: Vec<String>,
}

impl EditBuffer {
    fn cursor(&self) -> usize {
        if self.reversed {
            self.selection.start
        } else {
            self.selection.end
        }
    }

    fn move_to(&mut self, offset: usize, select: bool) {
        let anchor = if self.reversed {
            self.selection.end
        } else {
            self.selection.start
        };
        if select {
            self.selection = anchor.min(offset)..anchor.max(offset);
            self.reversed = offset < anchor;
        } else {
            self.selection = offset..offset;
            self.reversed = false;
        }
    }

    fn previous_boundary(&self, offset: usize) -> usize {
        self.text
            .grapheme_indices(true)
            .rev()
            .find_map(|(i, _)| (i < offset).then_some(i))
            .unwrap_or(0)
    }

    fn next_boundary(&self, offset: usize) -> usize {
        self.text
            .grapheme_indices(true)
            .find_map(|(i, _)| (i > offset).then_some(i))
            .unwrap_or(self.text.len())
    }

    fn word_start(&self) -> usize {
        self.text[..self.cursor()]
            .trim_end()
            .char_indices()
            .rev()
            .find_map(|(i, c)| c.is_whitespace().then_some(i + c.len_utf8()))
            .unwrap_or(0)
    }

    fn from_utf16(&self, offset: usize) -> usize {
        let mut units = 0;
        for (i, c) in self.text.char_indices() {
            if units >= offset {
                return i;
            }
            units += c.len_utf16();
        }
        self.text.len()
    }

    fn to_utf16(&self, offset: usize) -> usize {
        self.text[..offset].encode_utf16().count()
    }

    fn range_from_utf16(&self, range: Range<usize>) -> Range<usize> {
        let start = self.from_utf16(range.start);
        start..self.from_utf16(range.end).max(start)
    }

    fn range_to_utf16(&self, range: &Range<usize>) -> Range<usize> {
        self.to_utf16(range.start)..self.to_utf16(range.end)
    }

    fn replace(&mut self, range: Option<Range<usize>>, text: &str) -> Range<usize> {
        let range = range
            .or(self.marked.clone())
            .unwrap_or(self.selection.clone());
        self.text.replace_range(range.clone(), text);
        let inserted = range.start..range.start + text.len();
        self.move_to(inserted.end, false);
        self.marked = None;
        inserted
    }

    fn apply_tree_value(&mut self, value: &str) {
        // An unrelated tree update with the same controlled value must not
        // undo local edits or an in-progress IME composition.
        if value == self.tree_value {
            return;
        }
        self.tree_value = value.to_string();
        if let Some(pos) = self.sent.iter().position(|v| v == value) {
            self.sent.drain(..=pos);
        } else if value != self.text {
            self.text = value.to_string();
            self.move_to(self.text.len(), false);
            self.marked = None;
            self.sent.clear();
        }
    }

    fn record_sent(&mut self) {
        self.sent.push(self.text.clone());
        if self.sent.len() > 64 {
            self.sent.remove(0);
        }
    }
}

fn single_line(text: &str) -> String {
    text.chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect()
}

pub struct TextInput {
    pub focus: FocusHandle,
    buffer: EditBuffer,
    props: Value,
    scale: f32,
    link: RuntimeLink,
    on_change: Option<String>,
    last_sent: String,
    last_line: Option<ShapedLine>,
    last_bounds: Option<Bounds<Pixels>>,
    scroll_x: Pixels,
    selecting: bool,
}

impl TextInput {
    pub fn new(props: Value, scale: f32, link: RuntimeLink, cx: &mut Context<Self>) -> Self {
        let mut input = Self {
            focus: cx.focus_handle().tab_stop(true),
            buffer: EditBuffer::default(),
            props: Value::Nil,
            scale,
            link,
            on_change: None,
            last_sent: String::new(),
            last_line: None,
            last_bounds: None,
            scroll_x: px(0.),
            selecting: false,
        };
        input.set_props(props, scale, cx);
        input
    }

    pub fn set_props(&mut self, props: Value, scale: f32, cx: &mut Context<Self>) {
        let value = single_line(props.get("value").and_then(Value::as_str).unwrap_or(""));
        let before = self.buffer.text.clone();
        self.buffer.apply_tree_value(&value);
        if self.buffer.text != before {
            self.last_sent = self.buffer.text.clone();
        }
        self.on_change = props
            .get("on-change")
            .and_then(Value::as_str)
            .map(str::to_string);
        self.props = props;
        self.scale = scale;
        cx.notify();
    }

    #[cfg(all(test, feature = "ui-tests"))]
    pub(crate) fn test_text(&self) -> &str {
        &self.buffer.text
    }

    fn publish(&mut self, cx: &mut Context<Self>) {
        if self.buffer.marked.is_none() && self.buffer.text != self.last_sent {
            self.last_sent = self.buffer.text.clone();
            if let Some(handler) = &self.on_change {
                self.buffer.record_sent();
                self.link
                    .event(handler, vec![Value::str(&self.buffer.text)]);
            }
        }
        cx.notify();
    }

    fn move_cursor(&mut self, forward: bool, select: bool, cx: &mut Context<Self>) {
        let b = &mut self.buffer;
        let offset = if !select && !b.selection.is_empty() {
            if forward {
                b.selection.end
            } else {
                b.selection.start
            }
        } else if forward {
            b.next_boundary(b.cursor())
        } else {
            b.previous_boundary(b.cursor())
        };
        b.move_to(offset, select);
        cx.notify();
    }

    fn left(&mut self, _: &Left, _: &mut Window, cx: &mut Context<Self>) {
        self.move_cursor(false, false, cx);
    }
    fn right(&mut self, _: &Right, _: &mut Window, cx: &mut Context<Self>) {
        self.move_cursor(true, false, cx);
    }
    fn select_left(&mut self, _: &SelectLeft, _: &mut Window, cx: &mut Context<Self>) {
        self.move_cursor(false, true, cx);
    }
    fn select_right(&mut self, _: &SelectRight, _: &mut Window, cx: &mut Context<Self>) {
        self.move_cursor(true, true, cx);
    }
    fn home(&mut self, _: &Home, _: &mut Window, cx: &mut Context<Self>) {
        self.buffer.move_to(0, false);
        cx.notify();
    }
    fn end(&mut self, _: &End, _: &mut Window, cx: &mut Context<Self>) {
        self.buffer.move_to(self.buffer.text.len(), false);
        cx.notify();
    }
    fn select_home(&mut self, _: &SelectHome, _: &mut Window, cx: &mut Context<Self>) {
        self.buffer.move_to(0, true);
        cx.notify();
    }
    fn select_end(&mut self, _: &SelectEnd, _: &mut Window, cx: &mut Context<Self>) {
        self.buffer.move_to(self.buffer.text.len(), true);
        cx.notify();
    }
    fn select_all(&mut self, _: &SelectAll, _: &mut Window, cx: &mut Context<Self>) {
        self.buffer.move_to(0, false);
        self.buffer.move_to(self.buffer.text.len(), true);
        cx.notify();
    }

    fn backspace(&mut self, _: &Backspace, _: &mut Window, cx: &mut Context<Self>) {
        if self.buffer.selection.is_empty() {
            self.buffer
                .move_to(self.buffer.previous_boundary(self.buffer.cursor()), true);
        }
        self.buffer.replace(None, "");
        self.publish(cx);
    }
    fn delete(&mut self, _: &Delete, _: &mut Window, cx: &mut Context<Self>) {
        if self.buffer.selection.is_empty() {
            self.buffer
                .move_to(self.buffer.next_boundary(self.buffer.cursor()), true);
        }
        self.buffer.replace(None, "");
        self.publish(cx);
    }
    fn delete_word(&mut self, _: &DeleteWord, _: &mut Window, cx: &mut Context<Self>) {
        if self.buffer.selection.is_empty() {
            self.buffer.move_to(self.buffer.word_start(), true);
        }
        self.buffer.replace(None, "");
        self.publish(cx);
    }
    fn clear(&mut self, _: &Clear, _: &mut Window, cx: &mut Context<Self>) {
        self.buffer.replace(Some(0..self.buffer.text.len()), "");
        self.publish(cx);
    }
    fn paste(&mut self, _: &Paste, _: &mut Window, cx: &mut Context<Self>) {
        if let Some(text) = cx.read_from_clipboard().and_then(|item| item.text()) {
            self.buffer.replace(None, &single_line(&text));
            self.publish(cx);
        }
    }
    fn copy(&mut self, _: &Copy, _: &mut Window, cx: &mut Context<Self>) {
        if !self.buffer.selection.is_empty() {
            cx.write_to_clipboard(ClipboardItem::new_string(
                self.buffer.text[self.buffer.selection.clone()].to_string(),
            ));
        }
    }
    fn cut(&mut self, _: &Cut, window: &mut Window, cx: &mut Context<Self>) {
        if !self.buffer.selection.is_empty() {
            self.copy(&Copy, window, cx);
            self.buffer.replace(None, "");
            self.publish(cx);
        }
    }

    fn index_at(&self, position: Point<Pixels>) -> usize {
        let (Some(bounds), Some(line)) = (self.last_bounds, self.last_line.as_ref()) else {
            return 0;
        };
        let index = line.closest_index_for_x(position.x - bounds.left() + self.scroll_x);
        // Mouse hit-testing must not leave the caret inside a grapheme.
        self.buffer
            .text
            .grapheme_indices(true)
            .map(|(i, _)| i)
            .chain(std::iter::once(self.buffer.text.len()))
            .min_by_key(|i| i.abs_diff(index))
            .unwrap_or(0)
    }

    fn mouse_down(&mut self, event: &MouseDownEvent, _: &mut Window, cx: &mut Context<Self>) {
        self.selecting = true;
        self.buffer
            .move_to(self.index_at(event.position), event.modifiers.shift);
        cx.notify();
    }
}

impl EntityInputHandler for TextInput {
    fn text_for_range(
        &mut self,
        range: Range<usize>,
        actual: &mut Option<Range<usize>>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<String> {
        let range = self.buffer.range_from_utf16(range);
        *actual = Some(self.buffer.range_to_utf16(&range));
        Some(self.buffer.text[range].to_string())
    }
    fn selected_text_range(
        &mut self,
        _: bool,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<UTF16Selection> {
        Some(UTF16Selection {
            range: self.buffer.range_to_utf16(&self.buffer.selection),
            reversed: self.buffer.reversed,
        })
    }
    fn marked_text_range(&self, _: &mut Window, _: &mut Context<Self>) -> Option<Range<usize>> {
        self.buffer
            .marked
            .as_ref()
            .map(|r| self.buffer.range_to_utf16(r))
    }
    fn unmark_text(&mut self, _: &mut Window, cx: &mut Context<Self>) {
        self.buffer.marked = None;
        self.publish(cx);
    }
    fn replace_text_in_range(
        &mut self,
        range: Option<Range<usize>>,
        text: &str,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        // Enter/Tab are surface shortcuts, not text. The Wayland backend can
        // forward their control characters after raw key dispatch.
        if !text.is_empty() && text.chars().all(char::is_control) {
            return;
        }
        let range = range.map(|r| self.buffer.range_from_utf16(r));
        self.buffer.replace(range, &single_line(text));
        self.publish(cx);
    }
    fn replace_and_mark_text_in_range(
        &mut self,
        range: Option<Range<usize>>,
        text: &str,
        selected: Option<Range<usize>>,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let range = range.map(|r| self.buffer.range_from_utf16(r));
        let inserted = self.buffer.replace(range, &single_line(text));
        self.buffer.marked = (!inserted.is_empty()).then_some(inserted.clone());
        if let Some(selected) = selected {
            // The selection is UTF-16 relative to the newly inserted preedit,
            // not to the entire string or the replaced range's old end.
            let base = self.buffer.to_utf16(inserted.start);
            self.buffer.selection = self
                .buffer
                .range_from_utf16(base + selected.start..base + selected.end);
            self.buffer.reversed = false;
        }
        cx.notify(); // Preedit stays local until the platform commits it.
    }
    fn bounds_for_range(
        &mut self,
        range: Range<usize>,
        bounds: Bounds<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<Bounds<Pixels>> {
        let line = self.last_line.as_ref()?;
        let range = self.buffer.range_from_utf16(range);
        Some(Bounds::from_corners(
            point(
                bounds.left() + line.x_for_index(range.start) - self.scroll_x,
                bounds.top(),
            ),
            point(
                bounds.left() + line.x_for_index(range.end) - self.scroll_x,
                bounds.bottom(),
            ),
        ))
    }
    fn character_index_for_point(
        &mut self,
        position: Point<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<usize> {
        self.last_bounds?.localize(&position)?;
        Some(self.buffer.to_utf16(self.index_at(position)))
    }
}

impl Focusable for TextInput {
    fn focus_handle(&self, _: &App) -> FocusHandle {
        self.focus.clone()
    }
}

impl Render for TextInput {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        div()
            .id("editor")
            .w_full()
            .min_w(px(0.))
            .flex_none()
            .key_context(KEY_CONTEXT)
            .track_focus(&self.focus)
            .cursor_text()
            .on_action(cx.listener(Self::backspace))
            .on_action(cx.listener(Self::delete))
            .on_action(cx.listener(Self::left))
            .on_action(cx.listener(Self::right))
            .on_action(cx.listener(Self::select_left))
            .on_action(cx.listener(Self::select_right))
            .on_action(cx.listener(Self::home))
            .on_action(cx.listener(Self::end))
            .on_action(cx.listener(Self::select_home))
            .on_action(cx.listener(Self::select_end))
            .on_action(cx.listener(Self::select_all))
            .on_action(cx.listener(Self::delete_word))
            .on_action(cx.listener(Self::clear))
            .on_action(cx.listener(Self::paste))
            .on_action(cx.listener(Self::copy))
            .on_action(cx.listener(Self::cut))
            .on_mouse_down(MouseButton::Left, cx.listener(Self::mouse_down))
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(|this, _, _, _| this.selecting = false),
            )
            .on_mouse_up_out(
                MouseButton::Left,
                cx.listener(|this, _, _, _| this.selecting = false),
            )
            .on_mouse_move(cx.listener(|this, event: &gpui::MouseMoveEvent, _, cx| {
                if this.selecting {
                    this.buffer.move_to(this.index_at(event.position), true);
                    cx.notify();
                }
            }))
            .child(TextElement { input: cx.entity() })
    }
}

struct TextElement {
    input: Entity<TextInput>,
}
struct PaintedText {
    line: ShapedLine,
    cursor: Option<PaintQuad>,
    selection: Option<PaintQuad>,
    scroll_x: Pixels,
}

impl IntoElement for TextElement {
    type Element = Self;
    fn into_element(self) -> Self {
        self
    }
}

impl Element for TextElement {
    type RequestLayoutState = ();
    type PrepaintState = PaintedText;
    fn id(&self) -> Option<ElementId> {
        None
    }
    fn source_location(&self) -> Option<&'static std::panic::Location<'static>> {
        None
    }

    fn request_layout(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&gpui::InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, ()) {
        let mut style = Style::default();
        style.size.width = relative(1.).into();
        style.size.height = window.line_height().into();
        (window.request_layout(style, [], cx), ())
    }

    fn prepaint(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&gpui::InspectorElementId>,
        bounds: Bounds<Pixels>,
        _: &mut (),
        window: &mut Window,
        cx: &mut App,
    ) -> PaintedText {
        let input = self.input.read(cx);
        let b = &input.buffer;
        let text_style = window.text_style();
        let placeholder = b.text.is_empty();
        let text = if placeholder {
            input
                .props
                .get("placeholder")
                .and_then(Value::as_str)
                .unwrap_or("")
        } else {
            &b.text
        };
        let color = if placeholder {
            input
                .props
                .get("placeholder-color")
                .and_then(style::color)
                .unwrap_or(text_style.color.opacity(0.5))
        } else {
            text_style.color
        };
        let run = TextRun {
            len: text.len(),
            font: text_style.font(),
            color,
            background_color: None,
            underline: None,
            strikethrough: None,
        };
        let runs = if let Some(marked) = &b.marked {
            vec![
                TextRun {
                    len: marked.start,
                    ..run.clone()
                },
                TextRun {
                    len: marked.len(),
                    underline: Some(UnderlineStyle {
                        color: Some(color),
                        thickness: px(1.),
                        wavy: false,
                    }),
                    ..run.clone()
                },
                TextRun {
                    len: text.len() - marked.end,
                    ..run
                },
            ]
            .into_iter()
            .filter(|r| r.len > 0)
            .collect::<Vec<_>>()
        } else {
            vec![run]
        };
        let line = window.text_system().shape_line(
            text.to_string().into(),
            text_style.font_size.to_pixels(window.rem_size()),
            &runs,
            None,
        );
        let caret_x = line.x_for_index(b.cursor());
        let width = bounds.size.width.max(px(0.));
        let scroll_x = input
            .scroll_x
            .min(caret_x)
            .max(caret_x - width + px(2.))
            .max(px(0.))
            .min((line.width - width + px(2.)).max(px(0.)));
        let x = |offset| bounds.left() + line.x_for_index(offset) - scroll_x;
        let focused = input.focus.is_focused(window);
        let caret_color = input
            .props
            .get("caret-color")
            .and_then(style::color)
            .unwrap_or(text_style.color);
        let cursor = (focused && b.selection.is_empty()).then(|| {
            fill(
                Bounds::new(
                    point(x(b.cursor()), bounds.top()),
                    size(px(1.5 * input.scale), bounds.size.height),
                ),
                caret_color,
            )
        });
        let selection = (focused && !b.selection.is_empty()).then(|| {
            fill(
                Bounds::from_corners(
                    point(x(b.selection.start), bounds.top()),
                    point(x(b.selection.end), bounds.bottom()),
                ),
                rgba(0xcba6f755),
            )
        });
        PaintedText {
            line,
            cursor,
            selection,
            scroll_x,
        }
    }

    fn paint(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&gpui::InspectorElementId>,
        bounds: Bounds<Pixels>,
        _: &mut (),
        painted: &mut PaintedText,
        window: &mut Window,
        cx: &mut App,
    ) {
        let focus = self.input.read(cx).focus.clone();
        window.handle_input(
            &focus,
            ElementInputHandler::new(bounds, self.input.clone()),
            cx,
        );
        window.with_content_mask(Some(gpui::ContentMask { bounds }), |window| {
            if let Some(selection) = painted.selection.take() {
                window.paint_quad(selection);
            }
            let _ = painted.line.paint(
                point(bounds.left() - painted.scroll_x, bounds.top()),
                window.line_height(),
                gpui::TextAlign::Left,
                None,
                window,
                cx,
            );
            if let Some(cursor) = painted.cursor.take() {
                window.paint_quad(cursor);
            }
        });
        self.input.update(cx, |input, _| {
            input.last_line = Some(painted.line.clone());
            input.last_bounds = Some(bounds);
            input.scroll_x = painted.scroll_x;
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn buffer(text: &str) -> EditBuffer {
        let mut b = EditBuffer::default();
        b.apply_tree_value(text);
        b
    }

    #[test]
    fn edits_at_cursor_and_replaces_selection() {
        let mut b = buffer("abcd");
        b.move_to(2, false);
        b.replace(None, "X");
        assert_eq!(b.text, "abXcd");
        assert_eq!(b.cursor(), 3);
        b.move_to(1, false);
        b.move_to(4, true);
        b.replace(None, "Y");
        assert_eq!(b.text, "aYd");
        assert_eq!(b.selection, 2..2);
    }

    #[test]
    fn grapheme_deletion_and_navigation() {
        let mut b = buffer("a👩‍💻e\u{301}");
        let end = b.cursor();
        let previous = b.previous_boundary(end);
        assert_eq!(&b.text[previous..], "e\u{301}");
        b.move_to(previous, true);
        b.replace(None, "");
        assert_eq!(b.text, "a👩‍💻");
        assert_eq!(b.previous_boundary(b.cursor()), 1);
        assert_eq!(b.next_boundary(1), b.text.len());
    }

    #[test]
    fn utf16_ranges_cover_astral_characters() {
        let b = buffer("a😀z");
        assert_eq!(b.range_from_utf16(1..3), 1..5);
        assert_eq!(b.range_to_utf16(&(1..5)), 1..3);
        assert_eq!(b.range_from_utf16(99..100), 6..6);
    }

    #[test]
    fn reversed_selection_keeps_anchor() {
        let mut b = buffer("abcd");
        b.move_to(3, false);
        b.move_to(1, true);
        assert_eq!(b.selection, 1..3);
        assert!(b.reversed);
        b.move_to(4, true);
        assert_eq!(b.selection, 3..4);
        assert!(!b.reversed);
    }

    #[test]
    fn echoed_values_do_not_undo_newer_local_edits() {
        let mut b = buffer("");
        b.replace(None, "a");
        b.record_sent();
        b.replace(None, "b");
        b.record_sent();
        b.apply_tree_value("");
        assert_eq!(b.text, "ab");
        b.apply_tree_value("a");
        assert_eq!(b.text, "ab");
        b.apply_tree_value("ab");
        assert!(b.sent.is_empty());
        b.apply_tree_value("reset");
        assert_eq!(b.text, "reset");
    }

    #[test]
    fn preedit_replaces_marked_range_and_survives_unrelated_tree() {
        let mut b = buffer("ab");
        b.move_to(1, false);
        b.marked = Some(b.replace(None, "に"));
        b.apply_tree_value("ab");
        assert_eq!(b.text, "aにb");
        b.marked = Some(b.replace(None, "日本"));
        assert_eq!(b.text, "a日本b");
        assert_eq!(b.range_to_utf16(b.marked.as_ref().unwrap()), 1..3);
        b.replace(None, "日本語");
        assert_eq!(b.text, "a日本語b");
        assert!(b.marked.is_none());
    }

    #[test]
    fn word_deletion_handles_multibyte_whitespace() {
        let b = buffer("one\u{2003}two ");
        assert_eq!(b.word_start(), "one\u{2003}".len());
        assert_eq!(single_line("a\nb\tc"), "a b c");
    }

    #[cfg(feature = "ui-tests")]
    #[gpui::test]
    fn platform_input_actions_and_clipboard(cx: &mut gpui::TestAppContext) {
        cx.update(init);
        let (link, events) = RuntimeLink::test_channel();
        let (input, cx) = cx.add_window_view(|window, cx| {
            let input = TextInput::new(
                Value::map([("on-change", Value::str("change"))]),
                1.,
                link,
                cx,
            );
            window.focus(&input.focus, cx);
            input
        });
        cx.simulate_input("a👩‍💻e\u{301}");
        cx.simulate_keystrokes("enter tab");
        assert_eq!(
            input.read_with(cx, |input, _| input.buffer.text.clone()),
            "a👩‍💻e\u{301}"
        );
        cx.simulate_keystrokes("backspace left shift-left ctrl-c end ctrl-v");
        assert_eq!(
            input.read_with(cx, |input, _| input.buffer.text.clone()),
            "a👩‍💻a"
        );
        cx.update(|_, cx| {
            cx.write_to_clipboard(ClipboardItem::new_string("new\ntext".to_string()))
        });
        cx.simulate_keystrokes("ctrl-a ctrl-v");
        assert_eq!(
            input.read_with(cx, |input, _| input.buffer.text.clone()),
            "new text"
        );
        cx.simulate_keystrokes("home right delete end ctrl-w");
        assert_eq!(
            input.read_with(cx, |input, _| input.buffer.text.clone()),
            "nw "
        );
        let last = events.try_iter().last().unwrap();
        let event = crate::edn::parse(&last).unwrap();
        assert_eq!(event.get("handler").and_then(Value::as_str), Some("change"));
        assert_eq!(event.get("args").unwrap().items()[0].as_str(), Some("nw "));
    }

    #[cfg(feature = "ui-tests")]
    #[gpui::test]
    fn ime_preedit_is_local_and_commit_emits_change(cx: &mut gpui::TestAppContext) {
        let (link, events) = RuntimeLink::test_channel();
        let (input, cx) = cx.add_window_view(|window, cx| {
            let input = TextInput::new(
                Value::map([
                    ("value", Value::str("ab")),
                    ("on-change", Value::str("change")),
                ]),
                1.,
                link,
                cx,
            );
            window.focus(&input.focus, cx);
            input
        });
        input.update_in(cx, |input, window, cx| {
            input.buffer.move_to(1, false);
            input.replace_and_mark_text_in_range(None, "日本", Some(1..2), window, cx);
            assert_eq!(
                input.selected_text_range(false, window, cx).unwrap().range,
                2..3
            );
            input.set_props(input.props.clone(), 1., cx); // Unrelated controlled update.
            input.replace_and_mark_text_in_range(None, "日本語", None, window, cx);
        });
        assert!(events.try_recv().is_err());
        input.update_in(cx, |input, window, cx| input.unmark_text(window, cx));
        let event = crate::edn::parse(&events.try_recv().unwrap()).unwrap();
        assert_eq!(
            event.get("args").unwrap().items()[0].as_str(),
            Some("a日本語b")
        );
        assert!(events.try_recv().is_err());
    }
}
