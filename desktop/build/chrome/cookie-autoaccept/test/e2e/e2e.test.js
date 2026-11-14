// Loads the extension unpacked into headless Chrome and checks it on the fixture pages.
// Chrome is the com.google.Chrome flatpak when present, else $CHROME (a chromium binary). Skips when neither exists.
// Branded Chrome ignores --load-extension, so this loads it over the DevTools pipe with Extensions.loadUnpacked.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, normalize } from 'node:path';

const ROOT = resolve(import.meta.dirname, '../..');
const FIXTURES = join(ROOT, 'test/fixtures');
// The unpacked extension that npm run build writes.
const DIST = join(ROOT, 'dist');
const flatpak = spawnSync('flatpak', ['info', 'com.google.Chrome'], { stdio: 'ignore' }).status === 0;
const chrome = process.env.CHROME || (flatpak ? null : ['chromium', 'chromium-browser'].find((c) => spawnSync('which', [c]).status === 0));
const skip = !flatpak && !chrome ? 'no headless Chrome found (install the com.google.Chrome flatpak or set CHROME)' : false;

let server, port, browser, profile, cdp, extensionId;

function startServer() {
  server = createServer((req, res) => {
    const path = normalize(new URL(req.url, 'http://x').pathname).replace(/^\/+/, '');
    const file = join(FIXTURES, path);
    if (!file.startsWith(FIXTURES) || !existsSync(file)) return res.writeHead(404).end();
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }).end(readFileSync(file));
  });
  return new Promise((ok) => server.listen(0, '127.0.0.1', () => ok(server.address().port)));
}

function launch() {
  profile = mkdtempSync(join(tmpdir(), 'cookie-autoaccept-e2e-'));
  const flags = [
    '--headless=new', `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    '--disable-sync', '--disable-background-networking', '--remote-debugging-pipe', '--enable-unsafe-extension-debugging',
    'about:blank',
  ];
  const [cmd, args] = chrome
    ? [chrome, flags]
    : ['flatpak', ['run', `--filesystem=${ROOT}:ro`, `--filesystem=${profile}`, 'com.google.Chrome', ...flags]];
  browser = spawn(cmd, args, { stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'] });
  let id = 0, buf = '';
  const waiting = new Map();
  browser.stdio[4].on('data', (d) => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\0')) >= 0) {
      const msg = JSON.parse(buf.slice(0, i));
      buf = buf.slice(i + 1);
      const w = waiting.get(msg.id);
      if (w) {
        waiting.delete(msg.id);
        msg.error ? w.reject(new Error(`${w.method}: ${msg.error.message}`)) : w.resolve(msg.result);
      }
    }
  });
  return (method, params = {}, sessionId) =>
    new Promise((res, rej) => {
      const msgId = ++id;
      waiting.set(msgId, { resolve: res, reject: rej, method });
      browser.stdio[3].write(JSON.stringify({ id: msgId, method, params, ...(sessionId && { sessionId }) }) + '\0');
    });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function evaluate(sessionId, expression) {
  const r = await cdp('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, sessionId);
  return r.result.value;
}

async function workerSession() {
  for (let i = 0; i < 50; i++) {
    const { targetInfos } = await cdp('Target.getTargets');
    const sw = targetInfos.find((t) => t.type === 'service_worker' && t.url.includes(extensionId));
    if (sw) return (await cdp('Target.attachToTarget', { targetId: sw.targetId, flatten: true })).sessionId;
    await sleep(200);
  }
  throw new Error('extension service worker not found');
}

async function setSettings(settings) {
  const s = await workerSession();
  await evaluate(s, `chrome.storage.local.set({ settings: ${JSON.stringify(settings)} }).then(() => true)`);
}

/** Opens a fixture, waits until `until` (a page expression) is truthy or the timeout passes, returns page facts. */
async function visit(page, until, timeout = 15000) {
  const { targetId } = await cdp('Target.createTarget', { url: `http://localhost:${port}/${page}` });
  const { sessionId } = await cdp('Target.attachToTarget', { targetId, flatten: true });
  const start = Date.now();
  let ok = false;
  while (Date.now() - start < timeout) {
    ok = await evaluate(sessionId, `location.pathname === '/${page}' && document.readyState === 'complete' && Boolean(${until})`).catch(() => false);
    if (ok) break;
    await sleep(250);
  }
  const title = await evaluate(sessionId, 'document.title');
  const tabId = await workerSession().then((s) =>
    evaluate(s, `chrome.tabs.query({}).then(ts => ts.find(t => t.url?.endsWith('/${page}'))?.id)`),
  );
  const report = await workerSession().then((s) =>
    evaluate(s, `chrome.storage.session.get('tab:${tabId}').then(r => r['tab:${tabId}'] ?? null)`),
  );
  await cdp('Target.closeTarget', { targetId });
  return { ok, title, report };
}

before(async () => {
  if (skip) return;
  port = await startServer();
  cdp = launch();
  ({ id: extensionId } = await cdp('Extensions.loadUnpacked', { path: DIST }));
});

after(async () => {
  for (const s of browser?.stdio ?? []) s?.destroy();
  // Chrome writes to its profile while it exits; remove the profile after that.
  if (browser && browser.exitCode === null) await new Promise((ok) => { browser.once('exit', ok); browser.kill(); setTimeout(ok, 5000); });
  server?.close();
  if (profile) rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
});

const accepts = ['onetrust', 'cookiebot', 'didomi', 'quantcast', 'trustarc', 'usercentrics', 'sourcepoint', 'cookiebot-v2', 'frame-consent', 'generic'];
for (const cmp of accepts) {
  test(`accepts ${cmp}`, { skip }, async () => {
    const { title, report } = await visit(`${cmp}.html`, `document.title !== 'pending'`);
    assert.equal(title, 'accepted');
    assert.ok(report?.result === true || report?.clicked > 0, JSON.stringify(report));
  });
}

test('accepts a late banner in shadow DOM', { skip }, async () => {
  const { title, report } = await visit('generic-shadow.html', `document.title !== 'pending'`, 15000);
  assert.equal(title, 'accepted');
  assert.equal(report?.clicked, 1);
});

test('leaves a newsletter box alone', { skip }, async () => {
  const { ok } = await visit('newsletter.html', `!document.getElementById('notice').checkVisibility()`, 6000);
  assert.ok(!ok, 'newsletter box was hidden');
});

// A banner added after the prehide was released must be hidden before anything else in the page sees it: hiding it
// in the next animation frame let earlier callbacks in that frame, or a paint after a ResizeObserver, see it.
test('a banner added after the prehide release is hidden before the adding task ends', { skip }, async () => {
  const { targetId } = await cdp('Target.createTarget', { url: `http://localhost:${port}/none.html` });
  const { sessionId } = await cdp('Target.attachToTarget', { targetId, flatten: true });
  try {
    let released = false;
    for (let t = Date.now(); !released && Date.now() - t < 15000; await sleep(250)) {
      released = await evaluate(sessionId, `document.getElementById('cookie-autoaccept-prehide')?.media === 'not all'`).catch(() => false);
    }
    assert.ok(released, 'prehide never released');
    const opacity = await evaluate(sessionId, `(async () => {
      const el = document.createElement('div');
      el.id = 'onetrust-banner-sdk';
      el.textContent = 'We use cookies';
      document.body.append(el);
      await Promise.resolve();
      return getComputedStyle(el).opacity;
    })()`);
    assert.equal(opacity, '0');
  } finally {
    await cdp('Target.closeTarget', { targetId });
  }
});

test('reject mode rejects', { skip }, async () => {
  await setSettings({ mode: 'reject', disabledSites: [] });
  for (const cmp of ['onetrust', 'didomi', 'sourcepoint']) {
    const { title } = await visit(`${cmp}.html`, `document.title !== 'pending'`);
    assert.equal(title, 'rejected', cmp);
  }
  // With no rule, reject mode never clicks accept: it hides the banner instead.
  const { ok, title, report } = await visit('generic.html', `!document.getElementById('notice').checkVisibility()`);
  assert.ok(ok, 'banner still visible');
  assert.equal(title, 'pending');
  assert.equal(report?.hidden, 1);
});

test('a disabled site is left alone', { skip }, async () => {
  await setSettings({ mode: 'accept', disabledSites: ['localhost'] });
  const { title, report } = await visit('onetrust.html', `document.title !== 'pending'`, 6000);
  assert.equal(title, 'pending');
  assert.equal(report?.disabled, true);
  const generic = await visit('generic.html', `!document.getElementById('notice').checkVisibility()`, 6000);
  assert.ok(!generic.ok, 'fallback hid a banner on a disabled site');
});
