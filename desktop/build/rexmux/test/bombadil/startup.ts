// Bombadil over `rexmux tty` started on an empty Rex server with the shipped
// config (bb test:bombadil --spec startup): the :startup session's tabs and
// tiny fingers. Stubs stand in for emacs-bbh, pi and jjui; each prints
// "STUB <name>" and a line with a URL and a path, as the shell tab's fish does.
import { always, eventually, next, now } from "@antithesishq/bombadil";
import { actions, extract, weighted } from "@antithesishq/bombadil/terminal";

const ESC = "\x1b";
// Alt+Space, the local prefix, as a terminal sends it.
const AS = "\x1b ";
const STARTUP = ["shell", "bbh", "pi", "jjui"];
const PROGRAM: Record<string, string> = { bbh: "emacs-bbh", pi: "pi", jjui: "jjui" };
// hints.rs ALPHABET: with at most 26 targets, target i has letter i.
const ALPHABET = "asdfqwerzxcvjklmiuopghtybn";

type View = {
  labels: string[];
  active: string | null;
  text: string;
  last: string;
  hints: number;
  wasHints: number;
  key: string | null;
  allSeen: boolean;
  sidecarDown: boolean;
};

// Tab 1 runs no program: rexmux shows its label, ghostty-rex its directory.
function appeared(labels: string[]): boolean {
  return labels.length === STARTUP.length && (labels[0] === "shell" || labels[0] === "~") &&
    labels.slice(1).join(",") === STARTUP.slice(1).join(",");
}

function stays(labels: string[]): boolean {
  return labels.length === STARTUP.length && labels.every((l, i) => l === STARTUP[i] || l === "~");
}

function typed(a: any): string | null {
  if (!a || !a.TypeText) return null;
  return typeof a.TypeText === "string" ? a.TypeText : (a.TypeText.text ?? null);
}

let lastHints = 0;
let allSeen = false;

const view = extract((s): View => {
  const n = s.grid.size.rows;
  const lines: string[] = [];
  for (let i = 0; i < n; i++) lines.push(s.grid.rowText(i));
  const bar = (lines[0] ?? "").split("│").slice(1).join("│");
  const tabs = [...bar.matchAll(/(\[)?(\d+) ([^\]\s]+)\]?/g)];
  const labels = tabs.map((m) => m[3]);
  const active = tabs.find((m) => m[1] === "[")?.[3] ?? null;
  const last = (lines[n - 1] ?? "").trimEnd();
  const hm = last.match(/^(\d+) hints: type one/);
  const hints = hm ? Number(hm[1]) : 0;
  if (appeared(labels)) allSeen = true;
  const v: View = {
    labels,
    active,
    text: lines.slice(1, n - 2).join("\n"),
    last,
    hints,
    wasHints: lastHints,
    key: typed(s.lastAction),
    allSeen,
    sidecarDown: /sidecar down/.test(lines[n - 2] ?? ""),
  };
  lastHints = hints;
  return v;
});

const v = () => view.current as View;

// Startup: an empty server gets the four tabs, in Herdr's order.
export const startupTabsAppear = eventually(() => appeared(v().labels)).within(30, "seconds");

// Once there, they stay: nothing the client does drops or reorders them.
// ghostty-rex names a tab by its pane while no startup program runs in it
// (T210): ~, the test HOME, for the shell tab, and for a tab a revive is
// still bringing back.
export const startupTabsStay = always(() => !v().allSeen || stays(v().labels));

// Each program tab shows its program running.
const runs = (tab: string) => () => v().active !== tab || v().hints > 0 || v().text.includes("STUB " + PROGRAM[tab]);
export const bbhRunsEmacsBbh = always(now(() => v().active === "bbh").implies(eventually(runs("bbh")).within(20, "seconds")));
export const piRunsPi = always(now(() => v().active === "pi").implies(eventually(runs("pi")).within(20, "seconds")));
export const jjuiRunsJjui = always(now(() => v().active === "jjui").implies(eventually(runs("jjui")).within(20, "seconds")));

// Tiny fingers: Alt+Space s l on a pane showing a URL labels at least it.
export const tinyFingersLabelsTargets = always(
  now(() => v().key === AS + "sl" && v().wasHints === 0 && /https:\/\/example\.com\/\S+/.test(v().text))
    .implies(eventually(() => v().hints > 0).within(3, "seconds")));

// A label picks its target: lowercase copies, Shift pastes, and hints close.
const picked = (k: string | null) => k !== null && k.length === 1 && ALPHABET.indexOf(k.toLowerCase()) >= 0 &&
  ALPHABET.indexOf(k.toLowerCase()) < v().wasHints && v().wasHints <= ALPHABET.length;
export const aLabelCopies = always(
  now(() => v().wasHints > 0 && picked(v().key) && v().key === v().key!.toLowerCase())
    .implies(() => v().hints === 0 && v().last.startsWith("Copied: ")));
export const shiftLabelPastes = always(
  now(() => v().wasHints > 0 && picked(v().key) && v().key !== v().key!.toLowerCase())
    .implies(() => v().hints === 0 && v().last.startsWith("Pasted: ")));

// Escape always leaves tiny fingers.
export const escapeLeavesTinyFingers = always(() => !(v().wasHints > 0 && v().key === ESC) || v().hints === 0);

export const sidecarStaysUp = always(() => !v().sidecarDown);

// No tab shows a shell command the client typed to start fish or a program
// in it (T210): restored tabs get them as Rex block commands instead.
export const noTypedExecLine = always(() => !/exec \S*fish -l/.test(v().text));

const key = (k: string) => ({ TypeText: { Regexp: k.replace(/[.\[\]()?*+|^$\\]/g, (c) => "\\" + c) } });

export const normal = weighted([
  [4, actions(() => [key(AS + "t1"), key(AS + "t2"), key(AS + "t3"), key(AS + "t4")])],
  [2, actions(() => [key(AS + "tl"), key(AS + "th")])],
  [4, actions(() => [key(AS + "sl")])],
]).when(() => v().hints === 0);

export const inHints = weighted([
  [4, actions(() => [...ALPHABET.slice(0, Math.min(v().hints, ALPHABET.length))].map((c) => key(c)))],
  [2, actions(() => [...ALPHABET.slice(0, Math.min(v().hints, ALPHABET.length))].map((c) => key(c.toUpperCase())))],
  [2, actions(() => [key(ESC), key("\x7f")])],
]).when(() => v().hints > 0);
