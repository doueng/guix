// Starts one headless Chrome with a throwaway profile and loads ext/ over the DevTools pipe.
// Chrome is the com.google.Chrome flatpak when present, else $CHROME (a chromium binary).
// Branded Chrome ignores --load-extension, so this uses Extensions.loadUnpacked (needs
// --enable-unsafe-extension-debugging and --remote-debugging-pipe). With `port` it also
// opens a DevTools port for Bombadil's test-external.
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

export const ROOT = resolve(import.meta.dirname, '../..');
const flatpak = spawnSync('flatpak', ['info', 'com.google.Chrome'], { stdio: 'ignore' }).status === 0;
const chromeBin = process.env.CHROME || (flatpak ? null : ['chromium', 'chromium-browser'].find((c) => spawnSync('which', [c]).status === 0));
export const available = !!(flatpak || chromeBin);

export async function launch({ port, width = 1024, height = 768 } = {}) {
  const profile = mkdtempSync(join(tmpdir(), 'vimium-cljs-e2e-'));
  const flags = [
    '--headless=new', `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    '--disable-sync', '--disable-background-networking', '--remote-debugging-pipe', '--enable-unsafe-extension-debugging',
    `--window-size=${width},${height}`, '--js-flags=--max-old-space-size=512',
    ...(port ? [`--remote-debugging-port=${port}`, '--remote-allow-origins=*'] : []),
    'about:blank',
  ];
  const [cmd, args] = chromeBin
    ? [chromeBin, flags]
    : ['flatpak', ['run', `--filesystem=${ROOT}:ro`, `--filesystem=${profile}`, 'com.google.Chrome', ...flags]];
  const proc = spawn(cmd, args, { stdio: ['ignore', 'ignore', process.env.CHROME_LOG ? 'inherit' : 'ignore', 'pipe', 'pipe'] });
  let id = 0, buf = '';
  const waiting = new Map();
  const listeners = new Set();
  proc.stdio[4].on('data', (d) => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\0')) >= 0) {
      const msg = JSON.parse(buf.slice(0, i));
      buf = buf.slice(i + 1);
      if (msg.id && waiting.has(msg.id)) {
        const w = waiting.get(msg.id);
        waiting.delete(msg.id);
        msg.error ? w.reject(new Error(`${w.method}: ${msg.error.message}`)) : w.resolve(msg.result);
      } else for (const l of listeners) l(msg);
    }
  });
  const cdp = (method, params = {}, sessionId) =>
    new Promise((res, rej) => {
      const msgId = ++id;
      waiting.set(msgId, { resolve: res, reject: rej, method });
      proc.stdio[3].write(JSON.stringify({ id: msgId, method, params, ...(sessionId && { sessionId }) }) + '\0');
    });
  const { id: extensionId } = await cdp('Extensions.loadUnpacked', { path: join(ROOT, 'ext') });
  const close = () => {
    proc.stdio[3].destroy();
    proc.stdio[4].destroy();
    proc.unref();
    try { proc.kill('SIGTERM'); } catch {}
    setTimeout(() => { try { proc.kill('SIGKILL'); } catch {} rmSync(profile, { recursive: true, force: true }); }, 1500);
  };
  return { cdp, extensionId, close, onEvent: (f) => listeners.add(f), proc };
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function evaluate(cdp, sessionId, expression) {
  const r = await cdp('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, sessionId);
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
  return r.result.value;
}

/** A session on the extension's service worker. */
export async function workerSession(cdp, extensionId) {
  for (let i = 0; i < 50; i++) {
    const { targetInfos } = await cdp('Target.getTargets');
    const sw = targetInfos.find((t) => t.type === 'service_worker' && t.url.includes(extensionId));
    if (sw) return (await cdp('Target.attachToTarget', { targetId: sw.targetId, flatten: true })).sessionId;
    await sleep(200);
  }
  throw new Error('extension service worker not found');
}

export async function setSettings(cdp, extensionId, settings) {
  for (let i = 0; ; i++) {
    try {
      const s = await workerSession(cdp, extensionId);
      return await evaluate(cdp, s, `chrome.storage.local.set({ settings: ${JSON.stringify(settings)} }).then(() => true)`);
    } catch (e) {
      // The worker may still be starting (no chrome yet); try again.
      if (i > 20) throw e;
      await sleep(250);
    }
  }
}
