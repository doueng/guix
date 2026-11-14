// Keystrokes for common Vomnibar tasks: for each task, tries key sequences shortest first
// in the seeded profile (scripts/vomnibar-lab.mjs) and reports the first that does the task.
// Picks memory starts empty for every try, so these are first-time counts.
//   node scripts/vomnibar-keys.mjs [EXT_DIR]   (ext/ by default; prints JSON)
import { seededBrowser, keys, vomnibarSession } from './vomnibar-lab.mjs';
import { sleep, evaluate } from '../test/lib/chrome.mjs';

const prefixes = (w) => ['', ...[...w].map((_, i) => w.slice(0, i + 1))];
const TASKS = [
  { name: 'Switch to an open tab (GitHub philc/vimium)', words: ['vimium', 'philc'],
    ok: (t) => t.active === 'https://github.com/philc/vimium' && t.count === t.count0 },
  { name: 'Revisit a recent page (r/emacs, closed)', words: ['emacs', 'reddit'],
    ok: (t) => t.active === 'https://www.reddit.com/r/emacs/' && t.count === t.count0 },
  { name: 'Switch to an open tab from o or Ctrl+L (GitHub philc/vimium)', words: ['vimium', 'philc'], opens: ['o'],
    ok: (t) => t.active === 'https://github.com/philc/vimium' && t.count === t.count0 },
  { name: 'Revisit a page picked once before as "thheller" (shadow-cljs)', words: ['thheller', 'shadow'], opens: ['o'],
    warm: 'thheller', ok: (t) => t.active === 'https://github.com/thheller/shadow-cljs' && t.count === t.count0 },
  { name: 'Search the web (rust lifetimes)', words: ['rust lifetimes'], full: true,
    ok: (t) => /google\.com\/search\?q=rust(%20|\+)lifetimes/.test(t.active || '') },
  { name: 'Search Wikipedia by keyword (w cats)', words: ['w cats'], full: true,
    ok: (t) => /wikipedia\.org.*search=cats/.test(t.active || '') },
];

function candidates(task) {
  const out = [];
  for (const w of task.words)
    for (const q of task.full ? [w] : prefixes(w))
      for (let tabs = 0; tabs <= 6; tabs++)
        for (const open of task.opens || ['o', 'p'])
          out.push({ open, q, tabs, n: 1 + q.length + tabs + 1 });
  return out.sort((a, b) => a.n - b.n);
}

const b = await seededBrowser({ width: 1600, height: 1000, scale: 1 });
const results = [];
let focusAssists = 0;
try {
  const home = await b.openPage('https://news.ycombinator.com/');
  const state = () => evaluate(b.cdp, b.w, `chrome.tabs.query({}).then(ts => ({count: ts.length,
    active: (ts.find(t => t.active) || {}).pendingUrl || (ts.find(t => t.active) || {}).url}))`);
  const count0 = (await state()).count;
  const initial = new Set((await b.cdp('Target.getTargets')).targetInfos.filter((x) => x.type === 'page').map((x) => x.targetId));
  for (const task of TASKS) {
    let found = null;
    let warmed = false;
    for (const c of candidates(task)) {
      if (!task.warm) await evaluate(b.cdp, b.w, 'chrome.storage.local.clear().then(() => true)');
      else if (!warmed) {
        // One earlier pick: typed in full, opened with Enter.
        warmed = true;
        await evaluate(b.cdp, b.w, 'chrome.storage.local.clear().then(() => true)');
        await b.cdp('Page.bringToFront', {}, home.s);
        await keys(b, home.s, '<esc>o'); await sleep(500);
        const vs0 = await vomnibarSession(b);
        await evaluate(b.cdp, vs0, "document.getElementById('query').focus()");
        await keys(b, home.s, task.warm); await sleep(400); await keys(b, home.s, '<enter>'); await sleep(900);
        await b.cdp('Page.navigate', { url: 'https://news.ycombinator.com/' }, home.s); await sleep(600);
      }
      await b.cdp('Page.bringToFront', {}, home.s);
      await sleep(300);
      await keys(b, home.s, '<esc>' + c.open);
      await sleep(500);
      // The old build loses the input's focus right after opening (headless at least);
      // give it back so its counts measure ranking, not that bug.
      const vs = await vomnibarSession(b);
      if (!(await evaluate(b.cdp, vs, "document.activeElement.id === 'query'"))) {
        focusAssists++;
        await evaluate(b.cdp, vs, "document.getElementById('query').focus()");
      }
      await keys(b, home.s, c.q.replace(/ /g, ' '));
      await sleep(350);
      await keys(b, home.s, '<tab>'.repeat(c.tabs) + '<enter>');
      await sleep(900);
      const t = { ...(await state()), count0 };
      const ok = task.ok(t);
      // Undo: close extra tabs, bring the home tab back to Hacker News.
      const { targetInfos } = await b.cdp('Target.getTargets');
      for (const p of targetInfos.filter((x) => x.type === 'page' && !initial.has(x.targetId))) await b.cdp('Target.closeTarget', { targetId: p.targetId });
      const { targetInfo } = await b.cdp('Target.getTargetInfo', { targetId: home.targetId });
      if (!targetInfo.url.startsWith('https://news.ycombinator.com')) { await b.cdp('Page.navigate', { url: 'https://news.ycombinator.com/' }, home.s); await sleep(600); }
      await keys(b, home.s, '<esc>');
      if (ok) { found = c; break; }
    }
    const seq = found && `${found.open}${found.q ? ' ' + JSON.stringify(found.q) : ''}${found.tabs ? ' Tab×' + found.tabs : ''} Enter`;
    results.push({ task: task.name, keys: found ? found.n : null, seq });
    console.error(task.name, '->', found ? found.n : 'not found', seq);
  }
} finally { b.close(); }
console.log(JSON.stringify({ results, focusAssists }));
setTimeout(() => process.exit(0), 1600);
