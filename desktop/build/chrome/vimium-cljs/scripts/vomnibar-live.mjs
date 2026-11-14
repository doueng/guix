// Screenshots the Vomnibar in a headed Chrome on an X server (an Xvfb), where Chrome's own UI
// is real: the popup window, the new tab page and keyboard focus. Keys go through xdotool, so
// they land wherever Chrome's focus really is, as when you type.
//   DISPLAY=:99 node scripts/vomnibar-live.mjs OUT_DIR
// Needs xdotool and xwd + convert (guix shell xorg-server xdotool xwd imagemagick).
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { launch, sleep, evaluate, workerSession } from '../test/lib/chrome.mjs';
import { startServer } from '../test/lib/server.mjs';

const out = process.argv[2] || '/tmp/vomnibar-live';
mkdirSync(out, { recursive: true });
const display = process.env.DISPLAY || ':99';
const x = (...a) => execFileSync('xdotool', a, { env: { ...process.env, DISPLAY: display } }).toString().trim();
const shot = (name) => {
  const f = join(out, name + '.png');
  execFileSync('sh', ['-c', `xwd -root -silent -display ${display} | convert xwd:- ${f}`]);
  console.log('shot', f);
};
const type = async (text) => { x('type', '--delay', '40', text); await sleep(400); };
const key = async (k) => { x('key', k); await sleep(300); };

// THEME=light (or dark) sets the pages' prefers-color-scheme; Chrome's own UI follows the system.
const theme = { light: 1, dark: 0 }[process.env.THEME];
const b = await launch({ display, width: 1200, height: 760, flags: theme == null ? [] : [`--blink-settings=preferredColorScheme=${theme}`] });
const { server, port } = await startServer(async () => null);
const report = {};
try {
  const w = await workerSession(b.cdp, b.extensionId);
  await evaluate(b.cdp, w, `Promise.all(['https://github.com/philc/vimium', 'https://news.ycombinator.com/', 'https://clojure.org/guides/getting_started',
    'https://en.wikipedia.org/wiki/Vim_(text_editor)', 'https://guix.gnu.org/manual/en/html_node/Invoking-guix-shell.html']
    .map((url) => chrome.history.addUrl({ url }))).then(() => true)`);
  for (const p of ['notes.html', 'tube.html', ...Array.from({ length: 14 }, (_, i) => `notes.html?${i}`)])
    await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/${p}`, background: true });
  const tabs = () => evaluate(b.cdp, w, `chrome.tabs.query({}).then((ts) => ts.map((t) => [t.id, t.active, t.url]))`);
  const windows = () => evaluate(b.cdp, w, `chrome.windows.getAll({ populate: true }).then((ws) => ws.map((x) => [x.type, x.width, x.height, x.tabs.map((t) => t.url)]))`);
  const vomnibar = async (want = /ui\.html/) => {
    const { targetInfos } = await b.cdp('Target.getTargets');
    const t = targetInfos.filter((t) => want.test(t.url)).pop();
    if (!t) return null;
    const s = (await b.cdp('Target.attachToTarget', { targetId: t.targetId, flatten: true })).sessionId;
    return evaluate(b.cdp, s, `(() => { const q = document.getElementById('query'); const l = document.getElementById('list');
      return { url: location.href, value: q?.value, focused: document.hasFocus() && document.activeElement === q,
        rows: document.querySelectorAll('#list li').length,
        selected: [...document.querySelectorAll('#list li')].findIndex((li) => li.classList.contains('selected')),
        selectedVisible: (() => { const s = document.querySelector('#list li.selected'); if (!s) return null; const r = s.getBoundingClientRect(), lr = l.getBoundingClientRect();
          return r.top >= lr.top - 1 && r.bottom <= lr.bottom + 1 && r.bottom <= innerHeight; })(),
        scrollbars: [document.documentElement, document.body, l].filter((e) => e && e.scrollHeight > e.clientHeight && getComputedStyle(e).scrollbarWidth !== 'none' && e.offsetWidth > e.clientWidth).map((e) => e.id || e.tagName) }; })()`).catch((e) => String(e));
  };

  // 1. A normal page: Ctrl+L (the worker's command) opens the overlay; type at once.
  const { targetId } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/index.html` });
  await sleep(1500);
  x('mousemove', '600', '400'); x('click', '1'); await sleep(300);
  await evaluate(b.cdp, w, `chrome.tabs.query({ active: true, lastFocusedWindow: true }).then(([t]) => vimiumCljsVomnibarCommand(t, 'omni'))`);
  await type('vim');
  report.page = { vomnibar: await vomnibar(/#vomnibar/), windows: await windows() };
  shot('1-page');
  for (let i = 0; i < 12; i++) await key('Tab');
  report.pageMoved = await vomnibar(/#vomnibar/);
  shot('2-page-moved');
  await key('Escape');
  await sleep(300);
  await evaluate(b.cdp, w, `chrome.tabs.query({ active: true, lastFocusedWindow: true }).then(([t]) => vimiumCljsVomnibarCommand(t, 'tabs'))`);
  await sleep(500);
  for (let i = 0; i < 13; i++) await key('Tab');
  report.pageTabsMoved = await vomnibar(/#vomnibar/);
  shot('2b-page-tabs-moved');
  await key('Escape');

  // 2. A new tab (Chrome's t / Ctrl+T path): type at once, then Ctrl+L there.
  await evaluate(b.cdp, w, `chrome.tabs.create({}).then(() => true)`);
  await sleep(1500);
  await type('clo');
  report.newtab = { vomnibar: await vomnibar(/\?newtab/), windows: await windows(), tabs: await tabs() };
  shot('3-newtab-typed');
  await evaluate(b.cdp, w, `chrome.tabs.query({ active: true, lastFocusedWindow: true }).then(([t]) => vimiumCljsVomnibarCommand(t, 'tabs'))`);
  await sleep(800);
  await type('not');
  report.newtabCommand = { vomnibar: await vomnibar(/\?newtab|popup=1/), windows: await windows() };
  shot('4-newtab-command');
  await key('Escape');

  // 3. A chrome:// page: the popup fallback.
  await evaluate(b.cdp, w, `chrome.tabs.create({ url: 'chrome://version' }).then(() => true)`);
  await sleep(1200);
  await evaluate(b.cdp, w, `chrome.tabs.query({ active: true, lastFocusedWindow: true }).then(([t]) => vimiumCljsVomnibarCommand(t, 'omni'))`);
  await sleep(600);
  // Hyprland's chrome-extension-popup rule gives the popup 900x520; twm leaves Chrome's size.
  await evaluate(b.cdp, w, `chrome.windows.getAll().then((ws) => Promise.all(ws.filter((x) => x.type === 'popup').map((x) => chrome.windows.update(x.id, { width: 900, height: 520, left: 150, top: 120 })))).then(() => true)`);
  await sleep(600);
  await type('wiki');
  report.popup = { vomnibar: await vomnibar(/popup=1/), windows: await windows() };
  shot('5-popup');
  for (let i = 0; i < 12; i++) await key('Tab');
  shot('6-popup-moved');
  await key('Escape');
} finally {
  writeFileSync(join(out, 'report.json'), JSON.stringify(report, null, 1));
  console.log(JSON.stringify(report, null, 1));
  b.close(); server.close(); setTimeout(() => process.exit(0), 1600);
}
