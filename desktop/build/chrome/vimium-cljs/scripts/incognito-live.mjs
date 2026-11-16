// Screenshots a headed Chrome on an Xvfb while xdotool, a real keyboard to Chrome, opens an incognito
// window with Ctrl+Shift+N and types at once: the incognito new tab page is the Vomnibar, and keys work on a
// page there. Nothing reaches your desktop. DISPLAY=:99 node scripts/incognito-live.mjs DIR
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { launch, sleep, evaluate, ROOT } from '../test/lib/chrome.mjs';
import { startServer } from '../test/lib/server.mjs';

const out = process.argv[2] || '/tmp/incognito-live';
mkdirSync(out, { recursive: true });
const display = process.env.DISPLAY || ':99';
const x = (...a) => execFileSync('xdotool', a, { env: { ...process.env, DISPLAY: display } }).toString().trim();
const shot = (name) => {
  const f = join(out, name + '.png');
  execFileSync('sh', ['-c', `xwd -root -silent -display ${display} | convert xwd:- ${f}`]);
  console.log('shot', f);
};

const b = await launch({ display, width: 1200, height: 760 });
await b.cdp('Extensions.loadUnpacked', { path: join(ROOT, 'ext'), enableInIncognito: true });
const { server, port } = await startServer(async () => null);
const report = {};
const pages = async () => {
  const { targetInfos } = await b.cdp('Target.getTargets');
  return Promise.all(targetInfos.filter((t) => t.type === 'page').map(async (t) => {
    const s = (await b.cdp('Target.attachToTarget', { targetId: t.targetId, flatten: true })).sessionId;
    return evaluate(b.cdp, s, `({ context: '${t.browserContextId.slice(0, 6)}', url: location.href,
      query: document.getElementById('query')?.value, focused: document.hasFocus(), scrollY })`)
      .catch(() => ({ context: t.browserContextId.slice(0, 6), url: t.url }));
  }));
};
try {
  await sleep(2000);
  x('mousemove', '600', '400'); x('click', '1'); await sleep(300);
  const t0 = Date.now();
  x('key', 'ctrl+shift+n');
  await sleep(300);
  x('type', '--delay', '60', 'wikipedia');
  await sleep(700);
  report.typedMs = Date.now() - t0;
  report.newIncognitoWindow = await pages();
  shot('1-incognito-new-tab-typed');
  x('key', 'Escape'); await sleep(200);
  x('type', '--delay', '40', `127.0.0.1:${port}/`); x('key', 'Return');
  await sleep(2000);
  x('type', '--delay', '120', 'jj'); await sleep(500);
  report.afterJ = await pages();
  x('type', 'f'); await sleep(900);
  shot('2-incognito-page-hints');
  x('key', 'Escape');
} finally {
  writeFileSync(join(out, 'report.json'), JSON.stringify(report, null, 1));
  console.log(JSON.stringify(report, null, 1));
  b.close(); server.close(); setTimeout(() => process.exit(0), 1600);
}
