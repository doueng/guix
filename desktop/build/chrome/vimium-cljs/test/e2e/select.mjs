// After a pick the Vomnibar draws nothing more: the new tab page used to clear its query on Enter and show the unfiltered rows until the tab navigated.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { launch, available, sleep, evaluate, workerSession } from '../lib/chrome.mjs';
import { startServer, controller } from '../lib/server.mjs';

const skip = available ? false : 'no headless Chrome (install the com.google.Chrome flatpak or set CHROME)';
const T = { skip, timeout: 40000 };

let b, srv, port, page;
const ctl = () => controller(b.cdp, async () => page, b.extensionId, b.onEvent);

before(async () => {
  if (skip) return;
  b = await launch();
  ({ port, server: srv } = await startServer((op, q) => ctl()(op, q)));
});
after(() => { b?.close(); srv?.closeAllConnections(); srv?.close(); setTimeout(() => process.exit(process.exitCode ?? 0), 2000).unref(); });

async function until(f, ms = 5000) {
  const t0 = Date.now();
  let v;
  while (Date.now() - t0 < ms) { try { v = await f(); if (v) return v; } catch {} await sleep(50); }
  return v;
}

// Types TEXT into the Vomnibar, waits for its rows, then records what it draws from the pick on.
async function typeThenWatch(text, q = {}) {
  assert.ok(await until(async () => (await ctl()('vomnibar', q)).items.length > 0), 'rows shown');
  await ctl()('keys', { seq: text, gap: 0 });
  assert.ok(await until(async () => (await ctl()('vomnibar', q)).for === text), 'rows for the typed text');
  assert.ok(await ctl()('vomnibar-watch', q), 'recording');
}

const drawn = async () => (await ctl()('vomnibar-draws')).filter((d) => d.what === 'rows' || d.query !== d.for);

async function newTabPage() {
  const w = await workerSession(b.cdp, b.extensionId);
  // The worker may not have run yet in a fresh browser.
  assert.ok(await until(() => evaluate(b.cdp, w, 'chrome.tabs.create({}).then(() => true)')), 'new tab');
  const t = await until(async () => (await b.cdp('Target.getTargets')).targetInfos.find((t) => t.url.endsWith('/ui.html?newtab')));
  assert.ok(t, 'new tab page');
  page = (await b.cdp('Target.attachToTarget', { targetId: t.targetId, flatten: true })).sessionId;
  return t.targetId;
}

test('the new tab page draws nothing after a pick', T, async () => {
  const nt = await newTabPage();
  const target = `http://127.0.0.1:${port}/frame.html?picked`;
  await typeThenWatch(target.slice(7), { newtab: 1 });
  await ctl()('keys', { seq: '<enter>' });
  assert.ok(await until(async () => (await b.cdp('Target.getTargetInfo', { targetId: nt })).targetInfo.url === target), 'went to the pick');
  assert.deepEqual(await drawn(), [], 'nothing drawn after the pick');
  await b.cdp('Target.closeTarget', { targetId: nt });
});

test('a new tab page left for another tab starts over when it is back', T, async () => {
  const { targetId: notes } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/notes.html`, background: true });
  const nt = await newTabPage();
  await b.cdp('Page.bringToFront', {}, page);
  await typeThenWatch('Vomnibar field notes', { newtab: 1 });
  await ctl()('keys', { seq: '<enter>' });
  assert.ok(await until(async () => (await ctl()('tabs')).active?.endsWith('/notes.html')), 'switched to the notes tab');
  await sleep(200);
  assert.deepEqual(await drawn(), [], 'nothing drawn after the pick');
  await b.cdp('Page.bringToFront', {}, page);
  const v = await until(async () => { const v = await ctl()('vomnibar', { newtab: 1 }); return v.value === '' && v.for === '' && v; });
  assert.ok(v, 'empty again, with the rows for no text');
  for (const t of [notes, nt]) await b.cdp('Target.closeTarget', { targetId: t });
});

test('the in-page Vomnibar draws nothing after a pick', T, async () => {
  const { targetId: notes } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/notes.html` });
  const { targetId: home } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/` });
  page = (await b.cdp('Target.attachToTarget', { targetId: home, flatten: true })).sessionId;
  await b.cdp('Page.bringToFront', {}, page);
  assert.ok(await until(() => evaluate(b.cdp, page, 'window.__probeFacts().ready'), 10000), 'content script ready');
  await ctl()('keys', { seq: 'o' });
  await typeThenWatch('Vomnibar field notes');
  await ctl()('keys', { seq: '<enter>' });
  assert.ok(await until(async () => (await ctl()('tabs')).active?.endsWith('/notes.html')), 'switched to the notes tab');
  await sleep(300);
  assert.deepEqual(await drawn(), [], 'nothing drawn after the pick');
  for (const t of [notes, home]) await b.cdp('Target.closeTarget', { targetId: t });
});
