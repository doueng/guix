// Tabs that were open when the extension was reloaded or updated (bb build, then the reload
// arrow at chrome://extensions) must keep working without a page reload. Chrome does not
// re-inject content scripts into them, and the old script, cut off from the new worker,
// used to swallow worker commands such as w and e (previousTab, nextTab) without effect.
// The reload here is the user's: the arrow on the extension's card (developerPrivate.reload)
// and, for an update, loading the same unpacked path again.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { launch, available, sleep, evaluate, workerSession, ROOT } from '../lib/chrome.mjs';
import { startServer, controller } from '../lib/server.mjs';

const skip = available ? false : 'no headless Chrome (install the com.google.Chrome flatpak or set CHROME)';
const T = { skip, timeout: 90000 };

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
async function attach(path) {
  const { targetInfos } = await b.cdp('Target.getTargets');
  const t = targetInfos.find((t) => t.type === 'page' && t.url.endsWith(path));
  sessions[path] = (await b.cdp('Target.attachToTarget', { targetId: t.targetId, flatten: true })).sessionId;
}
async function open(path, { background = false } = {}) {
  await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}${path}`, background });
  await until(() => attach(path).then(() => true));
  const keep = page;
  page = sessions[path];
  assert.ok(await until(() => facts().then((f) => f.ready && Object.keys(f.frames).length >= 2), 10000), 'content script ready in ' + path);
  if (background) page = keep; else await front(path);
}
async function front(path) {
  page = sessions[path];
  await b.cdp('Page.bringToFront', {}, page);
  await until(async () => (await active()).endsWith(path));
  await sleep(200);
}
const active = async () => (await ctl()('tabs')).active || '';
const worker = () => workerSession(b.cdp, b.extensionId);
const inWorker = async (expr) => evaluate(b.cdp, await worker(), expr);
async function extensionFrames() {
  const { targetInfos } = await b.cdp('Target.getTargets');
  return targetInfos.filter((t) => t.url.startsWith(`chrome-extension://${b.extensionId}/ui.html`)).map((t) => t.url);
}

/** Waits until a worker other than OLD (a target id) runs, then lets it inject. */
async function newWorker(old) {
  await until(async () => {
    const t = (await b.cdp('Target.getTargets')).targetInfos.find((t) => t.type === 'service_worker' && t.url.includes(b.extensionId));
    return t && t.targetId !== old;
  }, 10000);
  await sleep(1500);
}
const workerId = async () => (await b.cdp('Target.getTargets')).targetInfos.find((t) => t.type === 'service_worker' && t.url.includes(b.extensionId))?.targetId;

/** The reload arrow on the extension's card at chrome://extensions. */
async function reloadArrow() {
  const old = await workerId();
  const { targetId } = await b.cdp('Target.createTarget', { url: 'chrome://extensions', background: true });
  const s = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
  await until(() => evaluate(b.cdp, s, '!!(window.chrome && chrome.developerPrivate)'));
  // Developer mode, as in the user's Chrome; without it Chrome disables the reloaded unpacked extension.
  await evaluate(b.cdp, s, 'chrome.developerPrivate.updateProfileConfiguration({inDeveloperMode: true})');
  await evaluate(b.cdp, s, `chrome.developerPrivate.reload('${b.extensionId}', {failQuietly: true})`);
  await b.cdp('Target.closeTarget', { targetId });
  await newWorker(old);
}

/** An update: loading the same unpacked path again (chrome.runtime.reload() would unload a CDP-loaded extension for good). */
async function reloadUpdate() {
  const old = await workerId();
  const { id } = await b.cdp('Extensions.loadUnpacked', { path: join(ROOT, 'ext') });
  assert.equal(id, b.extensionId, 'reloaded in place');
  await newWorker(old);
}

before(async () => {
  if (skip) return;
  b = await launch();
  ({ port, server: srv } = await startServer((op, q) => ctl()(op, q)));
});
after(() => { b?.close(); srv?.closeAllConnections(); srv?.close(); setTimeout(() => process.exit(process.exitCode ?? 0), 2000).unref(); });

async function tabKeys(when) {
  await front('/?a');
  await keys('e');
  assert.ok((await active()).endsWith('/?b'), `e ${when}: active ${await active()}`);
  page = sessions['/?b'];
  await keys('w');
  assert.ok((await active()).endsWith('/?a'), `w ${when}: active ${await active()}`);
}

test('w and e switch tabs from a tab that was open before an update', T, async () => {
  await open('/?a');
  await open('/?b');
  await tabKeys('before the update');
  await reloadUpdate();
  await tabKeys('after the update, without reloading the page');
});

test('after the reload arrow at chrome://extensions, every kind of binding works in tabs open before it', T, async () => {
  await open('/?c', { background: true }); // loaded in the background, never shown before the reload
  await reloadArrow();
  await reloadArrow(); // twice: the second orphan is a script the first reload injected
  await tabKeys('after the reload arrow');

  // Page-local keys, through exactly one content script.
  page = sessions['/?a'];
  const y0 = (await facts()).scrollY;
  await keys('j');
  assert.equal((await facts()).scrollY - y0, 60, 'j scrolls one step');
  await keys('gg');

  // Link hints need the worker and every frame, the cross-origin one too.
  await keys('f', 900);
  const f = await until(() => facts().then((f) => f.mode === 'hints' && Object.values(f.frames).length === 2
                                            && Object.values(f.frames).every((x) => x.mode === 'hints') && f));
  assert.ok(f, 'hints in the top frame and both iframes: ' + JSON.stringify((await facts()).frames && Object.values((await facts()).frames).map((x) => x.mode)));
  const frameHints = Object.values(f.frames).flatMap((x) => x.links.filter((l) => l.hint));
  assert.ok(frameHints.length >= 6, 'iframe links hinted: ' + frameHints.length);
  await keys(f.links.find((l) => l.id === 'l5').hint, 500);
  assert.ok((await facts()).url.endsWith('#l5'), 'followed the hint: ' + (await facts()).url);

  // Find, visual mode, the Vomnibar and help.
  await keys('/needle<enter>', 400);
  assert.ok((await facts()).findHighlights >= 3, 'find highlights');
  await keys('<esc>', 200);
  await keys('v', 200);
  assert.equal((await facts()).mode, 'visual');
  await keys('<esc>', 200);
  await keys('o', 900);
  assert.ok((await extensionFrames()).some((u) => u.includes('#vomnibar')), 'o opens the Vomnibar');
  await keys('<esc>', 500);
  await keys('?', 900);
  assert.ok((await extensionFrames()).some((u) => u.includes('#help')), '? shows help');
  await keys('<esc>', 500);

  // The tab that loaded in the background.
  await front('/?c');
  await keys('w');
  assert.ok(!(await active()).endsWith('/?c'), 'w from the background-loaded tab: active ' + await active());
});

test('a tab discarded across the reload works once shown again', T, async () => {
  await open('/?d');
  await front('/?a');
  const before = await inWorker(`chrome.tabs.query({}).then((ts) => ts.find((t) => t.url.endsWith('/?d')).id)`);
  const id = await inWorker(`chrome.tabs.discard(${before}).then((t) => t.id)`); // discarding may change the id
  await reloadArrow();
  await inWorker(`chrome.tabs.update(${id}, {active: true}).then(() => true)`);
  await sleep(500);
  await until(() => attach('/?d').then(() => true));
  page = sessions['/?d'];
  assert.ok(await until(() => facts().then((f) => f.ready), 10000), 'content script ready in the restored tab');
  await keys('w');
  assert.ok(!(await active()).endsWith('/?d'), 'w from the restored tab: active ' + await active());
});
