// Properties of the bbh Worker view, the Router's and Workers' transcript
// above their composer, as drawn in a 140x40 terminal with no menu bar.
// bb test:bombadil --view worker runs it; see test/bbh/bombadil.clj and
// test/bombadil/worker.el, whose instruments this spec reads.
//
// The screen: the transcript window from row 0, then the composer, whose
// status line worker.el tags as ` [T3 N F] Ready · ...`: the thread (or
// Router), the evil state of the selected window, and whether the transcript
// follows its end (F) or is scrolled up (S); TOP when it shows its first
// line.  CHECK in the tag means one of
// worker.el's checks broke: a step over its time budget, a window that
// output or scrolling left wrong, a repeated error or a shell stuck busy.
// The last row is the echo area.  Words typed here are zq-words, which no
// generated or anonymized text contains.  The Inbox, reached with C-c i,
// shows the thread list on the left as in inbox.ts.
import { always, eventually, next, not, now } from "@antithesishq/bombadil";
import { actions, extract, weighted } from "@antithesishq/bombadil/terminal";

const ESC = "\x1b";
const CN = "\x0e";
const CCCC = "\x03\x03";
const WAIT = "\x1e"; // C-^, which worker.el binds to ignore
const CC_GAP = 15;
const WORD = /zq[a-y]{3,5}/g;

type View = {
  worker: boolean; // a transcript above a composer is on screen
  inbox: boolean;
  thread: string | null; // the composer's thread, or Router
  state: string | null; // evil state of the selected window: N, I, ...
  follow: string | null; // F follows the end, S scrolled up
  atTop: boolean; // the transcript window starts at its first line
  check: boolean;
  status: string | null; // Ready, Working or Stopped
  queued: boolean;
  inComposer: boolean;
  inTranscript: boolean;
  minibuffer: boolean;
  transcript: string[];
  draft: string[]; // zq-words in the composer
  flat: string; // the composer's text, rows joined without wrapping or spaces
  shown: string[]; // zq-words in the transcript
  echo: string;
  key: string | null;
  prevKey: string | null; // the key before, since a slow redraw lands a state late
  sinceLeave: number; // actions since a key that leaves the view
  pending: string[]; // zq-words typed in the composer in insert state, not shown in it yet
  sinceSend: number;
  sentSinceCc: boolean;
  sent: string[]; // the flat drafts a send key went in on
  inboxGlyph: Record<string, { glyph: string; at: number }>;
  index: number;
};

function typed(a: any): string | null {
  if (!a || !a.TypeText) return null;
  return typeof a.TypeText === "string" ? a.TypeText : (a.TypeText.text ?? null);
}

// Counters extract carries from state to state.
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
    // Words typed but not drawn yet went too: the screen can lag a keystroke.
    const went = lastFlat + pending.join("");
    if (lastInComposer && went !== "") sent.push(went);
  } else sinceSend++;
  if (key === CCCC) sentSinceCc = false;
  sinceLeave = key !== null && LEAVING.has(key) ? 0 : sinceLeave + 1;
  const draft = composer.match(WORD) ?? [];
  // A terminal wraps a long line with a \ in its last column.
  const flat = worker ? lines.slice(h + 1, n - 1).map((l) => l.replace(/\\\s*$/, "").replace(/\s+/g, "")).join("") : "";
  if (key !== null && lastTyping && /^zq[a-y]{3,5}$/.test(key)) pending.push(key);
  // A word is done once it shows in the composer or went in a send, or the view left.
  pending = !worker || cur.row === n - 1 ? [] : pending.filter((w) => !flat.includes(w) && !sent.some((d) => d.includes(w)));
  // Only after a key that keeps the state: after ESC the screen can still
  // show insert state for a moment while Emacs is already in normal state.
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

// ---- properties --------------------------------------------------------

// worker.el's checks hold: no step over its time budget, no window that
// output or scrolling left with point unseen, blank rows below the end, or
// a scrolled-up view moved; no error shown twice since a prompt; no shell
// busy with nothing in flight.  checks.log says which and when.
export const workerChecksHold = always(() => !v().check);

// C-n sends the draft from the composer in normal and insert state: it
// leaves the composer within a few seconds, or the echo area says why not:
// a turn still running, or a restarted session still starting.
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

// C-c C-c never sends: the draft stays unless a send key comes after.  A
// draft that went left the composer shorter; typing inside a word only
// splits it, as vim's i after ESC does.
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

// Typed text lands in the composer: a word typed in insert state there
// shows in it within a second, unless it was sent or the view left first.
// Not at once, since a slow keystroke's redraw lands a state late.
export const typedWordLandsInComposer = always(
  now(() => v().pending.length > 0).implies(eventually(() => v().pending.length === 0).within(1, "seconds")));

// The transcript is read-only: a zq-word shows in it only after it was sent.
export const transcriptShowsOnlySentWords = always(() =>
  v().shown.every((w) => v().sent.some((s) => s.includes(w.slice(0, 5)))));

// The busy flag clears: Working ends within a minute and a half of steady
// churn, since a turn here lasts seconds (T163).
export const workingEnds = always(
  now(() => v().worker && v().status === "Working" && !v().queued)
    .implies(eventually(() => !v().worker || v().status !== "Working" || v().queued).within(90, "seconds")));

// A retry repeats neither its prompt nor its error (T162): the transcript
// never shows the same error on two lines in a row.
export const noRepeatedError = always(() => {
  const ls = v().transcript.map((l) => l.trim()).filter((l) => l !== "");
  return ls.every((l, i) => i === 0 || !l.startsWith("Error: ") || l !== ls[i - 1]);
});

// The Inbox and the Worker view agree: a thread the Inbox showed done
// (✓) at least five seconds ago is not Working in its Worker view, since
// a finished Worker's session is shut down.
export const doneThreadIsNotWorking = always(() => {
  const x = v();
  const seen = x.thread ? x.inboxGlyph[x.thread] : undefined;
  return !(x.worker && x.status === "Working" && seen && seen.glyph === "✓" && x.index - seen.at > 40);
});

// The Worker view keeps its composer, through session restarts too: it
// goes only on a key that leaves the view.  Emacs can run a few keys behind
// the screen, so the leaving key may be a few actions back.
const LEAVING = new Set(["\x03i", "\x03r"]);
export const composerStays = always(() => {
  const x = v();
  return now(() => x.worker && !x.minibuffer).implies(next(() => {
    const y = v();
    return y.worker || y.minibuffer || y.sinceLeave <= 4;
  }));
});

// Nothing the view sends gets a refusal the person cannot read, such as
// `Router: bbh super: invalid invalid'.
export const noBareRefusal = always(() => !/bbh super: (\S+) \1\b/.test(v().echo));

// After G the transcript ends on its bottom rows: at most two blank rows
// (the final empty line and one separator) below the last text, within a
// second, since a sample can land mid-redraw while output streams in.
// worker.el checks the layout itself after every command.
function overscrolled(y: View): boolean {
  if (!y.worker || !y.inTranscript || y.atTop) return false;
  let blank = 0;
  for (let i = y.transcript.length - 1; i >= 0 && y.transcript[i].trim() === ""; i--) blank++;
  return blank > 2 && blank < y.transcript.length;
}
export const bottomHasNoOverscroll = always(
  now(() => v().key === "G" && overscrolled(v())).implies(eventually(() => !overscrolled(v())).within(1, "seconds")));

// ---- actions -------------------------------------------------------------
// Never RET in the Worker view, q, x, d, :, C-x or C-c C-k: they leave,
// delete or stop.  WAIT does nothing, to let output arrive.

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

// Insert state on the read-only transcript, should anything enter it
// (worker.el checks it never does): typing there must not land.
export const inTranscriptInsert = weighted([
  [2, actions(() => [word])],
  [3, actions(() => [key(ESC)])],
]).when(() => v().inTranscript && v().state !== "N" && !v().minibuffer);

// In the Inbox, move and open a thread's Worker view.
export const inInbox = weighted([
  [4, actions(() => [key("j"), key("k")])],
  [3, actions(() => [key("\r")])],
  [1, actions(() => [key(WAIT)])],
  [1, actions(() => [key(ESC)])], // out of any state a stray key left
]).when(() => v().inbox && !v().minibuffer);

// Before the first Worker shows, or on any other screen, wait or go back.
export const elsewhere = weighted([
  [3, actions(() => [key(WAIT)])],
  [1, actions(() => [key(ESC)])],
  [1, actions(() => [key("\x03i")])],
]).when(() => !v().worker && !v().inbox && !v().minibuffer);

// A prompt, such as one a stray key opened, is quit with C-g.
export const quitPrompt = actions(() => [key("\x07")]).when(() => v().minibuffer);
