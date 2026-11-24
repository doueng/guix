// Bombadil over the ghostty-rex client (bb verify, T223): the cursor Ghostty
// would show, as rexg-render draws it into Bombadil's terminal, against what
// the focused pane's program asked for. Tabs 1 and 2 run test/rexg/modal.bb,
// whose first row says its mode (MODE=normal, insert or bar, ALT on the
// alternate screen); tab 3 runs fish with desktop/configs/fish's vi cursor and
// a mode prompt ([default], [insert], [visual], ...); tab 4 runs terminal
// Emacs with evil and desktop/configs/doom/cursor.el (test/rexg/emacs-cursor.el),
// whose mode line says MODE=<evil state> and which redisplays at the end of
// each command, as the person's Doom did when o left the cursor visible in
// insert state until the next key. The person's cursors:
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
  emacs: boolean; // the focused pane runs Emacs
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
  // Pane titles say the program and directory (T210), as ▶ bb ~ ───, or
  // ┌▶ … on a floating pane.
  const title = (l: string, mark: string) => new RegExp(`(^|[│┌])${mark} [^─│ ]`).test(l);
  const top = lines.findIndex((l, i) => i > 0 && i < n - 2 && title(l, "▶"));
  let bottom = n - 3;
  let mode: string | null = null;
  if (top >= 0) {
    // A floating pane's mode is inside its box, not in the panes under it.
    const left = lines[top].indexOf("┌▶"), right = lines[top].lastIndexOf("┐");
    for (let i = top + 1; i < n - 2; i++) {
      if (left >= 0 && lines[i][left] === "└") { bottom = i - 1; break; }
      if (left < 0 && title(lines[i], "[▶─]")) { bottom = i - 1; break; }
      const text = left >= 0 ? lines[i].slice(left + 1, right) : lines[i];
      const m = text.match(/MODE=(\w+)/) ?? text.match(/\[(default|insert|visual|replace|replace_one)\]/);
      // The lowest: fish's earlier prompts keep the mode they had.
      if (m) mode = m[1];
    }
  }
  const emacs = top >= 0 && /▶ \S*emacs/.test(lines[top]);
  return {
    rows: n,
    focusTop: top,
    focusBottom: bottom,
    mode,
    emacs,
    // Rex's M-x, or Emacs's own minibuffer (ESC then x is M-x there) on the
    // pane's last row.
    minibuffer: (s.cursor.position.row === n - 1 && /^M-x( |$)/.test(last)) ||
      (emacs && s.cursor.position.row === bottom && (lines[bottom] ?? "").trim() !== ""),
    prefix: /^alt\+space( \S+)*-$/.test(last),
    hints: /^\d+ hints: type one/.test(last),
    visible: s.cursor.visible,
    style: s.cursor.visualStyle,
    row: s.cursor.position.row,
    col: s.cursor.position.column,
    whole: /^ \S+ │/.test(lines[0] ?? "") && /│ agents \d+ │ blocked /.test(lines[n - 2] ?? ""),
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
    case "operator": case "motion":
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

// Emacs sends DECTCEM as a state starts, before it draws anything, so no
// frame shows its insert state with a cursor. Before, a redisplay inside the
// command left the cursor visible until the next key, which the rule above
// misses: Bombadil always types again within the second.
export const emacsInsertHasNoCursor = always(() => {
  const x = v();
  return !(x.emacs && x.whole && !x.minibuffer && x.mode === "insert" && x.visible);
});

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
  // Every way into insert state in Emacs (fish and modal.bb take some too).
  [5, actions(() => [key("o"), key("O"), key("A"), key("I"), key("cc"), key("C"), key("s"), key("gi"), key("cw")])],
  [4, actions(() => [key(AS + "t1"), key(AS + "t2"), key(AS + "t3"), key(AS + "t4")])],
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
