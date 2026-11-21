// Branded Chrome ignores --load-extension, so use Extensions.loadUnpacked (needs --enable-unsafe-extension-debugging and --remote-debugging-pipe).
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

export const ROOT = resolve(import.meta.dirname, '../..');
const flatpak = spawnSync('flatpak', ['info', 'com.google.Chrome'], { stdio: 'ignore' }).status === 0;
const chromeBin = process.env.CHROME || (flatpak ? null : ['chromium', 'chromium-browser'].find((c) => spawnSync('which', [c]).status === 0));
export const available = !!(flatpak || chromeBin);

// headed: a real window, only inside desktop/bin/test-display so it never reaches the user's screen.
export async function launch({ port, width = 1024, height = 768, ext = join(ROOT, 'ext'), headed = false, flags: extra = [] } = {}) {
  if (headed && !process.env.TEST_DISPLAY) throw new Error('headed Chrome runs only inside a test display: test-display -- node ...');
  const profile = mkdtempSync(join(tmpdir(), 'vimium-cljs-e2e-'));
  const flags = [
    ...(headed ? ['--ozone-platform=wayland'] : ['--headless=new']), `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    '--disable-sync', '--disable-background-networking', '--remote-debugging-pipe', '--enable-unsafe-extension-debugging',
    `--window-size=${width},${height}`, '--js-flags=--max-old-space-size=512',
    ...(port ? [`--remote-debugging-port=${port}`, '--remote-allow-origins=*'] : []),
    ...extra, 'about:blank',
  ];
  const [cmd, args] = chromeBin
    ? [chromeBin, flags]
    : ['flatpak', ['run', '--no-documents-portal', `--filesystem=${ROOT}:ro`, `--filesystem=${ext}`, `--filesystem=${profile}`,
       ...(headed ? ['--socket=wayland', '--nosocket=x11'] : []), 'com.google.Chrome', ...flags]];
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
  const { id: extensionId } = await cdp('Extensions.loadUnpacked', { path: ext });
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

export async function workerSession(cdp, extensionId) {
  for (let i = 0; i < 50; i++) {
    const { targetInfos } = await cdp('Target.getTargets');
    const sw = targetInfos.find((t) => t.type === 'service_worker' && t.url.includes(extensionId));
    if (sw) return (await cdp('Target.attachToTarget', { targetId: sw.targetId, flatten: true })).sessionId;
    await sleep(200);
  }
  throw new Error('extension service worker not found');
}
