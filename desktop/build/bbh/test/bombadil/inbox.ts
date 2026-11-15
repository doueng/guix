import { always, eventually, next, not, now } from "@antithesishq/bombadil";
import { actions, extract, weighted } from "@antithesishq/bombadil/terminal";

const SPLIT = 45;
const ESC = "\x1b";
const CN = "\x0e";
const CCCC = "\x03\x03";
const CC_GAP = 15;
const MOTIONS = new Set(["j", "k", "n", "p", "]t", "[t", "o", "\t"]);

type Row = { id: string; glyph: string; asks: number };
type View = {
  rows: Row[];
  selected: string | null;
  section: string | null;
  asks: string[];
  picked: string[];
  refusals: number;
  listCursor: string | null;
  inPane: boolean;
  insert: boolean;
  echo: string;
  text: string;
  key: string | null;
  minibuffer: boolean;
  badge: number | null;
  fits: boolean;
  wasted: boolean;
  sinceSend: number;
  sentSinceCc: boolean;
  prevKey: string | null;
  closed: string[];
};

function typed(a: any): string | null {
  if (!a || !a.TypeText) return null;
  return typeof a.TypeText === "string" ? a.TypeText : (a.TypeText.text ?? null);
}

// Formulas read counters from the view: a formula may be evaluated after later states.
let sinceSend = 1000;
let sentSinceCc = false;
let lastKey: string | null = null;
const seenUnder: Record<string, string[]> = {};
const closed: string[] = [];

const view = extract((s): View => {
  const n = s.grid.size.rows;
  const lines: string[] = [];
  for (let i = 0; i < n; i++) lines.push(s.grid.rowText(i));
  const rows: Row[] = [];
  let listCursor: string | null = null;
  const cur = s.cursor.position;
  for (let i = 2; i < n - 2; i++) {
    const left = lines[i].slice(0, SPLIT);
    const m = left.match(/^.(.) (T\d+)\s/);
    if (m) {
      const q = left.match(/(\d+)\?\s+\S+\s*$/);
      rows.push({ glyph: m[1], id: m[2], asks: q ? parseInt(q[1], 10) : 0 });
      if (cur.row === i && cur.column < SPLIT) listCursor = m[2];
    }
  }
  const right = lines.slice(1, n - 2).map((l) => l.slice(SPLIT + 1));
  const head = right[0].match(/^(T\d+)\s+1-9 pick/);
  let section: string | null = null;
  const asks: string[] = [];
  const picked: string[] = [];
  let ask: string | null = null;
  for (const l of right) {
    const h = l.match(/^\*+ ([A-Z-]+) (T\d+)\s/);
    if (h && head && h[2] === head[1]) section = h[1];
    const a = l.match(/^\*+ a\d+ (q\d{3} [a-z]+\?)/);
    if (a) { ask = a[1]; asks.push(ask); }
    if (ask && /<- picked\s*$/.test(l) && !picked.includes(ask)) picked.push(ask);
  }
  const mode = lines[n - 2];
  const badge = lines[1].slice(0, SPLIT).match(/need you|(\d+) need you/);
  const listMode = mode.slice(0, SPLIT);
  const fits = / All /.test(listMode);
  const legend = lines.findIndex((l, i) => i >= 2 && i < n - 2 && l.slice(0, SPLIT).startsWith("● you  ◐ working"));
  const wasted = / Bot /.test(listMode) && legend > 0 && legend < n - 3 &&
    lines[legend + 1].slice(0, SPLIT).trim() === "";
  const inPane = cur.column > SPLIT;
  const tag = inPane ? mode.slice(SPLIT + 1) : mode.slice(0, SPLIT);
  const refusals = Math.max(0, ...[...mode.matchAll(/refused:(\d+)/g)].map((m) => parseInt(m[1], 10)));
  const key = typed(s.lastAction);
  if (key === CN || key === "s") { sinceSend = 0; sentSinceCc = true; } else sinceSend++;
  if (key === CCCC) sentSinceCc = false;
  const before = lastKey;
  lastKey = key;
  if (head && section !== null) {
    for (const a of seenUnder[head[1]] ?? []) if (!asks.includes(a) && !closed.includes(a)) closed.push(a);
    seenUnder[head[1]] = asks.slice();
  }
  return {
    rows, selected: head ? head[1] : null, section, asks, picked, refusals, listCursor, inPane,
    insert: tag.includes("<I>"), echo: lines[n - 1].trim(), text: right.join("\n"), key,
    prevKey: before, closed: closed.slice(), sinceSend, sentSinceCc, minibuffer: cur.row === n - 1, badge: badge && badge[1] ? parseInt(badge[1], 10) : null, fits, wasted,
  };
});

const v = () => view.current as View;
const glyphOf = (id: string | null) => v().rows.find((r) => r.id === id)?.glyph ?? null;
const shows = (ask: string) => v().asks.includes(ask);

export const rowWithAsksNeverWorking = always(() =>
  v().rows.every((r) => r.asks === 0 || r.glyph === "●"));

const needYouHidden = () => {
  const x = v();
  return x.fits && !x.minibuffer && x.badge !== null &&
    x.rows.filter((r) => r.glyph === "●").length !== x.badge;
};
export const listShowsEveryThreadThatNeedsYou = always(
  now(needYouHidden).implies(eventually(() => !needYouHidden()).within(1, "seconds")));

export const listFillsItsWindow = always(
  now(() => v().wasted && !v().minibuffer).implies(eventually(() => !v().wasted).within(1, "seconds")));

export const ccOpensNoPrompt = always(() => v().key !== CCCC || !v().minibuffer);

const paneWaitsButListWorks = () => {
  const x = v();
  return !x.insert && x.selected !== null && glyphOf(x.selected) === "◐" &&
    (x.asks.length > 0 || ["STOPPED", "HOLDING-UP", "WAITING"].includes(x.section ?? ""));
};
export const paneAndListAgreeOnWaiting = always(
  now(paneWaitsButListWorks).implies(eventually(() => !paneWaitsButListWorks()).within(2, "seconds")));

const refused = () => /stale|changed; look again|no-such-ask/.test(v().echo);
export const sentAskCloses = always(() => {
  const picked = v().picked;
  const normal = !v().insert;
  const refusals = v().refusals;
  return now(() => normal && picked.length > 0)
    .and(next(() => v().key === CN || (v().key === "s" && v().inPane)))
    .implies(eventually(() => picked.every((a) => !shows(a)) || refused() || v().refusals > refusals).within(8, "seconds"));
});

export const closedAskStaysClosed = always(() => v().asks.every((a) => !v().closed.includes(a)));

export const cnNeverUndefined = always(() => !/C-n is undefined/.test(v().echo));

export const ccNeverSends = always(
  now(() => v().key === CCCC && v().sinceSend > CC_GAP)
    .implies(not(eventually(() => !v().sentSinceCc && /^Sent T\d+/.test(v().echo)).within(3, "seconds"))));

export const selectionStaysPut = always(() => {
  const sel = v().selected;
  return now(() => sel !== null).implies(next(() => {
    const x = v();
    return MOTIONS.has(x.key ?? "") || MOTIONS.has(x.prevKey ?? "") || x.selected === null || x.selected === sel ||
      !x.rows.some((r) => r.id === sel);
  }));
});

const cursorOffSelected = () => {
  const x = v();
  return !x.inPane && x.listCursor !== null && x.selected !== null && x.listCursor !== x.selected;
};
export const listCursorOnSelected = always(
  now(cursorOffSelected).implies(eventually(() => !cursorOffSelected()).within(1, "seconds")));

export const insertStateTypes = always(() => {
  const x = v();
  const ok = x.insert && x.inPane && x.asks.length > 0 && x.sinceSend > 15 && /^\*+ Answer\s*$/m.test(x.text);
  return next(() => {
    const k = v().key;
    return !ok || k === null || !/^[a-z]{3,6}$/.test(k) || v().text.includes(k) ||
      !x.asks.every((a) => v().asks.includes(a));
  });
});

const key = (k: string) => ({ TypeText: { Regexp: k.replace(/[.\[\]]/g, (c) => "\\" + c) } });

export const inList = weighted([
  [6, actions(() => [key("j"), key("k")])],
  [2, actions(() => [key("]t"), key("[t")])],
  [2, actions(() => [key("\t")])],
  [1, actions(() => [key("gr")])],
  [1, actions(() => [key(CN)])],
  [1, actions(() => [key(CCCC)]).when(() => v().sinceSend > CC_GAP)],
]).when(() => !v().inPane && !v().insert && !v().minibuffer);

export const inPaneNormal = weighted([
  [3, actions(() => [key("j"), key("k")])],
  [4, actions(() => [key("1"), key("2"), key(".")])],
  [3, actions(() => [key("i")])],
  [3, actions(() => [key(CN)])],
  [1, actions(() => [key("s")])],
  [2, actions(() => [key("o"), key("]t")])],
  [1, actions(() => [key("gr")])],
  [2, actions(() => [key(CCCC)]).when(() => v().sinceSend > CC_GAP)],
]).when(() => v().inPane && !v().insert && !v().minibuffer);

export const inInsert = weighted([
  [6, actions(() => [{ TypeText: { Regexp: "[a-z]{3,6}" } }])],
  [1, actions(() => [key(" ")])],
  [2, actions(() => [key(ESC)])],
  [2, actions(() => [key(CN)])],
  [2, actions(() => [key(CCCC)]).when(() => v().sinceSend > CC_GAP)],
]).when(() => v().insert && !v().minibuffer);

export const leaveStrayInsert = actions(() => [key(ESC)]).when(() => v().insert && !v().inPane && !v().minibuffer);

export const quitPrompt = actions(() => [key("\x07")]).when(() => v().minibuffer);
