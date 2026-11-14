// Times the Vomnibar over a large profile: N_HISTORY history entries and N_TABS open tabs.
// For each open it reports how long until the input is focused with rows shown, and for
// each keystroke how long from the input event until the list shows rows for that text.
//   node scripts/vomnibar-perf.mjs [--history 5000] [--tabs 60] [--query 'gith iss']
import { launch, sleep, evaluate, workerSession } from '../test/lib/chrome.mjs';
import { startServer } from '../test/lib/server.mjs';
import { keys, vomnibarSession } from './vomnibar-lab.mjs';

const arg = (n, d) => { const i = process.argv.indexOf(n); return i > 0 ? process.argv[i + 1] : d; };
const nHistory = +arg('--history', 5000), nTabs = +arg('--tabs', 60), query = arg('--query', 'gith iss');

const words = 'github issue pull request docs api clojure script chrome extension vimium guix hyprland emacs wiki news mail calendar video music shop search map weather code review build test deploy server cloud data notes blog forum'.split(' ');
const pick = (i, k) => words[(i * 7919 + k * 104729) % words.length];

const b = await launch();
const { server, port } = await startServer(async () => null);
try {
  let w = await workerSession(b.cdp, b.extensionId);
  // The worker can restart once just after the extension loads; wait for the one that stays.
  for (let i = 0; i < 40 && await evaluate(b.cdp, w, 'typeof chrome?.history').catch(() => 'gone') !== 'object'; i++) {
    await sleep(250);
    w = await workerSession(b.cdp, b.extensionId);
  }
  const urls = Array.from({ length: nHistory }, (_, i) => `https://${pick(i, 0)}.example.com/${pick(i, 1)}-${pick(i, 2)}/${i}`);
  for (let i = 0; i < urls.length; i += 500)
    await evaluate(b.cdp, w, `Promise.all(${JSON.stringify(urls.slice(i, i + 500))}.map((url) => chrome.history.addUrl({ url }))).then(() => true)`);
  for (let i = 0; i < nTabs; i++)
    await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/notes.html?${pick(i, 2)}-${pick(i, 3)}-${i}`, background: true });
  const { targetId } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/index.html` });
  const s = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
  await b.cdp('Page.bringToFront', {}, s);
  await sleep(1500);
  for (const round of [1, 2, 3]) {
    const t0 = Date.now();
    await keys(b, s, '<esc>o', 0);
    const vs = await vomnibarSession(b);
    while (!(await evaluate(b.cdp, vs, `document.activeElement?.id === 'query' && document.querySelectorAll('#list li').length > 0`))) await sleep(5);
    const openMs = Date.now() - t0;
    if (round === 1) console.log('load parts', JSON.stringify(await evaluate(b.cdp, vs, `(async () => { const r = {}; let t = performance.now();
      await chrome.tabs.query({}); r.tabs = performance.now() - t; t = performance.now();
      const h = await chrome.history.search({ text: '', maxResults: 3000, startTime: Date.now() - 180 * 864e5 }); r.history = performance.now() - t; r.n = h.length; t = performance.now();
      await chrome.bookmarks.getTree(); r.bookmarks = performance.now() - t; t = performance.now();
      await chrome.storage.session.get('recency'); await chrome.storage.local.get('vomnibarPicks'); r.storage = performance.now() - t;
      r.nav = performance.getEntriesByType('navigation')[0]?.toJSON(); return r; })()`)));
    await evaluate(b.cdp, vs, `(() => { const q = document.getElementById('query'); window.__t = { in: {}, out: {} };
      q.addEventListener('input', () => { window.__t.in[q.value] ??= performance.now(); }, true);
      new MutationObserver(() => { window.__t.out[q.value] ??= performance.now(); }).observe(document.getElementById('list'), { childList: true });
      return true; })()`);
    for (const gap of [0, 90]) {
      await keys(b, vs, query, gap);
      await sleep(1200);
      const t = await evaluate(b.cdp, vs, `window.__t`);
      const lat = Object.keys(t.in).map((k) => [k, t.out[k] != null ? Math.round(t.out[k] - t.in[k]) : null]);
      console.log(JSON.stringify({ round, openMs, gap, lat }));
      await evaluate(b.cdp, vs, `(() => { const q = document.getElementById('query'); q.value = ''; q.dispatchEvent(new Event('input')); window.__t = { in: {}, out: {} }; return true; })()`);
      await sleep(600);
    }
    if (process.env.DEBUG) console.log('frame before esc', (await b.cdp('Target.getTargets')).targetInfos.filter((t) => t.url.includes('ui.html')).map((t) => t.url),
      await evaluate(b.cdp, vs, 'document.hasFocus() + " " + document.getElementById("query").value').catch(String));
    // Escape removes the frame on keydown, so its keyup has no session left.
    await keys(b, vs, '<esc>', 0).catch(() => {});
    await sleep(500);
    if (process.env.DEBUG) console.log(JSON.stringify((await b.cdp('Target.getTargets')).targetInfos.filter((t) => !t.url.includes('notes.html')).map((t) => [t.type, t.url, t.targetId === targetId])));
  }
} finally { b.close(); server.close(); setTimeout(() => process.exit(0), 1600); }
