// Tabs that were open when the extension was reloaded or updated (./build.sh, then the reload
// arrow at chrome://extensions) must keep working without a page reload. Chrome does not
// re-inject content scripts into them, and the old script, cut off from the new worker,
// used to swallow worker commands such as w and e (previousTab, nextTab) without effect.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { launch, available, sleep, evaluate, workerSession, ROOT } from '../lib/chrome.mjs';
import { startServer, controller } from '../lib/server.mjs';

const skip = available ? false : 'no headless Chrome (install the com.google.Chrome flatpak or set CHROME)';
const T = { skip, timeout: 60000 };

let b, srv, port, page;
const ctl = () => controller(b.cdp, async () => page, b.extensionId);
const keys = async (seq, wait = 400) => { await ctl()('keys', { seq }); await sleep(wait); };
const facts = () => evaluate(b.cdp, page, 'window.__probeFacts()');
const sessions = {};

async function until(f, ms = 5000) {
  const t0 = Date.now();
  let v;
  while (Date.now() - t0 < ms) {
    try { v = await f(); if (v) return v; } catch { /* target still starting */ }
    await sleep(100);
  }
  return v;
}
async function open(path) {
  const { targetId } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}${path}` });
  sessions[path] = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
  await front(path);
  assert.ok(await until(() => facts().then((f) => f.ready && Object.keys(f.frames).length >= 2), 10000), 'content script ready in ' + path);
}
async function front(path) {
  page = sessions[path];
  await b.cdp('Page.bringToFront', {}, page);
  await until(async () => (await active()).endsWith(path));
  await sleep(200);
}
const active = async () => (await ctl()('tabs')).active || '';

/** Reloads the extension as the reload arrow does (loading the same unpacked path again;
  chrome.runtime.reload() unloads a CDP-loaded extension for good), and waits for the new worker. */
async function reloadExtension() {
  const old = (await b.cdp('Target.getTargets')).targetInfos.find((t) => t.type === 'service_worker' && t.url.includes(b.extensionId));
  const { id } = await b.cdp('Extensions.loadUnpacked', { path: join(ROOT, 'ext') });
  assert.equal(id, b.extensionId, 'reloaded in place');
  await until(async () => {
    const t = (await b.cdp('Target.getTargets')).targetInfos.find((t) => t.type === 'service_worker' && t.url.includes(b.extensionId));
    return t && t.targetId !== old.targetId;
  }, 10000);
  await sleep(1500);
}

before(async () => {
  if (skip) return;
  b = await launch();
  ({ port, server: srv } = await startServer((op, q) => ctl()(op, q)));
});
after(() => { b?.close(); srv?.closeAllConnections(); srv?.close(); setTimeout(() => process.exit(process.exitCode ?? 0), 2000).unref(); });

test('w and e switch tabs from a tab that was open before the extension reloaded', T, async () => {
  await open('/?a');
  await open('/?b');
  await front('/?a');
  await keys('e');
  assert.ok((await active()).endsWith('/?b'), 'e works before the reload');
  page = sessions['/?b'];
  await keys('w');
  assert.ok((await active()).endsWith('/?a'), 'w works before the reload');

  await reloadExtension();
  await front('/?a');
  await keys('e');
  assert.ok((await active()).endsWith('/?b'), 'e after the reload, without reloading the page: active ' + await active());
  page = sessions['/?b'];
  await keys('w');
  assert.ok((await active()).endsWith('/?a'), 'w after the reload: active ' + await active());
  // Page-local keys keep working too, through exactly one content script.
  page = sessions['/?a'];
  const y0 = (await facts()).scrollY;
  await keys('j');
  const y1 = (await facts()).scrollY;
  assert.equal(y1 - y0, 60, `j scrolls one step (one script handles it) after the reload: ${y0} -> ${y1}`);
});
