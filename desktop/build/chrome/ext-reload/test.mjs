// End to end: the ext-reload hub, its helper extension and the ext-reload command against a
// throwaway headless Flatpak Chrome. A target extension is built (its build rewrites bg.js),
// reloaded from disk, and the page its content script matches is reloaded. Run: make ext-reload-test
import { spawn, spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { createHash, generateKeyPairSync } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';

const HERE = import.meta.dirname;
const BIN = resolve(HERE, '../../../bin/ext-reload');
const PORT = 17357 + Math.floor(Math.random() * 1000);
const tmp = mkdtempSync(join(tmpdir(), 'ext-reload-test-'));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// The helper, pointed at this test's hub port.
const helperSrc = join(tmp, 'helper-src');
cpSync(join(HERE, 'reloader'), helperSrc, { recursive: true });
const bg = join(helperSrc, 'bg.js');
writeFileSync(bg, readFileSync(bg, 'utf8').replace('127.0.0.1:7357', `127.0.0.1:${PORT}`));
const helperDir = join(tmp, 'helper');

// A page server that counts loads, and a target extension whose content script matches it.
let loads = 0;
const web = createServer((req, res) => { loads++; res.end('<title>t</title>page'); }).listen(0, '127.0.0.1');
await new Promise((r) => web.once('listening', r));
const webPort = web.address().port;
const proj = join(tmp, 'target');
mkdirSync(join(proj, 'ext'), { recursive: true });
// A manifest key fixes the id, as for the extensions in desktop/build/chrome; ext-reload derives the same id.
const key = generateKeyPairSync('rsa', { modulusLength: 2048 }).publicKey.export({ type: 'spki', format: 'der' });
const keyId = [...createHash('sha256').update(key).digest().subarray(0, 16)]
  .map((b) => String.fromCharCode(97 + (b >> 4), 97 + (b & 15))).join('');
writeFileSync(join(proj, 'ext/manifest.json'), JSON.stringify({
  manifest_version: 3, name: 'ext-reload test target', version: '1.0', key: key.toString('base64'),
  background: { service_worker: 'bg.js' },
  content_scripts: [{ matches: ['http://127.0.0.1/*'], js: ['cs.js'] }],
}));
writeFileSync(join(proj, 'ext/bg.js'), "self.MARK = 'one';");
writeFileSync(join(proj, 'ext/cs.js'), '');
const config = join(tmp, 'extensions.edn');
writeFileSync(config, `{"target" {:dir "${proj}" :ext "ext" :build ["bb" "-e" "(spit \\"ext/bg.js\\" \\"self.MARK = 'two';\\")"]}
 "missing" {:dir "${proj}" :ext "ext2"}}`);
mkdirSync(join(proj, 'ext2'));
writeFileSync(join(proj, 'ext2/manifest.json'), JSON.stringify({ manifest_version: 3, name: 'never loaded', version: '1' }));
const env = { ...process.env, EXT_RELOAD_PORT: String(PORT), EXT_RELOAD_CONFIG: config,
              EXT_RELOAD_HELPER_SRC: helperSrc, EXT_RELOAD_HELPER_DIR: helperDir };
const cli = (...args) => spawnSync('bb', [BIN, ...args], { env, encoding: 'utf8' });

const hub = spawn('bb', [BIN, 'serve'], { env, stdio: ['ignore', 'inherit', 'inherit'] });
let chrome;
try {
  for (let i = 0; i < 100 && !existsSync(join(helperDir, 'manifest.json')); i++) await sleep(100);
  assert.ok(existsSync(join(helperDir, 'manifest.json')), 'hub installs the helper');
  let r = cli('target', '--no-build');
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /helper is not connected/, 'says so when Chrome has no helper');

  const profile = join(tmp, 'profile');
  chrome = spawn('flatpak', ['run', `--filesystem=${tmp}`, 'com.google.Chrome', '--headless=new', `--user-data-dir=${profile}`,
    '--no-first-run', '--disable-gpu', '--remote-debugging-pipe', '--enable-unsafe-extension-debugging', 'about:blank'],
    { stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'] });
  let id = 0, buf = '';
  const waiting = new Map();
  chrome.stdio[4].on('data', (d) => {
    buf += d;
    for (let i; (i = buf.indexOf('\0')) >= 0; buf = buf.slice(i + 1)) {
      const m = JSON.parse(buf.slice(0, i));
      const w = waiting.get(m.id);
      if (w) { waiting.delete(m.id); m.error ? w.rej(new Error(m.error.message)) : w.res(m.result); }
    }
  });
  const cdp = (method, params = {}, sessionId) => new Promise((res, rej) => {
    waiting.set(++id, { res, rej });
    chrome.stdio[3].write(JSON.stringify({ id, method, params, ...(sessionId && { sessionId }) }) + '\0');
  });
  const { id: helperId } = await cdp('Extensions.loadUnpacked', { path: helperDir });
  assert.equal(helperId, 'hhphiidacaejhbolcnboolfapncammkf', 'the manifest key fixes the helper id');
  const { id: targetId } = await cdp('Extensions.loadUnpacked', { path: join(proj, 'ext') });
  assert.equal(targetId, keyId, 'the manifest key fixes the target id');
  // A copy of the same name from another folder, without the key.
  const old = join(tmp, 'old-ext');
  cpSync(join(proj, 'ext'), old, { recursive: true });
  const { key: _k, ...oldManifest } = JSON.parse(readFileSync(join(old, 'manifest.json'), 'utf8'));
  writeFileSync(join(old, 'manifest.json'), JSON.stringify(oldManifest));
  const { id: oldId } = await cdp('Extensions.loadUnpacked', { path: old });
  assert.notEqual(oldId, keyId);
  await cdp('Target.createTarget', { url: `http://127.0.0.1:${webPort}/` });
  const mark = async () => {
    for (let i = 0; i < 50; i++) {
      const sw = (await cdp('Target.getTargets')).targetInfos.find((t) => t.type === 'service_worker' && t.url.includes(targetId));
      if (sw) {
        const { sessionId } = await cdp('Target.attachToTarget', { targetId: sw.targetId, flatten: true });
        const v = (await cdp('Runtime.evaluate', { expression: 'self.MARK', returnByValue: true }, sessionId)).result.value;
        if (v) return v; // a just-started worker may not have run bg.js yet
      }
      await sleep(200);
    }
  };
  assert.equal(await mark(), 'one');
  for (let i = 0; i < 100; i++) {
    const s = await fetch(`http://127.0.0.1:${PORT}/status`, { headers: { 'x-ext-reload': '1' } }).then((r) => r.json());
    if (s.helpers) break;
    await sleep(200);
  }
  assert.equal((await fetch(`http://127.0.0.1:${PORT}/status`)).status, 403, 'requests without the header are refused');
  const before = loads;

  r = cli('target');
  assert.equal(r.status, 0, r.stderr + r.stdout);
  assert.match(r.stdout, new RegExp(`reloaded ext-reload test target 1\\.0 \\(${keyId}\\)`), 'reloaded by its key id');
  assert.doesNotMatch(r.stdout, new RegExp(oldId), 'the old copy of the same name is not the one reloaded');
  assert.match(r.stdout, /reloaded 1 tab$/m);
  assert.equal(await mark(), 'two', 'Chrome reread the rebuilt extension from disk');
  for (let i = 0; i < 30 && loads === before; i++) await sleep(100);
  assert.equal(loads, before + 1, 'the matching tab was reloaded');

  r = cli('missing');
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /has not loaded "never loaded" from this repo\. Load it once with: ext-reload load missing/);

  r = cli('list');
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /ext-reload test target/);
  console.log('ext-reload test: ok');
} finally {
  chrome?.kill('SIGTERM');
  hub.kill('SIGTERM');
  web.close();
  setTimeout(() => { rmSync(tmp, { recursive: true, force: true }); process.exit(); }, 1500);
}
