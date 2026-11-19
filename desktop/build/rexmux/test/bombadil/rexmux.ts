// Bombadil over `rexmux tty` on a throwaway Rex server (bb test:bombadil).
// The tty frontend draws the same screen model as the window: row 0 the
// tabs, then pane title rows (▶ on the focused pane, ! before a blocked
// agent), the status line, and the echo or minibuffer line.
import { always, eventually, next, now } from "@antithesishq/bombadil";
import { actions, extract, weighted } from "@antithesishq/bombadil/terminal";

const ESC = "\x1b";
const CB = "\x02";
const MX = "\x1bx";

type View = {
  tabs: number;
  panes: number;
  markers: number;
  marked: string | null;
  markedBlocked: boolean;
  focus: string | null;
  blocked: number;
  sidecarDown: boolean;
  last: string;
  mx: boolean;
  minibuffer: boolean;
  selected: number;
  candidates: number;
  prefix: boolean;
  whichKey: number;
  key: string | null;
  wasPrefix: boolean;
  wasMinibuffer: boolean;
};

function typed(a: any): string | null {
  if (!a || !a.TypeText) return null;
  return typeof a.TypeText === "string" ? a.TypeText : (a.TypeText.text ?? null);
}

let lastPrefix = false;
let lastMinibuffer = false;

const view = extract((s): View => {
  const n = s.grid.size.rows;
  const lines: string[] = [];
  for (let i = 0; i < n; i++) lines.push(s.grid.rowText(i));
  const status = lines[n - 2] ?? "";
  const last = (lines[n - 1] ?? "").trimEnd();
  const titles = lines.slice(1, n - 2).flatMap((l) => [...l.matchAll(/([▶─]) ([0-9a-f]{6})( ! )?/g)]);
  const marked = titles.filter((m) => m[1] === "▶");
  // The tty frontend puts the cursor on the last row only while the minibuffer is open.
  const mb = s.cursor.position.row === n - 1;
  const cands = lines.slice(1, n - 2).filter((l) => /^[> ] \S/.test(l));
  const fm = status.match(/focus (\S+)/);
  const bm = status.match(/blocked (\d+)/);
  const prefix = /^ctrl\+b-$/.test(last);
  const key = typed(s.lastAction);
  const v: View = {
    tabs: ((lines[0] ?? "").match(/(\[\d+|  \d+)/g) ?? []).length,
    panes: titles.length,
    markers: marked.length,
    marked: marked.length ? marked[0][2] : null,
    markedBlocked: marked.length > 0 && marked[0][3] !== undefined,
    focus: fm && fm[1] !== "-" ? fm[1] : null,
    blocked: bm ? parseInt(bm[1], 10) : 0,
    sidecarDown: /sidecar down/.test(status),
    last,
    mx: mb && /^M-x( |$)/.test(last),
    minibuffer: mb,
    selected: cands.filter((l) => l.startsWith("> ")).length,
    candidates: cands.length,
    prefix,
    whichKey: prefix ? lines.slice(1, n - 2).filter((l) => /^  \S+  \S/.test(l)).length : 0,
    key,
    wasPrefix: lastPrefix,
    wasMinibuffer: lastMinibuffer,
  };
  lastPrefix = prefix;
  lastMinibuffer = mb;
  return v;
});

const v = () => view.current as View;

// Pane focus: one focused pane, and the status line names it.
export const oneFocusedPane = always(() => v().markers <= 1);
const focusDisagrees = () => v().marked !== null && v().focus !== null && v().marked !== v().focus;
export const statusNamesTheFocusedPane = always(
  now(focusDisagrees).implies(eventually(() => !focusDisagrees()).within(2, "seconds")));

// M-x: a list with candidates has exactly one selected.
export const mxSelectsOneCandidate = always(() => !v().mx || v().candidates === 0 || v().selected === 1);

// Escape and C-g always leave the minibuffer.
export const escapeLeavesTheMinibuffer = always(
  () => !(v().wasMinibuffer && (v().key === ESC || v().key === "\x07")) || !v().minibuffer);

// Keymaps: Ctrl+b starts a prefix and shows what can follow it.
export const prefixShowsItsKeys = always(
  () => !(v().key === CB && !v().wasPrefix && !v().wasMinibuffer) || (v().prefix && v().whichKey > 0));

// Agent attention: with a blocked agent anywhere, Ctrl+b u reaches one.
export const nextBlockedReachesABlockedAgent = always(
  now(() => v().key === CB + "u" && v().blocked > 0 && !v().wasMinibuffer && !v().wasPrefix)
    .implies(eventually(() => v().markedBlocked).within(3, "seconds")));

// A blocked agent is never hidden: the status line keeps counting it.
export const blockedAgentStaysCounted = always(
  now(() => v().blocked > 0).implies(next(() => v().blocked > 0 || v().minibuffer || v().focus === null)));

export const sidecarStaysUp = always(() => !v().sidecarDown);

const key = (k: string) => ({ TypeText: { Regexp: k.replace(/[.\[\]()?*+|^$\\]/g, (c) => "\\" + c) } });
const SAFE_MX = ["panes: next", "tabs: next", "panes: zoom", "agents: focus the next blocked", "panes: focus left"];

export const normal = weighted([
  [4, actions(() => [key(CB + "h"), key(CB + "j"), key(CB + "k"), key(CB + "l"), key(CB + "o")])],
  [3, actions(() => [key(CB + "n"), key(CB + "p"), key(CB + "1"), key(CB + "2")])],
  [3, actions(() => [key(CB + "u")])],
  [1, actions(() => [key(CB + "U")])],
  [2, actions(() => [key(CB + "%"), key(CB + '"')]).when(() => v().panes < 5)],
  [1, actions(() => [key(CB + "c")]).when(() => v().tabs < 4)],
  [1, actions(() => [key(CB + "x")]).when(() => v().panes > 2 && !v().markedBlocked)],
  [1, actions(() => [key(CB + "z")])],
  [2, actions(() => [key(CB)])],
  [3, actions(() => [key(MX)])],
  [1, actions(() => [key(CB + "w")])],
  [1, actions(() => [key("ls\r")])],
]).when(() => !v().minibuffer && !v().prefix);

export const inPrefix = weighted([
  [3, actions(() => [key("h"), key("l"), key("u"), key("n")])],
  [2, actions(() => [key(ESC), key("\x07")])],
  [1, actions(() => [key("q")])],
]).when(() => v().prefix);

export const inMx = weighted([
  [4, actions(() => [{ TypeText: { Regexp: "[a-z]{1,3}" } }])],
  [2, actions(() => [key("\x7f"), key("\x15")])],
  [3, actions(() => [key("\x1b[B"), key("\x1b[A")])],
  [3, actions(() => [key(ESC), key("\x07")])],
  // Enter only on commands that are safe on the test server.
  [3, actions(() => SAFE_MX.map((c) => key("\x15" + c + "\r")))],
]).when(() => v().mx);

export const inPick = weighted([
  [3, actions(() => [key("\x1b[B"), key("\x1b[A")])],
  [2, actions(() => [key("\r")])],
  [2, actions(() => [key(ESC)])],
]).when(() => v().minibuffer && !v().mx);
