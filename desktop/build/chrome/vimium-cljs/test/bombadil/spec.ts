// Properties of vimium-cljs while Bombadil explores the fixture site (test/bombadil/run.mjs runs it).
// Facts come from the page probe (test/fixtures/probe.js). Key presses go through
// /__ctl/keys, which sends trusted key events over the DevTools pipe, since the
// extension ignores synthetic events.
import { always } from "@antithesishq/bombadil";
import { actions, extract, registerCustomAction, weighted } from "@antithesishq/bombadil/browser";
import { back, clicks, scroll, waitOnce } from "@antithesishq/bombadil/browser/defaults/actions";

type Link = { id: string; visible: boolean; hint: string | null };
type Facts = {
  url: string; ready: boolean; mode: string; hintsMs: number; hintsScrollY: number; scrollY: number; maxScrollY: number;
  links: Link[]; findHighlights: number; findCurrent: number; needles: number;
  pageKeys: number; fieldKeys: string; fields: { id: string; value: string }[]; active: string | null;
  frames: Record<string, Facts>;
  last: null | { kind: string; seq: string; before: Facts; after: Facts; field?: string };
};

const facts = extract((s) => {
  const w = s.window as unknown as { __probeFacts?: () => Facts };
  return (w.__probeFacts ? w.__probeFacts() : null) as any;
});
const f = () => facts.current as Facts | null;

// ---- properties ------------------------------------------------------------

/** Once hint mode has settled, every visible link in the page, its open shadow roots
 *  and both iframes carries a hint, and hints are unique and prefix-free. */
export const hintsCoverVisibleLinks = always(() => {
  const x = f();
  // Markers are drawn once; links scrolled into view later are not hinted, as in Vimium.
  if (!x || x.mode !== "hints" || x.hintsMs < 600 || x.scrollY !== x.hintsScrollY) return true;
  const frames = Object.values(x.frames || {}).filter((fr) => fr.mode === "hints");
  const all = [x, ...frames];
  if (all.some((fr) => fr.links.some((l) => l.visible && !l.hint))) return false;
  const hints = all.flatMap((fr) => fr.links.filter((l) => l.hint).map((l) => l.hint as string));
  return new Set(hints).size === hints.length && hints.every((h) => hints.every((o) => o === h || !o.startsWith(h)));
});

/** Keys typed into a field reach the field and never trigger a command. */
export const keysNeverFireInFields = always(() => {
  const l = f()?.last;
  if (!l || l.kind !== "type") return true;
  const field = l.after.fields.find((fl) => fl.id === l.field);
  return !!field && field.value.includes(l.seq) && l.after.mode === "normal" && l.after.scrollY === l.before.scrollY
    && l.after.links.every((k) => !k.hint);
});

/** j scrolls down and k up, unless already at that edge. */
export const scrollingMoves = always(() => {
  const l = f()?.last;
  if (!l || l.kind !== "press" || (l.seq !== "j" && l.seq !== "k") || l.before.mode !== "normal") return true;
  // Keys go to a focused field, or to a focused iframe (whose own field may have focus).
  if (l.before.active && /^(INPUT|TEXTAREA|IFRAME|text|area|editable|shadowinput|host|same|cross)$/.test(l.before.active)) return true;
  return l.seq === "j"
    ? l.after.scrollY > l.before.scrollY || l.before.scrollY >= l.before.maxScrollY - 1
    : l.after.scrollY < l.before.scrollY || l.before.scrollY <= 0;
});

// ---- Vomnibar --------------------------------------------------------------
// The vomnibar action (below) opens it with o (or p, tab search), types, then presses Escape or Enter,
// and records what the frame showed (through /__ctl/vomnibar) and the open tabs.

type Vom = { open: boolean; focused: boolean; value: string; for: string | null; scrollbars: string[]; selectedVisible: boolean;
  items: { url: string; type: string; title: string }[] };
type VomLast = { kind: "vomnibar"; key: string; text: string; target: string | null; end: string; openMs: number; opened: Facts;
  typed: Vom; resultsMs: number; moved: Vom | null; mid: Facts; closed: Vom; pageFocused: boolean; before: Facts; after: Facts;
  tabsBefore: { urls: string[]; active: string }; tabsAfter: { urls: string[]; active: string } };
const vl = () => { const l = f()?.last as any; return l && l.kind === "vomnibar" ? l as VomLast : null; };

/** Rows for the typed text show within 150 ms of the last key: ranking stays in the frame, one pass per frame. */
export const vomnibarResultsFast = always(() => { const l = vl(); return !l || l.resultsMs <= 150; });

/** No scrollbar shows in the Vomnibar, and the selected row stays in view as Tab moves it past the tenth. */
export const vomnibarNoScrollbars = always(() => {
  const l = vl();
  return !l || [l.typed, l.moved].every((v) => !v || (v.scrollbars.length === 0 && v.selectedVisible));
});

/** Keys typed right after o, before the frame has loaded, all land in the input and none reach the page. */
export const vomnibarTypeAhead = always(() => {
  const l = f()?.last as any;
  if (!l || l.kind !== "typeAhead") return true;
  return l.typed.value === l.text && l.typed.focused && l.after.pageKeys === l.before.pageKeys && l.after.mode === "normal"
    && l.after.scrollY === l.before.scrollY;
});

/** o opens the Vomnibar, its input focused, within 750 ms. */
export const vomnibarOpensFast = always(() => { const l = vl(); return !l || (l.typed.open && l.openMs <= 750); });

/** Every key typed fast into the Vomnibar lands in its input. */
export const vomnibarKeepsKeystrokes = always(() => { const l = vl(); return !l || l.typed.value === l.text; });

/** While it is open, keys never reach the page: no page keydowns, no scrolling, no field input. */
export const vomnibarKeysStayOut = always(() => {
  const l = vl();
  return !l || (l.mid.pageKeys === l.opened.pageKeys && l.mid.scrollY === l.opened.scrollY && l.mid.fieldKeys === l.opened.fieldKeys);
});

/** Escape closes it and gives focus back to the page and the element that had it. */
export const vomnibarEscapeCloses = always(() => {
  const l = vl();
  return !l || l.end !== "esc" || (!l.closed.open && l.pageFocused && l.after.active === l.before.active);
});

/** An open page's exact title or URL ranks that page first. */
export const vomnibarExactMatchFirst = always(() => {
  const l = vl();
  return !l || !l.target || l.typed.items[0]?.url === l.target;
});

/** Picking a page open in a tab switches to that tab and never opens a second one. */
export const vomnibarNoDuplicateTabs = always(() => {
  const l = vl();
  return !l || l.end !== "enter" || (l.tabsAfter.urls.length === l.tabsBefore.urls.length && l.tabsAfter.active === l.target);
});

/** After /needle<enter>, every match is highlighted and one is current. */
export const findHighlightsMatches = always(() => {
  const l = f()?.last;
  if (!l || l.kind !== "find") return true;
  return l.after.findHighlights === l.after.needles && l.after.findCurrent === 1;
});

// ---- actions ---------------------------------------------------------------

const settle = (ms: number) => new Promise((r) => setTimeout(r, ms));
const ctl = (path: string) => fetch(path).then((r) => r.json());
const probeOf = (w: any) => w.__probeFacts ? w.__probeFacts(false) : null;

const press = registerCustomAction("press", async (_d, window, seq: string) => {
  const w = window as any;
  const before = probeOf(w);
  await ctl(`/__ctl/keys?seq=${encodeURIComponent(seq)}`);
  await settle(450);
  if (w.__probe) w.__probe.last = { kind: "press", seq, before, after: probeOf(w) };
});

const find = registerCustomAction("find", async (document, window) => {
  const w = window as any;
  // Find runs in the focused frame, as in Vimium; search the top page.
  const a = document.activeElement as HTMLElement | null;
  if (a && a.tagName === "IFRAME") a.blur();
  const before = probeOf(w);
  await ctl(`/__ctl/keys?seq=${encodeURIComponent("<esc>/needle<enter>")}`);
  await settle(400);
  if (w.__probe) w.__probe.last = { kind: "find", seq: "needle", before, after: probeOf(w) };
  await ctl(`/__ctl/keys?seq=${encodeURIComponent("<esc>")}`);
});

const typeInto = registerCustomAction("typeInto", async (document, window, id: string, text: string) => {
  const w = window as any;
  const el = id === "shadowinput"
    ? document.getElementById("host")?.shadowRoot?.getElementById(id)
    : document.getElementById(id);
  if (!el) return;
  await ctl(`/__ctl/keys?seq=${encodeURIComponent("<esc>")}`);
  el.scrollIntoView({ block: "center" });
  await settle(100);
  const r = el.getBoundingClientRect();
  await ctl(`/__ctl/click?x=${r.left + r.width / 2}&y=${r.top + r.height / 2}`);
  await settle(100);
  const before = probeOf(w);
  await ctl(`/__ctl/keys?seq=${encodeURIComponent(text)}`);
  await settle(300);
  if (w.__probe) w.__probe.last = { kind: "type", seq: text, field: id, before, after: probeOf(w) };
  await ctl(`/__ctl/keys?seq=${encodeURIComponent("<esc>")}`);
});

// Text to type: the exact title or URL of another open fixture page (the runner opens them
// in background tabs), or text that matches nothing open.
const pages: Record<string, string> = { "/tube.html": "vimium-cljs YouTube-like fixture", "/notes.html": "Vomnibar field notes" };
const vomnibar = registerCustomAction("vomnibar", async (_d, window, key: string, what: string, end: string) => {
  const w = window as any;
  const path = Object.keys(pages).find((p) => p !== w.location.pathname)!;
  const target = what === "title" || what === "url" ? `${w.location.origin}${path}` : null;
  const text = what === "title" ? pages[path] : what === "url" ? `${w.location.host}${path}` : what;
  if (end === "enter" && !target) end = "esc";
  await ctl(`/__ctl/keys?seq=${encodeURIComponent("<esc>")}`);
  await settle(100);
  const tabsBefore = await ctl("/__ctl/tabs");
  const before = probeOf(w);
  const t0 = Date.now();
  await ctl(`/__ctl/keys?seq=${key}`);
  let v = await ctl("/__ctl/vomnibar");
  while (!(v.open && v.focused) && Date.now() - t0 < 3000) { await settle(15); v = await ctl("/__ctl/vomnibar"); }
  const openMs = Date.now() - t0;
  const opened = probeOf(w);
  await ctl(`/__ctl/keys?gap=0&seq=${encodeURIComponent(text)}`);
  const t1 = Date.now();
  let typed = await ctl("/__ctl/vomnibar");
  while (typed.for !== text && Date.now() - t1 < 2000) { await settle(5); typed = await ctl("/__ctl/vomnibar"); }
  const resultsMs = Date.now() - t1;
  await settle(300);
  typed = await ctl("/__ctl/vomnibar");
  // Escape leaves whatever is selected, so move past the tenth row first.
  let moved = null;
  if (end === "esc") {
    await ctl(`/__ctl/keys?gap=0&seq=${"<tab>".repeat(12)}`);
    await settle(150);
    moved = await ctl("/__ctl/vomnibar");
  }
  const mid = probeOf(w);
  await ctl(`/__ctl/keys?seq=${end === "enter" ? "<enter>" : "<esc>"}`);
  await settle(300);
  const closed = await ctl("/__ctl/vomnibar");
  const tabsAfter = await ctl("/__ctl/tabs");
  const pageFocused = w.document.hasFocus();
  if (end === "enter") { await ctl("/__ctl/front"); await settle(200); }
  if (w.__probe) w.__probe.last = { kind: "vomnibar", key, text, target, end, openMs, opened, typed, resultsMs, moved, mid,
    closed, pageFocused, before, after: probeOf(w), tabsBefore, tabsAfter, seq: text };
});

// o is the Vomnibar and p (config.cljs) the tab search, which lists enough tabs to scroll.
const typeAhead = registerCustomAction("typeAhead", async (_d, window, key: string, text: string) => {
  const w = window as any;
  await ctl(`/__ctl/keys?seq=${encodeURIComponent("<esc>")}`);
  await settle(100);
  const before = probeOf(w);
  await ctl(`/__ctl/keys?gap=0&seq=${encodeURIComponent(key + text)}`);
  const t0 = Date.now();
  let typed = await ctl("/__ctl/vomnibar");
  while (!(typed.open && typed.focused && typed.value === text) && Date.now() - t0 < 1500) { await settle(15); typed = await ctl("/__ctl/vomnibar"); }
  const after = probeOf(w);
  await ctl(`/__ctl/keys?seq=${encodeURIComponent("<esc>")}`);
  await settle(250);
  if (w.__probe) w.__probe.last = { kind: "typeAhead", seq: key + text, text, typed, before, after };
});

const vomnibarActions = weighted([
  [3, actions(() => ["title", "url", "zq xv", "fixtrue", "j k gg"].flatMap((what) => ["esc", "enter"].map((end) => vomnibar("o", what, end))))],
  [2, actions(() => ["", "frame", "title"].flatMap((what) => ["esc", "enter"].map((end) => vomnibar("p", what, end))))],
  [2, actions(() => ["notes", "jjkk", "field n"].flatMap((t) => ["o", "p"].map((k) => typeAhead(k, t))))],
]);

const keyActions = weighted([
  [6, actions(() => ["j", "k", "j", "jj", "d", "u", "gg", "G"].map((s) => press(s)))],
  [5, actions(() => [press("f")])],
  [3, actions(() => ["a", "s", "d", "f", "j", "k", "l", "<esc>"].map((s) => press(s)))],
  [2, actions(() => [press("<esc>")])],
  [3, actions(() => [find()])],
  [4, actions(() => ["text", "area", "editable", "shadowinput"].flatMap((id) =>
    ["jkf", "gg/x", "Fd u", "hello j"].map((t) => typeInto(id, t))))],
]);

export const explore = weighted([
  [12, keyActions],
  [4, vomnibarActions],
  [4, clicks],
  [3, scroll],
  [1, back],
  [2, waitOnce],
]);
