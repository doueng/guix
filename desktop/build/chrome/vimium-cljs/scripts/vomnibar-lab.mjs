// Screenshots of the Vomnibar at the user's display size (1600x1000 CSS px at 1.6x), over a
// seeded profile: pages with real-looking URLs are served from DevTools request interception
// (no network), visited so they land in history with titles, some bookmarked, some left open.
//   node scripts/vomnibar-lab.mjs OUTDIR [query ...]
// Writes OUTDIR/<scheme>-<n>.png for each query ("" is the empty Vomnibar), light and dark.
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { launch, sleep, evaluate, workerSession } from '../test/lib/chrome.mjs';
import { keyEvents } from '../test/lib/keys.mjs';

export const SITES = [
  ['https://github.com/philc/vimium', 'GitHub - philc/vimium: The hacker\'s browser.', '#24292f', 'open'],
  ['https://news.ycombinator.com/', 'Hacker News', '#ff6600', 'open'],
  ['https://clojure.org/guides/getting_started', 'Clojure - Getting Started', '#5881d8', 'bookmark'],
  ['https://developer.chrome.com/docs/extensions/reference/api/tabs', 'chrome.tabs | API | Chrome for Developers', '#1a73e8', 'open'],
  ['https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'Rick Astley - Never Gonna Give You Up - YouTube', '#ff0000', 'history'],
  ['https://guix.gnu.org/manual/en/html_node/Invoking-guix-shell.html', 'Invoking guix shell (GNU Guix Reference Manual)', '#ffbb00', 'bookmark'],
  ['https://en.wikipedia.org/wiki/Vim_(text_editor)', 'Vim (text editor) - Wikipedia', '#636466', 'history'],
  ['https://github.com/thheller/shadow-cljs', 'GitHub - thheller/shadow-cljs: ClojureScript compilation made easy', '#24292f', 'history'],
  ['https://mail.google.com/mail/u/0/#inbox', 'Inbox (3) - Gmail', '#ea4335', 'open'],
  ['https://docs.github.com/en/actions', 'GitHub Actions documentation - GitHub Docs', '#24292f', 'bookmark'],
  ['https://www.reddit.com/r/emacs/', 'r/emacs', '#ff4500', 'history'],
  ['https://hyprland.org/', 'Hyprland', '#58e1ff', 'history'],
];

const html = (title, color) => `<!doctype html><html><head><meta charset=utf-8><title>${title}</title>`
  + `<link rel=icon href="data:image/svg+xml,${encodeURIComponent(`<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'><rect width='16' height='16' rx='4' fill='${color}'/><text x='8' y='12' font-size='10' font-family='sans-serif' font-weight='700' fill='white' text-anchor='middle'>${title[0]}</text></svg>`)}">`
  + `</head><body style="font:16px system-ui;margin:40px;background:#fafafa"><h1>${title}</h1>`
  + '<p>' + 'Lorem ipsum dolor sit amet, consectetur adipiscing elit. '.repeat(30) + '</p></body></html>';

export async function seededBrowser({ width = 1600, height = 1000, scale = 1.6 } = {}) {
  const b = await launch({ width, height });
  const byUrl = new Map(SITES.map(([u, t, c]) => [u.split('#')[0], html(t, c)]));
  b.onEvent((m) => {
    if (m.method !== 'Fetch.requestPaused') return;
    const body = byUrl.get(m.params.request.url.split('#')[0]) ?? '<!doctype html><title>Not here</title>';
    b.cdp('Fetch.fulfillRequest', { requestId: m.params.requestId, responseCode: 200,
      responseHeaders: [{ name: 'content-type', value: 'text/html; charset=utf-8' }],
      body: Buffer.from(body).toString('base64') }, m.sessionId).catch(() => {});
  });
  const pages = [];
  async function openPage(url) {
    const { targetId } = await b.cdp('Target.createTarget', { url: 'about:blank' });
    const s = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
    await b.cdp('Fetch.enable', { patterns: [{ urlPattern: 'https://*' }] }, s);
    await b.cdp('Page.enable', {}, s);
    await b.cdp('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile: false }, s);
    await b.cdp('Page.navigate', { url }, s);
    await sleep(400);
    const p = { targetId, s, url };
    pages.push(p);
    return p;
  }
  const w = await workerSession(b.cdp, b.extensionId);
  for (const [url, , , kind] of SITES) {
    const p = await openPage(url);
    if (kind === 'bookmark') await evaluate(b.cdp, w, `chrome.bookmarks.create({title: ${JSON.stringify(SITES.find((x) => x[0] === url)[1])}, url: ${JSON.stringify(url)}}).then(() => true)`);
    if (kind !== 'open') await b.cdp('Target.closeTarget', { targetId: p.targetId });
  }
  for (const t of (await b.cdp('Target.getTargets')).targetInfos)
    if (t.type === 'page' && t.url === 'about:blank') await b.cdp('Target.closeTarget', { targetId: t.targetId });
  return { ...b, w, openPage };
}

/** A session on the Vomnibar frame (or popup), once its input is shown. */
export async function vomnibarSession(b) {
  for (let i = 0; i < 40; i++) {
    const { targetInfos } = await b.cdp('Target.getTargets');
    const t = targetInfos.find((t) => t.url.includes('ui.html#vomnibar'));
    if (t) {
      const s = (await b.cdp('Target.attachToTarget', { targetId: t.targetId, flatten: true })).sessionId;
      for (let j = 0; j < 30; j++) {
        if (await evaluate(b.cdp, s, "!!document.getElementById('query') && !document.getElementById('vomnibar').hidden").catch(() => false)) return s;
        await sleep(50);
      }
    }
    await sleep(50);
  }
  throw new Error('no Vomnibar');
}

export async function keys(b, s, seq, gap = 30) {
  for (const [down, up] of keyEvents(seq)) {
    await Promise.race([b.cdp('Input.dispatchKeyEvent', down, s), sleep(1000)]);
    await Promise.race([b.cdp('Input.dispatchKeyEvent', up, s), sleep(1000)]);
    await sleep(gap);
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [out, ...queries] = process.argv.slice(2);
  mkdirSync(out, { recursive: true });
  const b = await seededBrowser();
  try {
    const p = await b.openPage('https://news.ycombinator.com/');
    await b.cdp('Page.bringToFront', {}, p.s);
    await sleep(800);
    for (const scheme of ['light', 'dark']) {
      await b.cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: scheme }] }, p.s);
      for (const [i, q] of (queries.length ? queries : ['']).entries()) {
        await keys(b, p.s, '<esc>o');
        const vs = await vomnibarSession(b);
        await b.cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: scheme }] }, vs);
        console.log('focus', await evaluate(b.cdp, vs, 'document.hasFocus() + " " + document.activeElement.id'));
        // FORCE_FOCUS=1 puts focus back in the input, for builds that lose it on open.
        if (process.env.FORCE_FOCUS) await evaluate(b.cdp, vs, "document.getElementById('query').focus()");
        if (q) await keys(b, vs, q);
        console.log('value', await evaluate(b.cdp, vs, "document.getElementById('query').value + ' active=' + document.activeElement.id"));
        await sleep(500);
        const { data } = await b.cdp('Page.captureScreenshot', { format: 'png' }, p.s);
        const f = join(out, `${scheme}-${i}.png`);
        writeFileSync(f, Buffer.from(data, 'base64'));
        console.log(f);
        await keys(b, p.s, '<esc>');
        await sleep(300);
      }
    }
  } finally { b.close(); setTimeout(() => process.exit(0), 1600); }
}
