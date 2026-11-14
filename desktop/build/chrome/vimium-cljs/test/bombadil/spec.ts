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
  if (l.before.active && /^(INPUT|TEXTAREA|text|area|editable|shadowinput|host)$/.test(l.before.active)) return true;
  return l.seq === "j"
    ? l.after.scrollY > l.before.scrollY || l.before.scrollY >= l.before.maxScrollY - 1
    : l.after.scrollY < l.before.scrollY || l.before.scrollY <= 0;
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
const probeOf = (w: any) => w.__probeFacts ? w.__probeFacts() : null;

const press = registerCustomAction("press", async (_d, window, seq: string) => {
  const w = window as any;
  const before = probeOf(w);
  await ctl(`/__ctl/keys?seq=${encodeURIComponent(seq)}`);
  await settle(450);
  if (w.__probe) w.__probe.last = { kind: "press", seq, before, after: probeOf(w) };
});

const find = registerCustomAction("find", async (_d, window) => {
  const w = window as any;
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
  [4, clicks],
  [3, scroll],
  [1, back],
  [2, waitOnce],
]);
