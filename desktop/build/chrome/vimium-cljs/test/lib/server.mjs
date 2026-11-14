// Serves test/fixtures on 127.0.0.1 (and localhost, the cross-origin frame's host).
// /__ctl/keys?seq=... sends trusted key events to the page through the DevTools pipe;
// /__ctl/click?x=&y= sends a trusted click. The Bombadil spec's custom actions call them.
import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, normalize } from 'node:path';
import { keyEvents } from './keys.mjs';
import { ROOT, sleep } from './chrome.mjs';

const FIXTURES = join(ROOT, 'test/fixtures');
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript' };

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
export function controller(cdp, session) {
  return async (op, q) => {
    const s = await session();
    if (op === 'keys') {
      for (const [down, up] of keyEvents(q.seq || '')) {
        await cdp('Input.dispatchKeyEvent', down, s);
        await cdp('Input.dispatchKeyEvent', up, s);
        await sleep(Number(q.gap || 30));
      }
      return true;
    }
    if (op === 'click') {
      const p = { x: Number(q.x), y: Number(q.y), button: 'left', clickCount: 1 };
      await cdp('Input.dispatchMouseEvent', { ...p, type: 'mousePressed' }, s);
      await cdp('Input.dispatchMouseEvent', { ...p, type: 'mouseReleased' }, s);
      return true;
    }
    throw new Error('unknown op ' + op);
  };
}
