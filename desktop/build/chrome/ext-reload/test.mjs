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

const helperSrc = join(tmp, 'helper-src');
cpSync(join(HERE, 'reloader'), helperSrc, { recursive: true });
const bg = join(helperSrc, 'bg.js');
writeFileSync(bg, readFileSync(bg, 'utf8').replace('127.0.0.1:7357', `127.0.0.1:${PORT}`));
const helperDir = join(tmp, 'helper');

let loads = 0;
const web = createServer((req, res) => { loads++; res.end('<title>t</title>page'); }).listen(0, '127.0.0.1');
await new Promise((r) => web.once('listening', r));
const webPort = web.address().port;
const proj = join(tmp, 'target');
mkdirSync(join(proj, 'ext'), { recursive: true });
const key = generateKeyPairSync('rsa', { modulusLength: 2048 }).publicKey.export({ type: 'spki', format: 'der' });
const keyId = [...createHash('sha256').update(key).digest().subarray(0, 16)]
  .map((b) => String.fromCharCode(97 + (b >> 4), 97 + (b & 15))).join('');
const manifest = (version, extra) => JSON.stringify({
  manifest_version: 3, name: 'ext-reload test target', version, key: key.toString('base64'),
  background: { service_worker: 'bg.js' },
  content_scripts: [{ matches: ['http://127.0.0.1/*'], js: ['cs.js'] }], permissions: ['storage'], ...extra,
});
// reread-manifest! as vimium-cljs and cookie-autoaccept carry it: disable and enable keep the manifest Chrome first
// loaded, so the extension reloads itself once when manifest.json on disk differs.
const hook = `const canon = (v) => JSON.stringify(v, (k, x) => x && typeof x === 'object' && !Array.isArray(x) ? Object.fromEntries(Object.entries(x).sort()) : x);
fetch(chrome.runtime.getURL('manifest.json')).then((r) => r.text()).then(async (text) => {
  if (canon(JSON.parse(text)) === canon(chrome.runtime.getManifest())) return;
  if ((await chrome.storage.local.get('reloadedForManifest')).reloadedForManifest === text) return;
  await chrome.storage.local.set({ reloadedForManifest: text });
  chrome.runtime.reload();
});`;
writeFileSync(join(proj, 'ext/manifest.json'), manifest('1.0'));
writeFileSync(join(proj, 'ext/bg.js'), `self.MARK = 'one';\n${hook}`);
writeFileSync(join(proj, 'ext/cs.js'), '');
// The rebuild adds a command with a suggested key, as T197's vomnibar-new-tab did.
writeFileSync(join(proj, 'build.json'), JSON.stringify({
  'ext/bg.js': `self.MARK = 'two';\n${hook}`,
  'ext/manifest.json': manifest('1.1', { incognito: 'split', commands: { go: { description: 'go', suggested_key: { default: 'Ctrl+Shift+Y' } } } }),
}));
const config = join(tmp, 'extensions.edn');
writeFileSync(config, `{"target" {:dir "${proj}" :ext "ext" :build ["bb" "-e" "(doseq [[f s] (cheshire.core/parse-string (slurp \\"build.json\\"))] (spit f s))"]}
 "missing" {:dir "${proj}" :ext "ext2"}}`);
mkdirSync(join(proj, 'ext2'));
writeFileSync(join(proj, 'ext2/manifest.json'), JSON.stringify({ manifest_version: 3, name: 'never loaded', version: '1' }));
// Chrome's Preferences as ext-reload reads them: the target is loaded and Allow in Incognito is off.
const prefs = join(tmp, 'Preferences');
writeFileSync(prefs, JSON.stringify({ extensions: { settings: { [keyId]: { location: 4 } } } }));
const env = { ...process.env, EXT_RELOAD_PORT: String(PORT), EXT_RELOAD_CONFIG: config,
              EXT_RELOAD_HELPER_SRC: helperSrc, EXT_RELOAD_HELPER_DIR: helperDir, EXT_RELOAD_CHROME_PREFS: prefs };
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
  const old = join(tmp, 'old-ext');
  cpSync(join(proj, 'ext'), old, { recursive: true });
  const { key: _k, ...oldManifest } = JSON.parse(readFileSync(join(old, 'manifest.json'), 'utf8'));
  writeFileSync(join(old, 'manifest.json'), JSON.stringify(oldManifest));
  const { id: oldId } = await cdp('Extensions.loadUnpacked', { path: old });
  // Developer mode, as in the user's Chrome; without it Chrome disables an unpacked extension that reloads itself.
  const { targetId: extPage } = await cdp('Target.createTarget', { url: 'chrome://extensions', background: true });
  const { sessionId: extSession } = await cdp('Target.attachToTarget', { targetId: extPage, flatten: true });
  for (let i = 0; i < 50; i++) {
    const r = await cdp('Runtime.evaluate', { expression: '!!(window.chrome && chrome.developerPrivate)', returnByValue: true }, extSession);
    if (r.result.value) break;
    await sleep(100);
  }
  await cdp('Runtime.evaluate', { expression: 'chrome.developerPrivate.updateProfileConfiguration({inDeveloperMode: true})', awaitPromise: true }, extSession);
  await cdp('Target.closeTarget', { targetId: extPage });
  assert.notEqual(oldId, keyId);
  await cdp('Target.createTarget', { url: `http://127.0.0.1:${webPort}/` });
  const mark = async (expression = 'self.MARK') => {
    for (let i = 0; i < 50; i++) {
      const sw = (await cdp('Target.getTargets')).targetInfos.find((t) => t.type === 'service_worker' && t.url.includes(targetId));
      if (sw) {
        const { sessionId } = await cdp('Target.attachToTarget', { targetId: sw.targetId, flatten: true });
        const v = (await cdp('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, sessionId)).result.value;
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
  assert.match(r.stdout, new RegExp(`reloaded ext-reload test target 1\\.\\d \\(${keyId}\\)`), 'reloaded by its key id');
  assert.doesNotMatch(r.stdout, new RegExp(oldId), 'the old copy of the same name is not the one reloaded');
  assert.match(r.stdout, /reloaded 1 tab$/m);
  assert.match(r.stdout, new RegExp(`off in incognito windows\\. Once: open chrome://extensions/\\?id=${keyId} and turn on Allow in Incognito`),
               'a manifest that asks for incognito gets the one click Chrome needs');
  assert.equal(await mark(), 'two', 'Chrome reread the rebuilt extension from disk');
  const manifestNow = 'chrome.commands.getAll().then((cs) => [chrome.runtime.getManifest().version, ...cs.map((c) => `${c.name}=${c.shortcut}`)].join(" "))';
  let now;
  for (let i = 0; i < 50 && (now = await mark(manifestNow).catch(() => null)) !== '1.1 go=Ctrl+Shift+Y'; i++) await sleep(200);
  assert.equal(now, '1.1 go=Ctrl+Shift+Y', 'the rebuilt manifest is in, and the command it added has its suggested key');
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
