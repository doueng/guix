// Runs Bombadil against the fixture site with the extension loaded: one headless Chrome
// (flatpak) loads ext/ over the DevTools pipe and also opens a DevTools port, and Bombadil's
// test-external drives a page through that port while test/bombadil/spec.ts checks the
// properties. Key presses come back through the fixture server's /__ctl/keys, which sends
// trusted key events over the pipe.
//   node test/bombadil/run.mjs [--time 3m] [--seed-dir DIR]
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { launch, available, sleep, ROOT } from '../lib/chrome.mjs';
import { startServer, controller } from '../lib/server.mjs';

const arg = (name, dflt) => { const i = process.argv.indexOf(name); return i > 0 ? process.argv[i + 1] : dflt; };
const time = arg('--time', '3m');
const out = arg('--output', mkdtempSync(join(tmpdir(), 'vimium-cljs-bombadil-')));

function bombadilBin() {
  if (process.env.BOMBADIL) return process.env.BOMBADIL;
  if (spawnSync('which', ['bombadil']).status === 0) return 'bombadil';
  // The pinned Guix package from the guix repo (T174).
  const r = spawnSync('guix', ['build', '-L', 'modules', '-e', '(@ (engstrand packages bombadil) bombadil)'],
    { cwd: process.env.GUIX_REPO || join(process.env.HOME, 'guix'), encoding: 'utf8' });
  if (r.status !== 0) throw new Error('cannot build bombadil: ' + r.stderr);
  return join(r.stdout.trim().split('\n').pop(), 'bin/bombadil');
}

if (!available) { console.error('bombadil: no headless Chrome (install the com.google.Chrome flatpak or set CHROME)'); process.exit(2); }
const bin = bombadilBin();
const devtoolsPort = 9200 + Math.floor(Math.random() * 600);
const b = await launch({ port: devtoolsPort });

// Key events go to the page Bombadil drives: the fixture page target, attached over the pipe.
let session = null, sessionTarget = null;
async function pageSession() {
  const { targetInfos } = await b.cdp('Target.getTargets');
  const t = targetInfos.find((t) => t.type === 'page' && t.url.startsWith(`http://127.0.0.1:${port}`) && !ours.has(t.targetId));
  if (!t) throw new Error('no fixture page');
  if (t.targetId !== sessionTarget) {
    session = (await b.cdp('Target.attachToTarget', { targetId: t.targetId, flatten: true })).sessionId;
    sessionTarget = t.targetId;
  }
  return session;
}
const { server, port } = await startServer(controller(b.cdp, pageSession, b.extensionId));
// Background tabs for the Vomnibar properties to find and switch to.
const ours = new Set();
for (const path of ['tube.html', 'notes.html'])
  ours.add((await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/${path}`, background: true })).targetId);
// Wait for the DevTools port.
for (let i = 0; i < 50; i++) {
  try { await fetch(`http://127.0.0.1:${devtoolsPort}/json/version`); break; } catch { await sleep(200); }
}
const origin = `http://127.0.0.1:${port}/`;
console.log(`bombadil: ${time} on ${origin}, output ${out}`);
const child = spawn(bin, ['browser', 'test-external', '--remote-debugger', `http://127.0.0.1:${devtoolsPort}`, '--create-target',
  '--allow-url', `http://127.0.0.1:${port}/*`, '--allow-url', `http://localhost:${port}/*`,
  '--time-limit', time, '--exit-on-violation', '--output-path', out, '--output-path-overwrite',
  origin, join(ROOT, 'test/bombadil/spec.ts')], { stdio: 'inherit' });
const code = await new Promise((ok) => child.on('exit', (c) => ok(c ?? 1)));
b.close();
server.closeAllConnections();
server.close();
console.log(code === 0 ? 'bombadil: no property violated' : `bombadil: exit ${code}; trace in ${out}`);
setTimeout(() => process.exit(code), 2000);
