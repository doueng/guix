// Serves test/fixtures on 127.0.0.1 (and localhost, the cross-origin frame's host).
// /__ctl/keys?seq=... sends trusted key events to the page through the DevTools pipe;
// /__ctl/click?x=&y= sends a trusted click. The Bombadil spec's custom actions call them.
import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, normalize } from 'node:path';
import { keyEvents } from './keys.mjs';
import { ROOT, sleep, evaluate, workerSession } from './chrome.mjs';

const FIXTURES = join(ROOT, 'test/fixtures');
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript' };

// What the Vomnibar frame shows: whether it is open with the input focused, the input's
// value and the rows (as the page cannot look into the extension's frame).
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

/** Control handler that drives whichever page SESSION() returns. */
// An input event sent to a frame that the event itself removes (Escape closing the
// Vomnibar) is never acknowledged, so input calls give up after a second.
const within = (p, ms) => Promise.race([p, sleep(ms)]);

export function controller(cdp, session, extensionId) {
  const frames = new Map();
  return async (op, q) => {
    if (op === 'vomnibar') {
      const { targetInfos } = await cdp('Target.getTargets');
      const t = targetInfos.find((t) => t.url.includes('ui.html#vomnibar'));
      if (!t) return { open: false, focused: false, value: '', items: [] };
      if (!frames.has(t.targetId)) frames.set(t.targetId, (await cdp('Target.attachToTarget', { targetId: t.targetId, flatten: true })).sessionId);
      return evaluate(cdp, frames.get(t.targetId), vomnibarState).catch(() => ({ open: false, focused: false, value: '', items: [] }));
    }
    if (op === 'tabs') {
      const w = await workerSession(cdp, extensionId);
      return evaluate(cdp, w, `Promise.all([chrome.tabs.query({}), chrome.tabs.query({active: true, lastFocusedWindow: true})])
        .then(([all, [a]]) => ({ urls: all.map((t) => t.url), active: a && a.url }))`);
    }
    const s = await session();
    if (op === 'front') { await cdp('Page.bringToFront', {}, s); return true; }
    if (op === 'keys') {
      for (const [down, up] of keyEvents(q.seq || '')) {
        await within(cdp('Input.dispatchKeyEvent', down, s), 1000);
        await within(cdp('Input.dispatchKeyEvent', up, s), 1000);
        await sleep(Number(q.gap || 30));
      }
      return true;
    }
    if (op === 'click') {
      const p = { x: Number(q.x), y: Number(q.y), button: 'left', clickCount: 1 };
      await within(cdp('Input.dispatchMouseEvent', { ...p, type: 'mousePressed' }, s), 1000);
      await within(cdp('Input.dispatchMouseEvent', { ...p, type: 'mouseReleased' }, s), 1000);
      return true;
    }
    throw new Error('unknown op ' + op);
  };
}
