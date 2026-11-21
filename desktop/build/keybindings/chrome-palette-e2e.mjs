// Chrome's commands in the Cmd+Space launcher, end to end on the hidden test display:
// a headed Chrome in test-display with the ext-reload helper loaded over CDP and a hub on a
// spare port, then `palette run` for rows of palette.edn's :chrome table, the way choosing
// them in the launcher runs them. Nothing touches your screen, your Chrome or your hub.
//
//   node desktop/build/keybindings/chrome-palette-e2e.mjs [--keep]
import { spawn, spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const ROOT = resolve(import.meta.dirname, '../../..');
const KEEP = process.argv.includes('--keep');
const PORT = 18357 + Math.floor(Math.random() * 1000);
const DISPLAY = `chrome-palette-${process.pid}`;
const tmp = mkdtempSync(join(tmpdir(), 'chrome-palette-e2e-'));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok: !!ok });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` - ${detail}` : ''}`);
};
const td = (...args) => spawnSync('test-display', ['--name', DISPLAY, ...args], { encoding: 'utf8' });

// The helper as the hub installs it, pointed at this run's port.
const helperSrc = join(tmp, 'helper-src');
cpSync(join(ROOT, 'desktop/build/chrome/ext-reload/reloader'), helperSrc, { recursive: true });
const bg = join(helperSrc, 'bg.js');
writeFileSync(bg, readFileSync(bg, 'utf8').replace('127.0.0.1:7357', `127.0.0.1:${PORT}`));
const helperDir = join(tmp, 'helper');
const env = { ...process.env, EXT_RELOAD_PORT: String(PORT), EXT_RELOAD_HELPER_SRC: helperSrc, EXT_RELOAD_HELPER_DIR: helperDir };

// Row indexes by label, as the launcher's chrome:N values name them.
const rows = JSON.parse(spawnSync('bb', ['-e', `(println (cheshire.core/generate-string (into {} (map-indexed (fn [i e] [(:label e) i]) (:chrome (clojure.edn/read-string (slurp "${ROOT}/desktop/configs/keybindings/palette.edn")))))))`], { encoding: 'utf8' }).stdout);
const palette = (label) => spawnSync('bb', [join(ROOT, 'desktop/bin/palette'), 'run', `chrome:${rows[label]}`], {
  encoding: 'utf8',
  env: { ...env, PALETTE_WINDOW: JSON.stringify({ class: 'google-chrome' }),
         KEYLEADER_CONFIG: join(ROOT, 'desktop/configs/keybindings/leader.edn'),
         KEYGUIDE_CONFIG: join(ROOT, 'desktop/configs/keybindings/bindings.edn'),
         XDG_STATE_HOME: join(tmp, 'state'), KEYLOG_FILE: '/dev/null' },
});

const hub = spawn('bb', [join(ROOT, 'desktop/bin/ext-reload'), 'serve'], { env, stdio: ['ignore', 'ignore', 'inherit'] });
let chrome;
try {
  if (td('--size', '1280x800', 'start').status !== 0) throw new Error('test-display would not start');
  // The display's environment, so Chrome draws there and test-display stop finds it.
  const denv = Object.fromEntries(td('exec', '--', 'env').stdout.split('\n').map((l) => l.split(/=(.*)/s)).filter((kv) => kv.length > 1));
  for (let i = 0; i < 100 && !existsSync(join(helperDir, 'manifest.json')); i++) await sleep(100);
  const profile = join(tmp, 'profile');
  // --no-documents-portal: the test needs no file chooser, and a stale portal mount makes bwrap refuse to start.
  chrome = spawn('flatpak', ['run', '--no-documents-portal', '--socket=wayland', '--nosocket=x11', `--filesystem=${tmp}`, 'com.google.Chrome',
    '--ozone-platform=wayland', `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--disable-sync',
    '--remote-debugging-pipe', '--enable-unsafe-extension-debugging', 'about:blank'],
    { env: { ...process.env, WAYLAND_DISPLAY: denv.WAYLAND_DISPLAY, XDG_RUNTIME_DIR: denv.XDG_RUNTIME_DIR, TEST_DISPLAY: denv.TEST_DISPLAY },
      stdio: ['ignore', 'ignore', openSync(join(tmp, 'chrome.log'), 'w'), 'pipe', 'pipe'] });
  chrome.stdio[4].on('error', () => {});
  let id = 0, buf = '';
  const waiting = new Map();
  chrome.stdio[4].on('data', (d) => {
    buf += d;
    for (let i; (i = buf.indexOf('\0')) >= 0; buf = buf.slice(i + 1)) {
      const m = JSON.parse(buf.slice(0, i));
      const w = waiting.get(m.id);
      if (w) { waiting.delete(m.id); m.error ? w.rej(new Error(m.error.message)) : w.res(m.result); }
    }
  });
  const cdp = (method, params = {}, sessionId) => new Promise((res, rej) => {
    waiting.set(++id, { res, rej });
    chrome.stdio[3].write(JSON.stringify({ id, method, params, ...(sessionId && { sessionId }) }) + '\0');
  });
  const { id: helperId } = await cdp('Extensions.loadUnpacked', { path: helperDir });
  let helpers = 0;
  for (let i = 0; i < 100 && !helpers; i++) {
    helpers = (await fetch(`http://127.0.0.1:${PORT}/status`, { headers: { 'x-ext-reload': '1' } }).then((r) => r.json()).catch(() => ({}))).helpers;
    if (!helpers) await sleep(200);
  }
  check('the ext-reload helper connects to the hub', helpers);
  const pages = async () => (await cdp('Target.getTargets')).targetInfos.filter((t) => t.type === 'page').map((t) => t.url);
  const opened = async (prefix) => {
    for (let i = 0; i < 50; i++) { const p = (await pages()).find((u) => u.startsWith(prefix)); if (p) return p; await sleep(200); }
  };
  // The active tab as the helper sees it.
  const activeTab = async () => {
    const sw = (await cdp('Target.getTargets')).targetInfos.find((t) => t.type === 'service_worker' && t.url.includes(helperId));
    const { sessionId } = await cdp('Target.attachToTarget', { targetId: sw.targetId, flatten: true });
    return (await cdp('Runtime.evaluate', { expression: 'chrome.tabs.query({ active: true, lastFocusedWindow: true }).then(([t]) => ({ url: t.url, pinned: t.pinned, muted: t.mutedInfo.muted }))', awaitPromise: true, returnByValue: true }, sessionId)).result.value;
  };

  let r = palette('Chrome: passwords');
  check('Chrome: passwords runs', r.status === 0, r.stderr.trim());
  check('it opens Chrome\'s password manager', await opened('chrome://password-manager'), (await pages()).join(' '));
  let title;
  for (let i = 0; i < 50 && !/password/i.test(title ?? ''); i++) {
    title = (await cdp('Target.getTargets')).targetInfos.find((t) => t.url.startsWith('chrome://password-manager'))?.title;
    if (!/password/i.test(title ?? '')) await sleep(200);
  }
  check('the page draws as Password Manager', /password/i.test(title ?? ''), title);
  await sleep(500);
  td('exec', '--', 'grim', join(tmp, 'passwords.png'));
  r = palette('Chrome: extensions');
  check('Chrome: extensions runs', r.status === 0, r.stderr.trim());
  check('it opens chrome://extensions', await opened('chrome://extensions'), (await pages()).join(' '));
  check('the page it opened is the active tab', (await activeTab())?.url?.startsWith('chrome://extensions'), JSON.stringify(await activeTab()));
  const before = await activeTab();
  r = palette('Pin or unpin tab');
  check('Pin or unpin tab runs', r.status === 0, r.stderr.trim());
  let after;
  for (let i = 0; i < 20 && !(after = await activeTab())?.pinned; i++) await sleep(150);
  check('it pins the active tab', !before.pinned && after?.pinned, JSON.stringify(after));
  r = palette('Mute or unmute tab');
  for (let i = 0; i < 20 && !(after = await activeTab())?.muted; i++) await sleep(150);
  check('Mute or unmute tab mutes it', r.status === 0 && after?.muted, JSON.stringify(after));
} catch (e) {
  check('the run finished', false, e.message);
} finally {
  chrome?.kill('SIGTERM');
  hub.kill('SIGTERM');
  td('stop');
  const failed = results.filter((x) => !x.ok).length;
  console.log(`chrome-palette-e2e: ${results.length} checks, ${failed} failed${KEEP || failed ? `; files in ${tmp}` : ''}`);
  setTimeout(() => {
    if (!KEEP && !failed) rmSync(tmp, { recursive: true, force: true });
    process.exit(failed ? 1 : 0);
  }, 1500);
}
