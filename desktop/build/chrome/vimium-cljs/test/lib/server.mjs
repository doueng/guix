import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, normalize } from 'node:path';
import { keyEvents } from './keys.mjs';
import { ROOT, sleep, evaluate, workerSession } from './chrome.mjs';

const FIXTURES = join(ROOT, 'test/fixtures');
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript' };

const vomnibarState = `(() => {
  const q = document.getElementById('query'), v = document.getElementById('vomnibar');
  const l = document.getElementById('list'), sel = l && l.querySelector('li.selected');
  // An element shows a scrollbar when it overflows and its scrollbar takes room.
  const scrollbars = [document.documentElement, document.body, l].filter((e) => e && e.scrollHeight > e.clientHeight + 1
    && getComputedStyle(e).overflowY !== 'hidden' && (e === document.documentElement ? innerWidth > e.clientWidth : e.offsetWidth - e.clientWidth > (parseFloat(getComputedStyle(e).borderLeftWidth) || 0) * 2)).map((e) => e.id || e.tagName);
  const r = sel && sel.getBoundingClientRect(), lr = l && l.getBoundingClientRect();
  return { open: !!q && !v.hidden, focused: document.hasFocus() && document.activeElement === q, value: q ? q.value : '',
    for: l ? l.dataset.for ?? null : null, scrollbars,
    selectedVisible: !sel || (r.top >= lr.top - 1 && r.bottom <= lr.bottom + 1 && r.top >= 0 && r.bottom <= innerHeight),
    items: [...document.querySelectorAll('#list li')].map((li) => ({ url: li.dataset.url, type: li.querySelector('.badge').textContent,
      title: li.querySelector('.title').textContent, selected: li.classList.contains('selected') })) };
})()`;

export function startServer(control) {
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    if (url.pathname.startsWith('/__ctl/')) {
      try {
        const out = await control(url.pathname.slice(7), Object.fromEntries(url.searchParams));
        return res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(out ?? null));
      } catch (e) {
        return res.writeHead(500).end(String(e));
      }
    }
    const path = normalize(url.pathname === '/' ? '/index.html' : url.pathname).replace(/^\/+/, '');
    const file = join(FIXTURES, path);
    if (!file.startsWith(FIXTURES) || !existsSync(file)) return res.writeHead(404).end();
    res.writeHead(200, { 'content-type': types[file.slice(file.lastIndexOf('.'))] || 'text/plain' }).end(readFileSync(file));
  });
  return new Promise((ok) => server.listen(0, '127.0.0.1', () => ok({ server, port: server.address().port })));
}

// Input events to a frame the event removes (Escape closing the Vomnibar) are never acknowledged, so give up after a second.
const within = (p, ms) => Promise.race([p, sleep(ms)]);

// Reports each redraw of the rows, and each change of the typed text, through a binding: the page may be gone when they are read.
const drawRecorder = `(() => {
  const l = document.getElementById('list'), q = document.getElementById('query');
  if (!l || window.__vomWatching) return !!l;
  window.__vomWatching = true;
  const report = (what) => window.__vomDraw(JSON.stringify({ what, query: q.value, for: l.dataset.for ?? null,
    urls: [...l.querySelectorAll('li')].map((li) => li.dataset.url) }));
  new MutationObserver(() => report('rows')).observe(l, { childList: true });
  q.addEventListener('input', () => report('input'));
  return true;
})()`;

// ON-EVENT (b.onEvent) lets vomnibar-watch record redraws; q.newtab picks the new tab page over the in-page Vomnibar.
export function controller(cdp, session, extensionId, onEvent) {
  const frames = globalThis.__vomFrames ??= new Map();
  const draws = globalThis.__vomDraws ??= new Map();
  if (onEvent && !globalThis.__vomListening) {
    globalThis.__vomListening = true;
    onEvent((m) => {
      if (m.method === 'Runtime.bindingCalled' && m.params.name === '__vomDraw') draws.get(m.sessionId)?.push(JSON.parse(m.params.payload));
    });
  }
  const press = async (s, seq, gap) => {
    for (const [down, up] of keyEvents(seq)) {
      await within(cdp('Input.dispatchKeyEvent', down, s), 1000);
      await within(cdp('Input.dispatchKeyEvent', up, s), 1000);
      await sleep(gap);
    }
  };
  const vomnibarSession = async (q) => {
    const { targetInfos } = await cdp('Target.getTargets');
    const t = targetInfos.find((t) => t.url.includes(q?.newtab ? 'ui.html?newtab' : 'ui.html#vomnibar'));
    if (!t) return null;
    if (!frames.has(t.targetId)) frames.set(t.targetId, (await cdp('Target.attachToTarget', { targetId: t.targetId, flatten: true })).sessionId);
    return frames.get(t.targetId);
  };
  return async (op, q) => {
    if (op === 'vomnibar') {
      const s = await vomnibarSession(q);
      if (!s) return { open: false, focused: false, value: '', items: [] };
      return evaluate(cdp, s, vomnibarState).catch(() => ({ open: false, focused: false, value: '', items: [] }));
    }
    // Starts recording what the Vomnibar draws from now on; vomnibar-draws returns it and stops.
    if (op === 'vomnibar-watch') {
      const s = await vomnibarSession(q);
      if (!s || !onEvent) return false;
      draws.set(s, []);
      await cdp('Runtime.enable', {}, s);
      await cdp('Runtime.addBinding', { name: '__vomDraw' }, s);
      return evaluate(cdp, s, drawRecorder).catch(() => false);
    }
    // Opens a new tab page, types q.text, picks with Enter and returns what it showed and drew from then on, then closes that tab.
    if (op === 'newtab-pick') {
      const w = await workerSession(cdp, extensionId);
      const known = new Set((await cdp('Target.getTargets')).targetInfos.map((t) => t.targetId));
      await evaluate(cdp, w, 'chrome.tabs.create({}).then(() => true)');
      let t;
      for (let i = 0; i < 50 && !t; i++) {
        t = (await cdp('Target.getTargets')).targetInfos.find((t) => !known.has(t.targetId) && t.url.endsWith('/ui.html?newtab'));
        if (!t) await sleep(100);
      }
      if (!t) return null;
      const s = (await cdp('Target.attachToTarget', { targetId: t.targetId, flatten: true })).sessionId;
      const state = () => evaluate(cdp, s, vomnibarState).catch(() => null);
      const wait = async (pred, ms) => { const t0 = Date.now(); let v; while (Date.now() - t0 < ms && !pred(v = await state())) await sleep(15); return v; };
      await wait((v) => v?.items.length > 0, 3000);
      await press(s, q.text, 0);
      const typed = await wait((v) => v?.for === q.text, 2000);
      draws.set(s, []);
      await cdp('Runtime.enable', {}, s);
      await cdp('Runtime.addBinding', { name: '__vomDraw' }, s);
      await evaluate(cdp, s, drawRecorder);
      await press(s, '<enter>', 0);
      await sleep(600);
      const drawn = draws.get(s);
      draws.delete(s);
      await cdp('Target.closeTarget', { targetId: t.targetId }).catch(() => null);
      return { typed, drawn };
    }
    if (op === 'vomnibar-draws') {
      const all = [...draws.values()].flat();
      draws.clear();
      return all;
    }
    if (op === 'tabs') {
      const w = await workerSession(cdp, extensionId);
      return evaluate(cdp, w, `Promise.all([chrome.tabs.query({}), chrome.tabs.query({active: true, lastFocusedWindow: true})])
        .then(([all, [a]]) => ({ urls: all.map((t) => t.url), active: a && a.url }))`);
    }
    const s = await session();
    if (op === 'front') { await cdp('Page.bringToFront', {}, s); return true; }
    if (op === 'keys') { await press(s, q.seq || '', Number(q.gap || 30)); return true; }
    if (op === 'click') {
      const p = { x: Number(q.x), y: Number(q.y), button: 'left', clickCount: 1 };
      await within(cdp('Input.dispatchMouseEvent', { ...p, type: 'mousePressed' }, s), 1000);
      await within(cdp('Input.dispatchMouseEvent', { ...p, type: 'mouseReleased' }, s), 1000);
      return true;
    }
    throw new Error('unknown op ' + op);
  };
}
