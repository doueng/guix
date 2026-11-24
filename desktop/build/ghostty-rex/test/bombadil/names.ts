// Bombadil over the ghostty-rex client (bb verify, spec names): tabs name
// themselves from what runs in them, as Herdr and tmux do, a name given by
// hand stays until cleared, and no Rex ID reaches the chrome (T210). The
// session has two tabs made without labels, so Rex calls them Window and 8
// hex digits, each a fish shell in the test HOME.
import { always, eventually, next, now } from "@antithesishq/bombadil";
import { actions, extract, weighted } from "@antithesishq/bombadil/terminal";

// Alt+Space, the local prefix, as a terminal sends it.
const AS = "\x1b ";
const MINE = "mine";

type View = {
  labels: string[];
  active: number;
  label: string | null;
  chrome: string;
  prompt: boolean;
  wasPrompt: boolean;
  prefix: boolean;
  minibuffer: boolean;
  key: string | null;
  manual: string | null;
};

function typed(a: any): string | null {
  if (!a || !a.TypeText) return null;
  return typeof a.TypeText === "string" ? a.TypeText : (a.TypeText.text ?? null);
}

// What the person named each tab by hand, by tab number, as the actions did.
const manual: Record<number, string> = {};
let lastPrompt = false;
let lastActive = 0;

const view = extract((s): View => {
  const n = s.grid.size.rows;
  const lines: string[] = [];
  for (let i = 0; i < n; i++) lines.push(s.grid.rowText(i));
  const bar = (lines[0] ?? "").split("│").slice(1).join("│");
  const tabs = [...bar.matchAll(/(\[)?(\d+)(?: ([^\]\s]+))?\]?/g)];
  const labels = tabs.map((m) => m[3] ?? "");
  const ai = tabs.findIndex((m) => m[1] === "[");
  const active = ai >= 0 ? ai + 1 : 0;
  const last = (lines[n - 1] ?? "").trimEnd();
  // Alt+Space t r asks: leader/t-r label (string):
  const prompt = /^\S+ label \(string\): /.test(last) || /^\S+ label \(string\):$/.test(last);
  const key = typed(s.lastAction);
  // The rename prompt took the typed name for the tab shown when it opened.
  if (lastPrompt && key !== null && key.endsWith("\r")) {
    const name = key.slice(0, -1);
    if (name === "") delete manual[lastActive]; else manual[lastActive] = name;
  }
  // Title rows are the pane rows that start a tile: ▶ or ─ and a space.
  const titles = lines.slice(1, n - 2).filter((l) => /(^|│)[▶─] \S/.test(l));
  const v: View = {
    labels,
    active,
    label: active ? labels[active - 1] : null,
    chrome: [lines[0] ?? "", ...titles, lines[n - 2] ?? ""].join("\n"),
    prompt,
    wasPrompt: lastPrompt,
    prefix: /^alt\+space( \S+)*-$/.test(last),
    minibuffer: s.cursor.position.row === n - 1 || prompt,
    key,
    manual: manual[active] ?? null,
  };
  lastPrompt = prompt;
  if (!prompt) lastActive = active;
  return v;
});

const v = () => view.current as View;

// No Rex ID in the tab bar, the pane titles or the status line: neither
// Rex's default window label nor a short block or session ID.
export const noRawIds = always(() => !/Window [0-9a-f]{8}|(^|[^0-9a-z])[0-9a-f]{6,}([^0-9a-z]|$)/.test(v().chrome));

// cd renames the tab to the directory's name, unless it was named by hand.
// Each cd has its own property, so it knows the name to expect; a later
// action, which may change the tab or its directory again, ends the wait.
const CDS: Record<string, string> = { "cd /tmp\r": "tmp", "cd /usr\r": "usr", "cd ~/projects\r": "projects", "cd\r": "~" };
const cdRenames = (cmd: string) => always(
  now(() => v().key === cmd && !v().wasPrompt && v().manual === null)
    .implies(eventually(() => v().label === CDS[cmd] || v().manual !== null || (v().key !== null && v().key !== cmd))
      .within(5, "seconds")));
export const cdTmpRenamesTheTab = cdRenames("cd /tmp\r");
export const cdUsrRenamesTheTab = cdRenames("cd /usr\r");
export const cdProjectsRenamesTheTab = cdRenames("cd ~/projects\r");
export const cdHomeRenamesTheTab = cdRenames("cd\r");

// A program names the tab while it runs, and the shell's directory after.
export const aProgramRenamesTheTab = always(
  now(() => v().key === "sleep 3\r" && !v().wasPrompt && v().manual === null && v().label !== "sleep")
    .implies(eventually(() => v().label === "sleep" || v().manual !== null || (v().key !== null && v().key !== "sleep 3\r"))
      .within(3, "seconds")));
export const theShellNamesItAgain = always(
  now(() => v().label === "sleep" && v().manual === null)
    .implies(eventually(() => v().label !== "sleep").within(8, "seconds")));

// A name given by hand shows, and stays while its tab is shown, whatever runs.
export const aGivenNameShows = always(
  now(() => v().manual !== null).implies(eventually(() => v().manual === null || v().label === v().manual).within(3, "seconds")));
export const aGivenNameStays = always(
  now(() => v().manual !== null && v().label === v().manual)
    .implies(next(() => v().manual === null || v().label === v().manual || v().prompt || v().minibuffer)));

// Clearing it (an empty name) names the tab from its pane again.
export const clearingNamesItAgain = always(
  now(() => v().wasPrompt && v().key === "\r")
    .implies(eventually(() => v().label !== MINE || v().manual !== null).within(3, "seconds")));

const key = (k: string) => ({ TypeText: { Regexp: k.replace(/[.\[\]()?*+|^$\\]/g, (c) => "\\" + c) } });

export const normal = weighted([
  [6, actions(() => Object.keys(CDS).map(key)).when(() => v().label !== "sleep")],
  [2, actions(() => [key("sleep 3\r")]).when(() => v().label !== "sleep")],
  [2, actions(() => [key(AS + "t1"), key(AS + "t2")])],
  [2, actions(() => [key(AS + "tr")])],
]).when(() => !v().minibuffer && !v().prefix);

export const inRename = weighted([
  [3, actions(() => [key(MINE + "\r")])],
  [2, actions(() => [key("\r")])],
]).when(() => v().prompt);

export const stuck = weighted([
  [1, actions(() => [key("\x1b"), key("\x07")])],
]).when(() => (v().minibuffer || v().prefix) && !v().prompt);
