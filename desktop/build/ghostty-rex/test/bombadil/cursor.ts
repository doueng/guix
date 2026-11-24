// Bombadil over the ghostty-rex client (bb verify, T223): the cursor Ghostty
// would show, as rexg-render draws it into Bombadil's terminal, against what
// the focused pane's program asked for. Tabs 1 and 2 run test/rexg/modal.bb,
// whose first row says its mode (MODE=normal, insert or bar, ALT on the
// alternate screen); tab 3 runs fish with desktop/configs/fish's vi cursor and
// a mode prompt ([default], [insert], [visual], ...). The person's cursors:
// none in insert mode, an underscore otherwise; a bar on modal.bb's alternate
// screen. verify/quint/cursor.qnt models the same rules.
import { always, eventually, now } from "@antithesishq/bombadil";
import { actions, extract, weighted } from "@antithesishq/bombadil/terminal";

const ESC = "\x1b";
// Alt+Space, the local prefix, as a terminal sends it: Escape then Space.
const AS = "\x1b ";
const MX = "\x1bx";

type Want = { visible: boolean; style: string | null } | null;
type View = {
  rows: number;
  focusTop: number; // the focused pane's title row, -1 without one
  focusBottom: number;
  mode: string | null;
  minibuffer: boolean;
  prefix: boolean;
  hints: boolean;
  visible: boolean;
  style: string;
  row: number;
  col: number;
  // A whole rexg-render frame: the tab bar and the status line where they
  // belong. Right after a resize the terminal shows its own reflow of the
  // old frame until rexg-render's redraw arrives, which may take a sample.
  whole: boolean;
};

const view = extract((s): View => {
  const n = s.grid.size.rows;
  const lines: string[] = [];
  for (let i = 0; i < n; i++) lines.push(s.grid.rowText(i));
  const last = (lines[n - 1] ?? "").trimEnd();
  const top = lines.findIndex((l, i) => i > 0 && i < n - 2 && /▶ [0-9a-f]{6}/.test(l));
  let bottom = n - 3;
  let mode: string | null = null;
  if (top >= 0) {
    for (let i = top + 1; i < n - 2; i++) {
      if (/[▶─] [0-9a-f]{6}/.test(lines[i])) { bottom = i - 1; break; }
      const m = lines[i].match(/MODE=(\w+)/) ?? lines[i].match(/\[(default|insert|visual|replace|replace_one)\]/);
      // The lowest: fish's earlier prompts keep the mode they had.
      if (m) mode = m[1];
    }
  }
  return {
    rows: n,
    focusTop: top,
    focusBottom: bottom,
    mode,
    minibuffer: s.cursor.position.row === n - 1 && /^M-x( |$)/.test(last),
    prefix: /^alt\+space( \S+)*-$/.test(last),
    hints: /^\d+ hints: type one/.test(last),
    visible: s.cursor.visible,
    style: s.cursor.visualStyle,
    row: s.cursor.position.row,
    col: s.cursor.position.column,
    whole: /^ \S+ │/.test(lines[0] ?? "") && /│ focus /.test(lines[n - 2] ?? ""),
  };
});

const v = () => view.current as View;

// What the cursor should be; null when the spec cannot tell (no mode shown yet).
function want(x: View): Want {
  if (x.minibuffer) return { visible: true, style: null };
  if (x.prefix || x.hints) return { visible: false, style: null };
  switch (x.mode) {
    case "insert": return { visible: false, style: null };
    case "bar": return { visible: true, style: "Bar" };
    case "normal": case "default": case "visual": case "replace": case "replace_one":
      return { visible: true, style: "Underline" };
    default: return null;
  }
}
const matches = () => {
  const x = v(), w = want(x);
  return w === null || (x.visible === w.visible && (!w.visible || w.style === null || x.style === w.style));
};

// Once the screen settles, the cursor is what the focused program or the
// minibuffer asked for: a mode switch, a tab switch, an overlay closing or a
// resize gives it back within a second.
export const cursorFollowsTheMode = always(now(() => !matches()).implies(eventually(matches).within(1, "seconds")));

// The fixture runs: the focused pane shows its mode, so the rules above are
// not met by default.
export const aModeShows = eventually(() => v().mode !== null).within(20, "seconds");

// Which-key and tiny fingers hide the cursor in the same frame they draw in.
export const noCursorUnderAnOverlay = always(() => !v().whole || !(v().prefix || v().hints) || !v().visible);

// A shown cursor is the minibuffer's or inside the focused pane: never in
// another pane, the tab bar or the status line.
export const cursorInTheFocusedPane = always(() => {
  const x = v();
  if (!x.visible || !x.whole) return true;
  if (x.minibuffer) return true;
  return x.focusTop >= 0 && x.row > x.focusTop && x.row <= x.focusBottom;
});

const key = (k: string) => ({ TypeText: { Regexp: k.replace(/[.\[\]()?*+|^$\\]/g, (c) => "\\" + c) } });

export const inPane = weighted([
  [6, actions(() => [key("i"), key(ESC), key("a"), key("q"), key("v"), key("x")])],
  [4, actions(() => [key(AS + "t1"), key(AS + "t2"), key(AS + "t3")])],
  [2, actions(() => [key(MX)])],
  [2, actions(() => [key(AS)])],
  [1, actions(() => [key(AS + "sl")])],
  [1, actions(() => [{ Resize: { rows: 30, columns: 110 } }, { Resize: { rows: 24, columns: 90 } }])],
]).when(() => !v().minibuffer && !v().prefix && !v().hints);

// Overlays close again; leader keys after the prefix would run commands
// (an editor over the panes, closing a pane) this spec does not explore.
export const inOverlay = weighted([
  [3, actions(() => [key(ESC), key("\x07")])],
  [1, actions(() => [{ TypeText: { Regexp: "[a-z]{1,2}" } }]).when(() => v().minibuffer)],
]).when(() => v().minibuffer || v().prefix || v().hints);
