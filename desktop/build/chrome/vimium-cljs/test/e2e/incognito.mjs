// Incognito: the manifest's split mode runs a second worker for incognito windows, which turns Chrome's own
// incognito new tab page (no extension may override it) into ours, and the content script runs on pages there.
// Chrome must allow the extension in incognito; Extensions.loadUnpacked's enableInIncognito does what the
// Allow in Incognito toggle does.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { launch, available, sleep, evaluate } from '../lib/chrome.mjs';
import { startServer } from '../lib/server.mjs';
import { keyEvents } from '../lib/keys.mjs';

const skip = available ? false : 'no headless Chrome (install the com.google.Chrome flatpak or set CHROME)';
const T = { skip, timeout: 40000 };

let b, srv, port;

before(async () => {
  if (skip) return;
  b = await launch();
  await b.cdp('Extensions.loadUnpacked', { path: (await import('../lib/chrome.mjs')).ROOT + '/ext', enableInIncognito: true });
  ({ port, server: srv } = await startServer(async () => null));
});
after(() => { b?.close(); srv?.closeAllConnections(); srv?.close(); setTimeout(() => process.exit(process.exitCode ?? 0), 2000).unref(); });

async function until(f, ms = 8000) {
  const t0 = Date.now();
  let v;
  while (Date.now() - t0 < ms) { try { v = await f(); if (v) return v; } catch {} await sleep(100); }
  return v;
}

const targets = async () => (await b.cdp('Target.getTargets')).targetInfos;
const attach = async (t) => (await b.cdp('Target.attachToTarget', { targetId: t.targetId, flatten: true })).sessionId;
async function press(s, seq) {
  for (const [down, up] of keyEvents(seq)) {
    await b.cdp('Input.dispatchKeyEvent', down, s);
    await b.cdp('Input.dispatchKeyEvent', up, s);
    await sleep(30);
  }
}

// The worker for normal windows, which starts first; the incognito one has the same URL in another context.
const normalWorker = () => until(async () => {
  const sw = (await targets()).find((t) => t.type === 'service_worker' && t.url.includes(b.extensionId));
  const s = sw && await attach(sw);
  // The worker from before enableInIncognito's reload may still be listed, without chrome.
  return s && (await evaluate(b.cdp, s, 'typeof chrome?.tabs === "object"').catch(() => false)) && s;
});

let incognitoTab;

test('a new incognito window opens on the vimium-cljs new tab page, and typing goes to the Vomnibar', T, async () => {
  const w = await normalWorker();
  assert.equal(await evaluate(b.cdp, w, 'chrome.extension.isAllowedIncognitoAccess()'), true, 'allowed in incognito');
  await evaluate(b.cdp, w, 'chrome.windows.create({ incognito: true }).then(() => true)');
  const found = await until(async () => {
    for (const t of await targets()) {
      if (t.type !== 'page' || !t.url.endsWith('/ui.html?newtab')) continue;
      const s = await attach(t);
      if (await evaluate(b.cdp, s, 'chrome.extension.inIncognitoContext')) return { t, s };
    }
  });
  assert.ok(found, 'an incognito tab shows ui.html?newtab');
  incognitoTab = found;
  assert.ok(!(await targets()).some((t) => t.type === 'page' && t.browserContextId === found.t.browserContextId && t.url.startsWith('chrome://newtab')),
            "Chrome's incognito new tab page is gone");
  await b.cdp('Page.bringToFront', {}, found.s);
  assert.ok(await until(() => evaluate(b.cdp, found.s, "document.hasFocus() && document.activeElement?.id === 'query'")), 'the Vomnibar has focus');
  await press(found.s, 'notes');
  const typed = await until(() => evaluate(b.cdp, found.s, "document.getElementById('query').value").then((v) => v === 'notes' && v));
  assert.equal(typed || await evaluate(b.cdp, found.s, "document.getElementById('query').value"), 'notes', 'typed into the Vomnibar');
});

test('vimium keys work on a page in the incognito window', T, async () => {
  assert.ok(incognitoTab, 'incognito window from the first test');
  // CDP cannot open a tab in Chrome's own incognito profile, but the incognito new tab page can.
  const url = `http://127.0.0.1:${port}/`;
  await evaluate(b.cdp, incognitoTab.s, `chrome.tabs.create({ url: '${url}' }).then(() => true)`);
  const t = await until(async () => (await targets()).find((t) => t.url === url && t.browserContextId === incognitoTab.t.browserContextId));
  assert.ok(t, 'the page opened in the incognito window');
  const s = await attach(t);
  await b.cdp('Page.bringToFront', {}, s);
  assert.ok(await until(() => evaluate(b.cdp, s, 'window.__probeFacts?.().ready')), 'content script ready');
  assert.equal(await evaluate(b.cdp, s, 'scrollY'), 0);
  await press(s, 'jj');
  assert.ok(await until(() => evaluate(b.cdp, s, 'scrollY > 0')), 'j scrolled');
  await press(s, 'gg');
  assert.ok(await until(() => evaluate(b.cdp, s, 'scrollY === 0')), 'gg went to the top');
});
