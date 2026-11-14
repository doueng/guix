// Bombadil explores the fixture site in headless Chrome with the extension loaded unpacked, checking spec.ts.
//
//   node bombadil/run.mjs [--time 300] [--seed N] [--page FIXTURE.html] [--reproduce DIR] [--headed]
//
// One Chrome: $CHROME (a chromium binary) when set, else the com.google.Chrome flatpak. This script owns it over a
// CDP pipe (to load the extension and drive the popup); Bombadil attaches over a port. The fixtures are served from
// test/fixtures on localhost and 127.0.0.1 (two sites, so a frame can be cross-origin), with no network.
// Bombadil is $BOMBADIL, else bombadil on PATH, else the pinned Guix package in ~/guix (T174).
// --seed picks the starting settings (mode, and whether localhost, where it starts, is disabled); Bombadil's own choices are
// recorded in its trace, not seeded. --page keeps the exploration on one fixture. A failure prints the seed and the
// trace directory; --reproduce DIR replays that trace from the same starting settings.
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, normalize, resolve } from 'node:path';

const ROOT = resolve(import.meta.dirname, '..');
const FIXTURES = join(ROOT, 'test/fixtures');
const PROBE = readFileSync(join(import.meta.dirname, 'probe.js'));
const SPEC = join(import.meta.dirname, 'spec.ts');
// Pages that only make sense inside a frame of another page.
const FRAME_ONLY = new Set(['consent-message.html', 'index.html']);

function options(argv) {
  const o = { time: 300, seed: Math.floor(Math.random() * 2 ** 31), page: null, reproduce: null, headed: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--time') o.time = Number(argv[++i]);
    else if (a === '--reproduce') o.reproduce = resolve(argv[++i]);
    else if (a === '--headed') o.headed = true;
    else if (a === '--page') o.page = argv[++i];
    else if (a === '--seed') o.seed = Number(argv[++i]);
    else throw new Error(`unknown argument ${a}`);
  }
  if (!(o.time > 0)) throw new Error('--time needs a number of seconds');
  if (!Number.isInteger(o.seed)) throw new Error('--seed needs an integer');
  if (o.reproduce) Object.assign(o, JSON.parse(readFileSync(join(o.reproduce, 'harness.json'), 'utf8')));
  return o;
}

function bombadilBin() {
  if (process.env.BOMBADIL) return process.env.BOMBADIL;
  if (spawnSync('which', ['bombadil']).status === 0) return 'bombadil';
  const guix = process.env.GUIX_CHECKOUT || join(process.env.HOME, 'guix');
  const r = spawnSync('guix', ['build', '-L', join(guix, 'modules'), '-e', '(@ (engstrand packages bombadil) bombadil)'], { encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`no bombadil: set BOMBADIL, put it on PATH, or build it from ${guix}: ${r.stderr}`);
  return join(r.stdout.trim().split('\n').pop(), 'bin/bombadil');
}

const pages = () => readdirSync(FIXTURES).filter((f) => f.endsWith('.html') && !FRAME_ONLY.has(f)).sort();

function nav(port, host) {
  const other = host === 'localhost' ? '127.0.0.1' : 'localhost';
  const links = pages().map((p) => `<a href="/${p}">${p.replace('.html', '')}</a>`).join(' ');
  return `<nav id="probe-nav" style="margin:2em 0;line-height:2">${links} <a href="http://${other}:${port}/">other site</a></nav>`;
}

/** The starting settings for a seed (mulberry32). */
function startSettings(seed) {
  let s = seed >>> 0;
  const rnd = () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 2 ** 32;
  };
  return { mode: rnd() < 0.5 ? 'reject' : 'accept', disabledSites: rnd() < 0.25 ? ['localhost'] : [] };
}

// ---- Chrome over a CDP pipe -----------------------------------------------------------------------------------

function launchChrome(profile, headed) {
  const flags = [
    ...(headed ? [] : ['--headless=new']), `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check',
    '--disable-gpu', '--disable-sync', '--disable-background-networking', '--disable-component-update',
    '--remote-debugging-pipe', '--remote-debugging-port=0', '--enable-unsafe-extension-debugging', 'about:blank',
  ];
  const [cmd, args] = process.env.CHROME
    ? [process.env.CHROME, flags]
    : ['flatpak', ['run', `--filesystem=${ROOT}:ro`, `--filesystem=${profile}`, 'com.google.Chrome', ...flags]];
  const browser = spawn(cmd, args, { stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'] });
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
  const cdp = (method, params = {}, sessionId) =>
    new Promise((res, rej) => {
      const msgId = ++id;
      waiting.set(msgId, { resolve: res, reject: rej, method });
      browser.stdio[3].write(JSON.stringify({ id: msgId, method, params, ...(sessionId && { sessionId }) }) + '\0');
    });
  return { browser, cdp };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function debuggerPort(profile) {
  const file = join(profile, 'DevToolsActivePort');
  for (let i = 0; i < 100; i++) {
    if (existsSync(file)) {
      const port = readFileSync(file, 'utf8').split('\n')[0];
      if (port) return Number(port);
    }
    await sleep(100);
  }
  throw new Error('Chrome did not open its debugging port');
}

function harness(cdp, extensionId, site) {
  const evaluate = async (sessionId, expression) => {
    const r = await cdp('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, sessionId);
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
  };
  const sessions = new Map();
  async function attach(match) {
    for (let i = 0; i < 50; i++) {
      const { targetInfos } = await cdp('Target.getTargets');
      const t = targetInfos.find(match);
      if (t) {
        if (!sessions.has(t.targetId)) {
          const { sessionId } = await cdp('Target.attachToTarget', { targetId: t.targetId, flatten: true });
          sessions.set(t.targetId, sessionId);
        }
        return { targetId: t.targetId, sessionId: sessions.get(t.targetId) };
      }
      await sleep(100);
    }
    return null;
  }
  const worker = async () => {
    const w = await attach((t) => t.type === 'service_worker' && t.url.startsWith(`chrome-extension://${extensionId}/`));
    if (!w) throw new Error('extension service worker not found');
    return w.sessionId;
  };

  async function settings(host) {
    const s = await evaluate(await worker(), "chrome.storage.local.get('settings').then((r) => r.settings ?? null)");
    const mode = s?.mode === 'reject' ? 'reject' : 'accept';
    const disabled = Array.isArray(s?.disabledSites) && s.disabledSites.includes(host.replace(/^www\./, ''));
    return { mode, disabled };
  }

  const failed = (error) => {
    console.error(`harness: popup did not open: ${error}`);
    return { ok: false, status: null, error };
  };

  // Opens the real popup for the active tab, reads its status line, and maybe clicks one of its controls.
  let busy = Promise.resolve();
  function popup(op) {
    const run = busy.then(async () => {
      // The popup belongs to the focused window's active tab: make that Bombadil's fixture tab.
      const opened = await evaluate(await worker(), `(async () => {
        const tabs = await chrome.tabs.query({});
        const t = tabs.find((x) => /^http:\\/\\/(localhost|127\\.0\\.0\\.1):${site()}\\//.test(x.url || ''));
        if (!t) return 'no fixture tab';
        await chrome.windows.update(t.windowId, { focused: true });
        await chrome.tabs.update(t.id, { active: true });
        return chrome.action.openPopup({ windowId: t.windowId }).then(() => 'ok', (e) => String(e.message));
      })()`);
      if (opened !== 'ok') return failed(opened);
      const p = await attach((t) => t.type === 'page' && t.url === `chrome-extension://${extensionId}/src/popup.html`);
      if (!p) return failed('popup target not found');
      let status = null;
      for (let i = 0; i < 50 && (status === null || status === '...'); i++) {
        status = await evaluate(p.sessionId, "document.getElementById('status')?.textContent ?? null").catch(() => null);
        if (status === null || status === '...') await sleep(50);
      }
      const click = {
        'toggle-site': "document.getElementById('enabled')",
        accept: "document.querySelector('input[name=mode][value=accept]')",
        reject: "document.querySelector('input[name=mode][value=reject]')",
      }[op];
      // A control that is already in that state fires no change; close the popup ourselves then.
      const changed = click
        ? await evaluate(p.sessionId, `(() => { const el = ${click}; if (!el || el.disabled || (el.type === 'radio' && el.checked)) return false; el.click(); return true; })()`).catch(() => false)
        : false;
      if (!changed) await cdp('Target.closeTarget', { targetId: p.targetId }).catch(() => {});
      sessions.delete(p.targetId);
      return { ok: true, status, changed };
    });
    busy = run.catch(() => {});
    return run;
  }
  async function setSettings(s) {
    await evaluate(await worker(), `chrome.storage.local.set({ settings: ${JSON.stringify(s)} }).then(() => true)`);
  }
  return { settings, setSettings, popup };
}

// ---- the fixture site ----------------------------------------------------------------------------------------

function startServer(control) {
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    const host = (req.headers.host || '').split(':')[0];
    const port = server.address().port;
    try {
      if (url.pathname === '/__probe.js') return res.writeHead(200, { 'content-type': 'text/javascript' }).end(PROBE);
      if (url.pathname === '/__state') return json(res, await control.settings(host));
      if (url.pathname === '/__ctl/popup') return json(res, await control.popup(url.searchParams.get('op') || 'read'));
      if (url.pathname === '/favicon.ico') return res.writeHead(204).end();
      if (url.pathname === '/') {
        return html(res, `<!doctype html><html><head><meta charset="utf-8"><title>fixtures</title></head><body><h1>Consent fixtures on ${host}</h1>${nav(port, host)}</body></html>`);
      }
      const rel = normalize(url.pathname).replace(/^\/+/, '');
      const file = join(FIXTURES, rel);
      if (!file.startsWith(FIXTURES + '/') || !existsSync(file)) return res.writeHead(404).end();
      let page = readFileSync(file, 'utf8');
      const probe = '<script src="/__probe.js"></script>';
      page = /<head>/i.test(page) ? page.replace(/<head>/i, `<head>${probe}`) : probe + page;
      if (!FRAME_ONLY.has(rel)) page = page.replace(/<\/body>/i, `${nav(port, host)}</body>`);
      return html(res, page);
    } catch (e) {
      res.writeHead(500, { 'content-type': 'text/plain' }).end(String(e?.stack || e));
    }
  });
  return new Promise((ok) => server.listen(0, '127.0.0.1', () => ok(server)));
}
const json = (res, v) => res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' }).end(JSON.stringify(v));
const html = (res, s) => res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' }).end(s);

// ---- main ---------------------------------------------------------------------------------------------------

async function main() {
  const o = options(process.argv.slice(2));
  const bombadil = bombadilBin();
  const profile = mkdtempSync(join(tmpdir(), 'cookie-autoaccept-bombadil-'));
  const out = mkdtempSync(join(tmpdir(), o.reproduce ? 'cookie-autoaccept-replay-' : 'cookie-autoaccept-trace-'));
  const { browser, cdp } = launchChrome(profile, o.headed);
  let server, code = 1;
  const cleanup = () => {
    for (const s of browser.stdio ?? []) s?.destroy();
    browser.kill();
    server?.close();
    rmSync(profile, { recursive: true, force: true });
  };
  process.on('SIGINT', () => { cleanup(); process.exit(130); });
  try {
    const port = await debuggerPort(profile);
    const { id: extensionId } = await cdp('Extensions.loadUnpacked', { path: ROOT });
    const ctl = harness(cdp, extensionId, () => server.address().port);
    const start = startSettings(o.seed);
    await ctl.setSettings(start);
    server = await startServer(ctl);
    const site = server.address().port;
    const origin = `http://localhost:${site}/${o.page ?? ''}`;
    const allowed = o.page
      ? [`http://localhost:${site}/${o.page}*`]
      : [`http://localhost:${site}/*`, `http://127.0.0.1:${site}/*`];
    const args = [
      'browser', 'test-external', '--remote-debugger', `http://127.0.0.1:${port}`, '--create-target',
      ...allowed.flatMap((u) => ['--allow-url', u]),
      ...(o.reproduce
        ? ['--reproduce', join(o.reproduce, 'trace.jsonl'), '--output-path', out, '--output-path-overwrite']
        : ['--output-path', out, '--output-path-overwrite', '--exit-on-violation', '--time-limit', `${o.time}s`]),
      origin, SPEC,
    ];
    const started = Date.now();
    writeFileSync(join(out, 'harness.json'), JSON.stringify({ seed: o.seed, page: o.page }) + '\n');
    console.log(`bombadil: ${o.reproduce ? `replaying ${o.reproduce}` : `${o.time} s`} on ${origin}, seed ${o.seed} (${JSON.stringify(start)})`);
    code = await new Promise((done) => {
      const b = spawn(bombadil, args, { stdio: 'inherit', env: { ...process.env, RUST_LOG: process.env.RUST_LOG || 'warn' } });
      b.on('exit', (c, sig) => done(c ?? (sig ? 1 : 0)));
    });
    const secs = ((Date.now() - started) / 1000).toFixed(0);
    if (code === 0) console.log(`bombadil: passed in ${secs} s; trace in ${out}`);
    else {
      console.log(`bombadil: FAILED (exit ${code}) after ${secs} s, seed ${o.seed}`);
      console.log(`bombadil: trace ${out}`);
      console.log(`bombadil: replay with  npm run bombadil -- --reproduce ${out}`);
    }
  } finally {
    cleanup();
  }
  process.exit(code);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
