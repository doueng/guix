// Google Docs, as test/fixtures/docs.html reproduces it: keys go to a contenteditable in a 1px iframe.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { launch, available, sleep, evaluate } from '../lib/chrome.mjs';
import { startServer, controller } from '../lib/server.mjs';

const skip = available ? false : 'no headless Chrome (install the com.google.Chrome flatpak or set CHROME)';
const T = { skip, timeout: 40000 };

let b, srv, port, page;
const ctl = () => controller(b.cdp, async () => page, b.extensionId);
const docs = () => evaluate(b.cdp, page, 'window.__docsFacts()');
const keys = async (seq, wait = 300) => { await ctl()('keys', { seq }); await sleep(wait); };
async function until(pred, ms = 4000) {
  const t0 = Date.now();
  let f;
  while (Date.now() - t0 < ms) {
    try { f = await docs(); if (pred(f)) return f; } catch {}
    await sleep(100);
  }
  return f;
}
async function open() {
  const { targetId } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/docs.html` });
  page = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
  await b.cdp('Page.enable', {}, page);
  await b.cdp('Page.bringToFront', {}, page);
  const ready = await until((f) => f, 8000);
  assert.ok(ready, 'fixture loaded');
  await sleep(400);
}
async function clickOn(sel) {
  const r = await evaluate(b.cdp, page, `(() => { const r = document.querySelector('${sel}').getBoundingClientRect(); return [r.left + r.width / 2, Math.min(r.top + r.height / 2, 400)]; })()`);
  await ctl()('click', { x: r[0], y: r[1] });
  await sleep(200);
}
const hintOf = (f, id) => (f.hints.find(([i]) => i === id) || [])[1];

before(async () => {
  if (skip) return;
  b = await launch();
  ({ port, server: srv } = await startServer((op, q) => ctl()(op, q)));
});
after(() => { b?.close(); srv?.closeAllConnections(); srv?.close(); setTimeout(() => process.exit(process.exitCode ?? 0), 2000).unref(); });

test('Docs: the editor keeps the focus Docs gives it, and typing reaches it', T, async () => {
  await open();
  let f = await docs();
  assert.ok(f.typingFocus, 'grab-back-focus left the key catcher alone');
  await keys('hello jkf gg?');
  f = await docs();
  assert.equal(f.text, 'hello jkf gg?');
  assert.equal(f.editorTop, 0, 'j did not scroll');
  assert.deepEqual(f.hints, [], 'f did not hint');
  await clickOn('#editor');
  await keys('h');
  assert.equal((await docs()).text, 'hello jkf gg?h', 'h after a click types, not goBack');
});

test('Docs: Escape stops typing; j scrolls the document, q types nothing, chords reach Docs, i types again', T, async () => {
  await open();
  await keys('<esc>');
  let f = await docs();
  assert.ok(f.suspended, 'suspended');
  assert.ok(f.typingFocus, 'the editor keeps focus');
  assert.equal(f.escapes, 0, 'that Escape was ours');
  await keys('jj', 600);
  f = await docs();
  assert.ok(f.editorTop > 0, `j scrolled the editor div (${f.editorTop})`);
  const top = f.editorTop;
  await keys('q<bs> ;');
  f = await docs();
  assert.equal(f.text, '', 'no text in normal mode');
  await keys('<c-b>');
  assert.deepEqual((await docs()).actions, ['ctrl-b'], 'Ctrl+B reached Docs');
  await keys('<esc>');
  assert.equal((await docs()).escapes, 1, 'a second Escape goes to Docs');
  await keys('k', 600);
  assert.ok((await docs()).editorTop < top, 'k scrolled back');
  await keys('iabc');
  f = await docs();
  assert.equal(f.text, 'abc');
  assert.ok(!f.suspended);
});

test('Docs: a click in the document types again', T, async () => {
  await open();
  await keys('<esc>q');
  assert.equal((await docs()).text, '');
  await clickOn('#editor');
  await keys('xy');
  assert.equal((await docs()).text, 'xy');
});

test('Docs: hints open a menu and pick its item; Docs refocusing its editor keeps normal mode', T, async () => {
  await open();
  await keys('<esc>f', 700);
  let f = await until((f) => hintOf(f, 'm-format'));
  assert.ok(hintOf(f, 'b-bold'), 'toolbar button hinted');
  await keys(hintOf(f, 'm-format'), 500);
  f = await docs();
  assert.deepEqual(f.menuOpen, ['menu-format']);
  await keys('f', 700);
  f = await until((f) => hintOf(f, 'i-bold'));
  await keys(hintOf(f, 'i-bold'), 500);
  f = await docs();
  assert.deepEqual(f.actions, ['i-bold']);
  assert.deepEqual(f.menuOpen, []);
  assert.ok(f.typingFocus, 'Docs gave the editor its focus back');
  await keys('q');
  assert.equal((await docs()).text, '', 'still normal mode: Docs refocusing is not the user asking to type');
  await keys('iok');
  assert.equal((await docs()).text, 'ok', 'i types');
});

test('Docs: Escape out of hints over the editor lands in normal mode', T, async () => {
  await open();
  await keys('<esc>f', 700);
  await until((f) => f.hints.length > 0);
  await keys('<esc>');
  const f = await until((f) => f.hints.length === 0 && f.frameMode === 'normal');
  assert.equal(f.frameMode, 'normal');
  assert.ok(f.suspended, 'still not typing');
  await keys('j', 500);
  assert.ok((await docs()).editorTop > 0, 'j scrolls');
  assert.equal((await docs()).text, '');
});

test('Docs: the Vomnibar opens over the page from the editor and Escape gives the keyboard back', T, async () => {
  await open();
  await keys('<esc>o', 300);
  let v;
  for (let i = 0; i < 30 && !(v && v.open && v.focused); i++) { await sleep(100); v = await ctl()('vomnibar'); }
  assert.ok(v.open && v.focused, 'Vomnibar open and focused');
  await keys('<esc>', 600);
  v = await ctl()('vomnibar');
  assert.ok(!v.open, 'closed');
  await keys('q');
  assert.equal((await docs()).text, '', 'still normal mode after the Vomnibar');
});
