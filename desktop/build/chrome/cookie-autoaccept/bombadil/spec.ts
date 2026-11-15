import { always } from "@antithesishq/bombadil";
import { isAction } from "@antithesishq/bombadil/actions";
import { actions, extract, registerCustomAction, weighted } from "@antithesishq/bombadil/browser";
import { back, clicks, forward, reload, scroll, waitOnce } from "@antithesishq/bombadil/browser/defaults/actions";

const BANNER_MS = 15000;
const BLOCKED_MS = 3000;
const PREHIDE_MS = 12000;

type Act = {
  kind: "click" | "decision" | "submit";
  trusted?: boolean;
  label?: string;
  inConsent?: boolean;
  accept?: boolean;
  navigates?: boolean;
  submits?: boolean;
  what?: string;
  by?: "user" | "script";
  frame?: boolean;
};
type Facts = {
  pageId: string;
  path: string;
  host: string;
  settings: { mode: string; disabled: boolean; blocked: boolean } | null;
  ready: boolean;
  ageMs: number;
  bannerVisible: boolean;
  cmpBannerVisible: boolean;
  cmpFrames: number;
  bannerKinds: string[];
  bannerMs: number;
  consentCount: number;
  blocker: string | null;
  blockedMs: number;
  keepHidden: string[];
  keepHiddenMs: number;
  earlyScript: boolean;
  prehideActive: boolean;
  cmp: { name: string; uiBuilt: boolean; stored: string | null } | null;
  cmpBlocked: boolean;
  autoconsentStyles: number;
  marks: string[];
  acts: Act[];
  popup: { op: string; status: string; before: Facts; after: Facts } | null;
};

const facts = extract((s) => {
  const w = s.window as unknown as { __probeFacts?: () => Facts };
  return (w.__probeFacts ? w.__probeFacts() : null) as any;
});
const f = () => facts.current as Facts | null;

const byScript = (x: Facts) =>
  x.acts.filter((a) => (a.kind === "click" && !a.trusted) || (a.kind === "decision" && a.by === "script") || a.kind === "submit");
const accepted = (x: Facts) =>
  byScript(x).some((a) => (a.kind === "click" && a.accept) || (a.kind === "decision" && a.what === "accepted"));
const enabled = (x: Facts | null): x is Facts => !!x && !!x.settings && !x.settings.disabled;

const exempt = (x: Facts) => x.settings!.mode === "reject" && x.bannerKinds.every((k) => k === "pay-wall");

export const bannerGoesAway = always(() => {
  const x = f();
  return !enabled(x) || exempt(x) || x.bannerMs <= BANNER_MS;
});

export const disabledSiteUntouched = always(() => {
  const x = f();
  if (!x || !x.settings || !x.settings.disabled) return true;
  const userDecided = x.acts.some((a) => a.kind === "decision" && a.by === "user");
  const hidden = x.ready && x.consentCount > 0 && !x.bannerVisible && !userDecided;
  return byScript(x).length === 0 && x.marks.length === 0 && x.autoconsentStyles === 0 && !x.earlyScript && !hidden;
});

export const rejectNeverAccepts = always(() => {
  const x = f();
  return !x || !x.settings || x.settings.mode !== "reject" || !accepted(x);
});

export const clicksStayInConsentUi = always(() => {
  const x = f();
  return !x || byScript(x).every((a) => a.kind !== "submit" && (a.kind !== "click" || (!!a.inConsent && !a.navigates && !a.submits)));
});

export const pageStaysUsable = always(() => {
  const x = f();
  return !x || x.blockedMs <= BLOCKED_MS;
});

export const nonConsentUiStays = always(() => {
  const x = f();
  return !x || x.keepHiddenMs <= PREHIDE_MS;
});

export const cmpBannerNeverShows = always(() => {
  const x = f();
  return !enabled(x) || (!x.cmpBannerVisible && x.cmpFrames === 0);
});

export const preansweredCmpBuildsNoUi = always(() => {
  const x = f();
  return !enabled(x) || !x.cmp || !x.cmp.uiBuilt;
});

export const blockingOnlyWhereTurnedOn = always(() => {
  const x = f();
  return !x || !x.settings || (x.cmpBlocked ? x.settings.blocked : true);
});

export const blockingBlocks = always(() => {
  const x = f();
  return !x || !x.settings || !x.settings.blocked || !x.cmp;
});

function reportMatches(status: string, x: Facts): boolean {
  if (!x.settings) return true;
  if (x.settings.disabled) return status === "Off for this site.";
  if (status === "Off for this site." || status === "Not a web page.") return false;
  const acts = byScript(x);
  if (status.startsWith("Accepted cookies")) {
    return x.settings.mode === "accept" && (acts.length > 0 || !x.bannerVisible);
  }
  if (status.startsWith("Rejected cookies")) return x.settings.mode === "reject" && !accepted(x);
  const hid = status.match(/^Hid (\d+) cookie banner/);
  if (hid) return x.marks.filter((m) => m === "hide").length === Number(hid[1]);
  if (status === "No cookie popup seen.") return acts.length === 0 && x.marks.length === 0;
  return true;
}

export const popupTellsTheTruth = always(() => {
  const x = f();
  const p = x?.popup;
  return !p || p.op !== "read" || typeof p.status !== "string" || reportMatches(p.status, p.before) || reportMatches(p.status, p.after);
});

const popup = registerCustomAction("popup", async (_document, window, op: string) => {
  const w = window as unknown as { __probeFacts?: () => Facts; __probe?: { popup: unknown } };
  const before = w.__probeFacts ? w.__probeFacts() : null;
  const r = await fetch(`/__ctl/popup?op=${op}`).then((res) => res.json());
  const after = w.__probeFacts ? w.__probeFacts() : null;
  if (w.__probe && before && after) w.__probe.popup = { op, status: r.status, before, after };
});

const dwell = registerCustomAction("dwell", async (_document, window, ms: number) => {
  await new Promise((r) => window.setTimeout(r, ms));
});
// Bombadil stops a custom action after about 5 s, so a longer stay is several dwells in a row.
const dwells = actions(() => [dwell(2000), dwell(4500)]);

const disabled = () => !!f()?.settings?.disabled;
const popupActions = weighted([
  [6, actions(() => [popup("read")])],
  [1, actions(() => [popup("toggle-site")]).when(() => !disabled())],
  [8, actions(() => [popup("toggle-site")]).when(disabled)],
  [3, actions(() => [popup(f()?.settings?.mode === "reject" ? "accept" : "reject")])],
  [1, actions(() => [popup("toggle-block")]).when(() => !f()?.settings?.blocked)],
  [6, actions(() => [popup("toggle-block")]).when(() => !!f()?.settings?.blocked)],
]);

const isLink = (a: unknown) => isAction(a as any, "Click") && !!(a as any).Click.fingerprint?.href;
const linkClicks = clicks.filter((a) => isLink(a));
const pageClicks = clicks.filter((a) => !isLink(a));
const young = () => (f()?.ageMs ?? 0) < 12000;

export const explore = weighted([
  [25, pageClicks],
  [15, linkClicks],
  [10, scroll],
  [8, back],
  [2, forward],
  [6, reload],
  [10, waitOnce],
  [10, dwells],
  [60, dwells.when(young)],
  [20, popupActions],
]);
