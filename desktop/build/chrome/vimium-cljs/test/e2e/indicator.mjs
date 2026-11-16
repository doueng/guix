// The mode badge on the Docs and Sheets fixtures: it names the mode keys are in, after each step.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { writeFileSync, mkdirSync } from 'node:fs';
import { launch, available, sleep, evaluate } from '../lib/chrome.mjs';
import { startServer, controller } from '../lib/server.mjs';

const skip = available ? false : 'no headless Chrome (install the com.google.Chrome flatpak or set CHROME)';
const T = { skip, timeout: 60000 };
const shots = process.env.INDICATOR_SHOTS; // a directory to save a screenshot of each mode into

let b, srv, port, page;
const ctl = () => controller(b.cdp, async () => page, b.extensionId);
const badge = () => evaluate(b.cdp, page, 'document.documentElement.dataset.vimiumCljsIndicator || null');
const keys = async (seq, wait = 350) => { await ctl()('keys', { seq }); await sleep(wait); };
async function until(want, ms = 3000) {
  const t0 = Date.now();
  let m;
  while (Date.now() - t0 < ms) { m = await badge(); if (m === want) return m; await sleep(50); }
  return m;
}
async function open(path) {
  const { targetId } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/${path}` });
  page = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
  await b.cdp('Page.enable', {}, page);
  await b.cdp('Page.bringToFront', {}, page);
  await sleep(1200);
}
async function clickOn(sel) {
  const r = await evaluate(b.cdp, page, `(() => { const r = document.querySelector('${sel}').getBoundingClientRect(); return [r.left + r.width / 2, Math.min(r.top + r.height / 2, 400)]; })()`);
  await ctl()('click', { x: r[0], y: r[1] });
  await sleep(250);
}
async function expect(want, name, what) {
  assert.equal(await until(want), want, what);
  if (shots) {
    mkdirSync(shots, { recursive: true });
    const { data } = await b.cdp('Page.captureScreenshot', { format: 'png' }, page);
    writeFileSync(`${shots}/${name}-${want}.png`, Buffer.from(data, 'base64'));
  }
}

before(async () => {
  if (skip) return;
  b = await launch();
  ({ port, server: srv } = await startServer((op, q) => ctl()(op, q)));
});
after(() => { b?.close(); srv?.closeAllConnections(); srv?.close(); setTimeout(() => process.exit(process.exitCode ?? 0), 2000).unref(); });

test('Docs: the badge follows typing, normal mode, hints and the Vomnibar', T, async () => {
  await open('docs.html');
  await expect('insert', 'docs', 'the editor has the keyboard: typing');
  await keys('<esc>');
  await expect('normal', 'docs', 'Escape over the editor: normal mode');
  await keys('f');
  await expect('hints', 'docs', 'f: hints');
  await keys('<esc>');
  await expect('normal', 'docs', 'Escape leaves hints for normal mode');
  await keys('o', 600);
  await expect('vomnibar', 'docs', 'o: the Vomnibar');
  await keys('<esc>');
  await expect('normal', 'docs', 'Escape closes the Vomnibar, back to normal mode');
  await keys('f');
  await expect('hints', 'docs', 'f after the Vomnibar: hints');
  await keys('<esc>');
  await keys('i');
  await expect('insert', 'docs', 'i: typing again');
  await keys('<esc>o', 600);
  await clickOn('#b-bold');
  await expect('insert', 'docs', 'a click in the page closes the Vomnibar, as in Vimium: typing in the document');
  assert.equal((await ctl()('vomnibar', {})).open, false, 'the Vomnibar closed');
});

test('Sheets: the badge follows the cell editor, the formula bar and normal mode', T, async () => {
  await open('sheets.html');
  await expect('insert', 'sheets', 'the cell editor has the keyboard: typing');
  await keys('x');
  await expect('insert', 'sheets', 'editing a cell');
  await keys('<esc>');
  await expect('insert', 'sheets', 'Escape in a cell is the sheet\'s: still typing');
  await keys('<esc>');
  await expect('normal', 'sheets', 'a second Escape: normal mode');
  await keys('v');
  await expect('visual', 'sheets', 'v: visual mode');
  await keys('<esc>');
  await expect('normal', 'sheets', 'Escape leaves visual mode');
  await keys('f');
  await expect('hints', 'sheets', 'f: hints');
  await keys('<esc>');
  await clickOn('#formula');
  await expect('insert', 'sheets', 'the formula bar: typing');
  await keys('<esc><esc>');
  await expect('normal', 'sheets', 'Escape twice from the formula bar');
  await keys('o', 600);
  await expect('vomnibar', 'sheets', 'o: the Vomnibar');
  await keys('<esc>');
  await expect('normal', 'sheets', 'Escape closes the Vomnibar');
});
