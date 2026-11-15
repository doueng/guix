import { always, eventually, next, not, now } from "@antithesishq/bombadil";
import { actions, extract, weighted } from "@antithesishq/bombadil/terminal";

const ESC = "\x1b";
const CN = "\x0e";
const CCCC = "\x03\x03";
const WAIT = "\x1e";
const CC_GAP = 15;
const WORD = /zq[a-y]{3,5}/g;

type View = {
  worker: boolean;
  inbox: boolean;
  thread: string | null;
  state: string | null;
  follow: string | null;
  atTop: boolean;
  check: boolean;
  status: string | null;
  queued: boolean;
  inComposer: boolean;
  inTranscript: boolean;
  minibuffer: boolean;
  transcript: string[];
  draft: string[];
  flat: string;
  shown: string[];
  echo: string;
  key: string | null;
  prevKey: string | null;
  sinceLeave: number;
  pending: string[];
  sinceSend: number;
  sentSinceCc: boolean;
  sent: string[];
  inboxGlyph: Record<string, { glyph: string; at: number }>;
  index: number;
};

function typed(a: any): string | null {
  if (!a || !a.TypeText) return null;
  return typeof a.TypeText === "string" ? a.TypeText : (a.TypeText.text ?? null);
}

let index = 0;
let sinceSend = 1000;
let sentSinceCc = false;
let lastFlat = "";
let lastInComposer = false;
let lastKey: string | null = null;
let lastTyping = false;
let sinceLeave = 1000;
let pending: string[] = [];
const sent: string[] = [];
const inboxGlyph: Record<string, { glyph: string; at: number }> = {};

const view = extract((s): View => {
  index++;
  const n = s.grid.size.rows;
  const lines: string[] = [];
  for (let i = 0; i < n; i++) lines.push(s.grid.rowText(i));
  const cur = s.cursor.position;
  let h = -1;
  let tag: RegExpMatchArray | null = null;
  for (let i = 0; i < n - 1; i++) {
    const m = lines[i].match(/^ \[(\S+) ([A-Z]) ([FS-])( TOP)?( CHECK)?\]  ?(Ready|Working|Stopped:)/);
    if (m) { h = i; tag = m; }
  }
  const worker = h >= 0 && tag !== null;
  const transcript = worker ? lines.slice(0, h) : [];
  const composer = worker ? lines.slice(h + 1, n - 1).join("\n") : "";
  const inComposer = worker && cur.row > h && cur.row < n - 1;
  const inTranscript = worker && cur.row < h;
  const inbox = !worker && lines.slice(0, 2).some((l) => /need you/.test(l));
  if (inbox) {
    for (let i = 1; i < n - 2; i++) {
      const m = lines[i].slice(0, 45).match(/^.(.) (T\d+)\s/);
      if (m) inboxGlyph[m[2]] = { glyph: m[1], at: index };
    }
  }
  const key = typed(s.lastAction);
  if (key === CN) {
    sinceSend = 0; sentSinceCc = true;
    const went = lastFlat + pending.join("");
    if (lastInComposer && went !== "") sent.push(went);
  } else sinceSend++;
  if (key === CCCC) sentSinceCc = false;
  sinceLeave = key !== null && LEAVING.has(key) ? 0 : sinceLeave + 1;
  const draft = composer.match(WORD) ?? [];
  // A terminal wraps a long line with a \ in its last column.
  const flat = worker ? lines.slice(h + 1, n - 1).map((l) => l.replace(/\\\s*$/, "").replace(/\s+/g, "")).join("") : "";
  if (key !== null && lastTyping && /^zq[a-y]{3,5}$/.test(key)) pending.push(key);
  pending = !worker || cur.row === n - 1 ? [] : pending.filter((w) => !flat.includes(w) && !sent.some((d) => d.includes(w)));
  // After ESC the screen can still show insert state while Emacs is already in normal state.
  const keepsState = key === null || key === " " || key === WAIT || key === CN || /^zq[a-y]{3,5}$/.test(key);
  lastTyping = inComposer && tag !== null && tag[2] === "I" && cur.row !== n - 1 && keepsState;
  lastFlat = flat;
  lastInComposer = inComposer;
  const prevKey = lastKey;
  lastKey = key;
  return {
    worker, inbox,
    thread: tag ? tag[1] : null, state: tag ? tag[2] : null, follow: tag ? tag[3] : null,
    atTop: !!(tag && tag[4]), check: !!(tag && tag[5]), status: tag ? tag[6] : null,
    queued: worker && / \d+ queued/.test(lines[h]),
    inComposer, inTranscript, minibuffer: cur.row === n - 1,
    transcript, draft, flat, shown: transcript.join("\n").match(WORD) ?? [],
    echo: lines[n - 1].trim(), key, prevKey, sinceLeave, pending: pending.slice(), sinceSend, sentSinceCc, sent: sent.slice(),
    inboxGlyph: { ...inboxGlyph }, index,
  };
});

const v = () => view.current as View;

export const workerChecksHold = always(() => !v().check);

export const cnSendsFromComposer = always(() => {
  const x = v();
  const draft = x.draft.slice();
  const thread = x.thread;
  return now(() => x.inComposer && draft.length > 0 && !x.minibuffer)
    .and(next(() => v().key === CN))
    .implies(eventually(() => {
      const y = v();
      return !y.worker || y.thread !== thread || draft.every((w) => !y.flat.includes(w)) ||
        /Router: |Let the turn finish|finished|Starting agent, please wait/.test(y.echo);
    }).within(10, "seconds"));
});

export const ccNeverSends = always(() => {
  const x = v();
  const draft = x.draft.slice();
  const thread = x.thread;
  const length = x.flat.length;
  return now(() => x.inComposer && draft.length > 0 && x.sinceSend > CC_GAP)
    .and(next(() => v().key === CCCC))
    .implies(not(eventually(() => {
      const y = v();
      return y.worker && y.thread === thread && !y.sentSinceCc && y.flat.length < length &&
        draft.every((w) => !y.flat.includes(w));
    }).within(3, "seconds")));
});

export const typedWordLandsInComposer = always(
  now(() => v().pending.length > 0).implies(eventually(() => v().pending.length === 0).within(1, "seconds")));

export const transcriptShowsOnlySentWords = always(() =>
  v().shown.every((w) => v().sent.some((s) => s.includes(w.slice(0, 5)))));

export const workingEnds = always(
  now(() => v().worker && v().status === "Working" && !v().queued)
    .implies(eventually(() => !v().worker || v().status !== "Working" || v().queued).within(90, "seconds")));

export const noRepeatedError = always(() => {
  const ls = v().transcript.map((l) => l.trim()).filter((l) => l !== "");
  return ls.every((l, i) => i === 0 || !l.startsWith("Error: ") || l !== ls[i - 1]);
});

export const doneThreadIsNotWorking = always(() => {
  const x = v();
  const seen = x.thread ? x.inboxGlyph[x.thread] : undefined;
  return !(x.worker && x.status === "Working" && seen && seen.glyph === "✓" && x.index - seen.at > 40);
});

const LEAVING = new Set(["\x03i", "\x03r"]);
export const composerStays = always(() => {
  const x = v();
  return now(() => x.worker && !x.minibuffer).implies(next(() => {
    const y = v();
    return y.worker || y.minibuffer || y.sinceLeave <= 4;
  }));
});

export const noBareRefusal = always(() => !/bbh super: (\S+) \1\b/.test(v().echo));

function overscrolled(y: View): boolean {
  if (!y.worker || !y.inTranscript || y.atTop) return false;
  let blank = 0;
  for (let i = y.transcript.length - 1; i >= 0 && y.transcript[i].trim() === ""; i--) blank++;
  return blank > 2 && blank < y.transcript.length;
}
export const bottomHasNoOverscroll = always(
  now(() => v().key === "G" && overscrolled(v())).implies(eventually(() => !overscrolled(v())).within(1, "seconds")));

function followingHalfBlank(y: View): boolean {
  if (!y.worker || y.follow !== "F" || y.atTop) return false;
  let blank = 0;
  for (let i = y.transcript.length - 1; i >= 0 && y.transcript[i].trim() === ""; i--) blank++;
  return blank > 2 && blank < y.transcript.length;
}
export const followingShowsLatest = always(
  now(() => followingHalfBlank(v())).implies(eventually(() => !followingHalfBlank(v())).within(1, "seconds")));

// Never RET in the Worker view, q, x, d, :, C-x or C-c C-k: they leave, delete or stop.

const key = (k: string) => ({ TypeText: { Regexp: k.replace(/[.\[\]]/g, (c) => "\\" + c) } });
const word = { TypeText: { Regexp: "zq[a-y]{3,5}" } };

export const inComposerNormal = weighted([
  [3, actions(() => [key("i"), key("a")])],
  [3, actions(() => [key(CN)])],
  [1, actions(() => [key(CCCC)]).when(() => v().sinceSend > CC_GAP)],
  [2, actions(() => [key("\x15"), key("\x04")])],
  [1, actions(() => [key(ESC + "k"), key(ESC + "j")])],
  [6, actions(() => [key("\x17k")])],
  [2, actions(() => [key(WAIT)])],
  [1, actions(() => [key("\x03i")])],
  [1, actions(() => [key("\x03r")])],
]).when(() => v().inComposer && v().state === "N" && !v().minibuffer);

export const inComposerInsert = weighted([
  [6, actions(() => [word])],
  [1, actions(() => [key(" ")])],
  [2, actions(() => [key(ESC)])],
  [3, actions(() => [key(CN)])],
  [1, actions(() => [key(CCCC)]).when(() => v().sinceSend > CC_GAP)],
  [1, actions(() => [key(WAIT)])],
]).when(() => v().inComposer && v().state === "I" && !v().minibuffer);

export const inTranscript = weighted([
  [5, actions(() => [key("j"), key("k")])],
  [3, actions(() => [key("\x15"), key("\x04")])],
  [2, actions(() => [key("\x06"), key("\x02")])],
  [2, actions(() => [key("\x1b[5~"), key("\x1b[6~")])],
  [1, actions(() => [key("gg")])],
  [2, actions(() => [key("G")])],
  [1, actions(() => [key("\x17j")])],
  [1, actions(() => [key("i")])],
  [2, actions(() => [key(WAIT)])],
  [1, actions(() => [key("\x03i")])],
]).when(() => v().inTranscript && v().state === "N" && !v().minibuffer);

export const inTranscriptInsert = weighted([
  [2, actions(() => [word])],
  [3, actions(() => [key(ESC)])],
]).when(() => v().inTranscript && v().state !== "N" && !v().minibuffer);

export const inInbox = weighted([
  [4, actions(() => [key("j"), key("k")])],
  [3, actions(() => [key("\r")])],
  [1, actions(() => [key(WAIT)])],
  [1, actions(() => [key(ESC)])],
]).when(() => v().inbox && !v().minibuffer);

export const elsewhere = weighted([
  [3, actions(() => [key(WAIT)])],
  [1, actions(() => [key(ESC)])],
  [1, actions(() => [key("\x03i")])],
]).when(() => !v().worker && !v().inbox && !v().minibuffer);

export const quitPrompt = actions(() => [key("\x07")]).when(() => v().minibuffer);
