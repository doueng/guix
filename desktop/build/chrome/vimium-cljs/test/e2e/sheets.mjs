// Google Sheets, as test/fixtures/sheets.html reproduces it: keys go to a cell editor kept above the page.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { launch, available, sleep, evaluate } from '../lib/chrome.mjs';
import { startServer, controller } from '../lib/server.mjs';

const skip = available ? false : 'no headless Chrome (install the com.google.Chrome flatpak or set CHROME)';
const T = { skip, timeout: 40000 };

let b, srv, port, page;
const ctl = () => controller(b.cdp, async () => page, b.extensionId);
const sheet = () => evaluate(b.cdp, page, 'window.__sheetFacts()');
const keys = async (seq, wait = 300) => { await ctl()('keys', { seq }); await sleep(wait); };
async function until(pred, ms = 4000) {
  const t0 = Date.now();
  let f;
  while (Date.now() - t0 < ms) {
    try { f = await sheet(); if (pred(f)) return f; } catch {}
    await sleep(100);
  }
  return f;
}
async function open() {
  const { targetId } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/sheets.html` });
  page = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
  await b.cdp('Page.enable', {}, page);
  await b.cdp('Page.bringToFront', {}, page);
  assert.ok(await until((f) => f, 8000), 'fixture loaded');
  await sleep(400);
}
async function clickOn(sel, dy = 0) {
  const r = await evaluate(b.cdp, page, `(() => { const r = document.querySelector('${sel}').getBoundingClientRect(); return [r.left + 30, r.top + 10 + ${dy}]; })()`);
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

test('Sheets: the cell editor keeps its focus, and typing fills cells', T, async () => {
  await open();
  let f = await sheet();
  assert.ok(f.editorFocused, 'grab-back-focus left the cell editor alone');
  await keys('hjkl fG<enter>gg?<enter>');
  f = await sheet();
  assert.deepEqual(f.cells, { A1: 'hjkl fG', A2: 'gg?' });
  assert.equal(f.top, 0, 'nothing scrolled');
  assert.deepEqual(f.hints, [], 'f did not hint');
  assert.equal(f.active, 'A3');
});

test('Sheets: Escape stops typing; j scrolls the grid, q edits nothing, arrows move, i types again', T, async () => {
  await open();
  await keys('<esc>');
  let f = await sheet();
  assert.ok(f.suspended && f.editorFocused, 'normal mode, editor still focused');
  assert.equal(f.escapes, 0, 'that Escape was ours');
  await keys('jj', 600);
  f = await sheet();
  assert.ok(f.top > 0, `j scrolled the grid through its scrollbar (${f.top})`);
  await keys('q<bs> ;');
  f = await sheet();
  assert.equal(f.editing, null, 'no cell edit in normal mode');
  assert.deepEqual(f.cells, {});
  await keys('<right>');
  assert.equal((await sheet()).active, 'B1', 'arrows reach Sheets');
  await keys('<c-b>');
  assert.deepEqual((await sheet()).actions, ['ctrl-b'], 'Ctrl+B reached Sheets');
  await keys('iok<enter>');
  f = await sheet();
  assert.deepEqual(f.cells, { B1: 'ok' });
  assert.ok(!f.suspended);
});

test('Sheets: Escape while editing a cell is the Sheet\'s; a second one gives normal mode', T, async () => {
  await open();
  await keys('ab');
  let f = await sheet();
  assert.equal(f.editing, 'A1');
  assert.ok(f.editorShown, 'the editor moved over the cell');
  await keys('<esc>');
  f = await sheet();
  assert.equal(f.editing, null, 'Sheets cancelled the edit');
  assert.deepEqual(f.cells, {});
  assert.equal(f.escapes, 1);
  assert.ok(!f.suspended, 'still typing');
  await keys('<esc>j', 500);
  f = await sheet();
  assert.ok(f.suspended && f.top > 0, 'the second Escape gave normal mode');
});

test('Sheets: the formula bar types, and its Escape gives the keyboard back to the grid', T, async () => {
  await open();
  await keys('<esc>');
  await clickOn('#formula');
  await keys('=jk');
  let f = await sheet();
  assert.ok(f.formulaFocused && f.formula === '=jk', 'typed into the formula bar');
  assert.equal(f.top, 0);
  assert.ok(!f.suspended, 'a click types again');
  await keys('<esc>');
  f = await sheet();
  assert.ok(f.editorFocused, 'Sheets took the keyboard back to the cell editor');
  assert.equal(f.escapes, 1, 'the Escape was Sheets\'');
  await keys('x<enter>');
  assert.deepEqual((await sheet()).cells, { A1: 'x' }, 'typing goes on in the grid');
});

test('Sheets: hints reach menus, the toolbar and sheet tabs; a click on a cell types again', T, async () => {
  await open();
  await keys('<esc>f', 700);
  let f = await until((f) => hintOf(f, 'docs-file-menu'));
  assert.ok(hintOf(f, 'b-bold'), 'toolbar button hinted');
  assert.ok(hintOf(f, 'tab-2'), 'sheet tab hinted');
  assert.ok(!hintOf(f, 'waffle-rich-text-editor'), 'the hidden cell editor is not hinted');
  await keys(hintOf(f, 'tab-2'), 500);
  f = await sheet();
  assert.equal(f.tab, 'tab-2');
  assert.ok(f.editorFocused && f.suspended, 'Sheets refocusing its editor keeps normal mode');
  await keys('f', 700);
  f = await until((f) => hintOf(f, 'docs-file-menu'));
  await keys(hintOf(f, 'docs-file-menu'), 500);
  assert.deepEqual((await sheet()).menuOpen, ['menu-file']);
  await keys('<esc>');
  await clickOn('#waffle-grid-container', 42);
  await keys('z<enter>');
  f = await sheet();
  assert.deepEqual(f.cells, { A3: 'z' }, 'the click selected A3 and typing filled it');
});
