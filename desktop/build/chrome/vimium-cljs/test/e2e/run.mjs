// End-to-end checks: loads ext/ into one headless Chrome and drives the fixture pages with
// trusted key events. Run `npm run build` first. Skips when no Chrome is found.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { launch, available, sleep, evaluate, setSettings, workerSession } from '../lib/chrome.mjs';
import { startServer, controller } from '../lib/server.mjs';

const skip = available ? false : 'no headless Chrome (install the com.google.Chrome flatpak or set CHROME)';
const T = { skip, timeout: 40000 };
// The user's mapping text from their Vimium export.
const USER_KEYS = 'map i focusInput\nmap w previousTab\nmap e nextTab\nunmap J\nunmap K\nmap h goBack\nmap l goForward\nmap p Vomnibar.activateTabSelection\nmap <c-o> visitPreviousTab\nunmap N';

let b, srv, port, page;
const ctl = () => controller(b.cdp, async () => page);
const facts = () => evaluate(b.cdp, page, 'window.__probeFacts()');
const trace = process.env.E2E_TRACE ? (...a) => console.error('  ·', ...a) : () => {};
const keys = async (seq, wait = 250) => { trace('keys', seq); await ctl()('keys', { seq }); await sleep(wait); };
async function until(pred, ms = 5000) {
  const t0 = Date.now();
  let f;
  while (Date.now() - t0 < ms) {
    try { f = await facts(); if (pred(f)) return f; } catch { /* page still loading */ }
    await sleep(100);
  }
  return f;
}
const sessions = {};
async function attach(targetId) {
  page = sessions[targetId] ??= (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
}
async function open(path = '/') {
  const { targetId } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}${path}` });
  await attach(targetId);
  await b.cdp('Page.enable', {}, page);
  await b.cdp('Page.bringToFront', {}, page);
  const f = await until((f) => f.ready && Object.keys(f.frames).length >= 2, 10000);
  assert.ok(f.ready, 'content script ready');
  return targetId;
}
async function clickOn(expr) {
  const r = await evaluate(b.cdp, page, `(() => { const r = (${expr}).getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; })()`);
  await ctl()('click', { x: r[0], y: r[1] });
  await sleep(150);
}
async function activeTabUrl() {
  const s = await workerSession(b.cdp, b.extensionId);
  return evaluate(b.cdp, s, 'chrome.tabs.query({active: true, lastFocusedWindow: true}).then(t => t[0] && t[0].url)');
}
async function extensionFrames() {
  const { targetInfos } = await b.cdp('Target.getTargets');
  return targetInfos.filter((t) => t.url.startsWith(`chrome-extension://${b.extensionId}/ui.html`)).map((t) => t.url);
}

before(async () => {
  if (skip) return;
  b = await launch();
  ({ port, server: srv } = await startServer((op, q) => ctl()(op, q)));
  await setSettings(b.cdp, b.extensionId, { keyMappings: USER_KEYS, grabBackFocus: true, exclusionRules: [] });
});
after(() => { b?.close(); srv?.closeAllConnections(); srv?.close(); setTimeout(() => process.exit(process.exitCode ?? 0), 2000).unref(); });

test('link hints cover every visible link, across shadow DOM and both iframes', T, async () => {
  await open();
  await keys('f', 700);
  const f = await until((f) => f.mode === 'hints' && Object.values(f.frames).every((x) => x.mode === 'hints'));
  const top = f.links.filter((l) => l.visible);
  assert.ok(top.length >= 37, `visible top links: ${top.length}`);
  assert.deepEqual(top.filter((l) => !l.hint).map((l) => l.id), [], 'unhinted visible links in the top frame');
  assert.ok(f.links.find((l) => l.id === 'shadowlink').hint, 'shadow DOM link hinted');
  assert.equal(f.links.find((l) => l.id === 'hidden').hint, null, 'display:none link not hinted');
  assert.equal(f.links.find((l) => l.id === 'far').hint, null, 'off-screen link not hinted');
  const frames = Object.values(f.frames);
  assert.equal(frames.length, 2, 'two frames report');
  for (const fr of frames) assert.deepEqual(fr.links.filter((l) => l.visible && !l.hint).map((l) => l.id), [], 'unhinted in ' + fr.url);
  const all = [...top, ...frames.flatMap((fr) => fr.links.filter((l) => l.hint))].map((l) => l.hint);
  assert.equal(new Set(all).size, all.length, 'hints unique across frames');
  for (const h of all) for (const o of all) if (h !== o) assert.ok(!o.startsWith(h), `${h} is a prefix of ${o}`);
  // Follow l5 by typing its hint.
  const hint = f.links.find((l) => l.id === 'l5').hint;
  await keys(hint, 400);
  const g = await facts();
  assert.ok(g.url.endsWith('#l5'), 'followed ' + g.url);
  assert.equal(g.mode, 'normal');
});

test('link hints: Escape right after f cancels them in every frame', T, async () => {
  await open();
  await ctl()('keys', { seq: 'f<esc>', gap: '0' });
  await sleep(900);
  const f = await facts();
  assert.equal(f.mode, 'normal');
  for (const fr of Object.values(f.frames)) assert.equal(fr.mode, 'normal', 'frame ' + fr.url);
  assert.ok(f.links.every((l) => !l.hint));
});

test('F opens a hinted link in a new tab; Escape leaves hint mode', T, async () => {
  const before = (await b.cdp('Target.getTargets')).targetInfos.filter((t) => t.type === 'page').length;
  await keys('F', 700);
  const f = await until((f) => f.mode === 'hints');
  await keys(f.links.find((l) => l.id === 'l7').hint, 800);
  const pages = (await b.cdp('Target.getTargets')).targetInfos.filter((t) => t.type === 'page');
  assert.equal(pages.length, before + 1);
  assert.ok(pages.some((t) => t.url.endsWith('#l7')));
  await keys('f', 500);
  assert.equal((await facts()).mode, 'hints');
  await keys('<esc>', 300);
  const g = await facts();
  assert.equal(g.mode, 'normal');
  assert.ok(g.links.every((l) => !l.hint), 'markers removed');
});

test('keys typed in inputs, textareas, contenteditable and shadow inputs reach the page', T, async () => {
  await open();
  for (const [sel, id] of [['document.getElementById("text")', 'text'], ['document.getElementById("area")', 'area'],
                           ['document.getElementById("editable")', 'editable'],
                           ['document.getElementById("host").shadowRoot.getElementById("shadowinput")', 'shadowinput']]) {
    const y0 = (await facts()).scrollY;
    await clickOn(sel);
    await keys('jkfFgg/dx', 300);
    const f = await facts();
    assert.equal(f.mode, 'normal', 'no mode change in ' + id);
    assert.equal(f.scrollY, y0, 'no scrolling from ' + id);
    assert.ok(f.fields.find((x) => x.id === id).value.includes('jkfFgg/dx'), `typed into ${id}: ${JSON.stringify(f.fields.find((x) => x.id === id))}`);
    assert.ok(f.links.every((l) => !l.hint), 'no hints from ' + id);
    await keys('<esc>', 150);
  }
  // Escape blurred the field, so j scrolls again.
  const y0 = (await facts()).scrollY;
  await keys('jj', 500);
  assert.ok((await facts()).scrollY > y0, 'j scrolls after Escape');
});

test('scrolling: j k d u G gg', T, async () => {
  await open();
  await keys('gg', 300);
  let f = await facts();
  assert.equal(f.scrollY, 0);
  await keys('j', 400);
  const y1 = (await facts()).scrollY;
  assert.ok(y1 >= 50 && y1 <= 70, 'j scrolls one step: ' + y1);
  await keys('k', 400);
  assert.equal((await facts()).scrollY, 0);
  await keys('3j', 500);
  assert.ok((await facts()).scrollY >= 170, '3j');
  await keys('d', 500);
  const y2 = (await facts()).scrollY;
  assert.ok(y2 >= 170 + 300, 'd scrolls half a page: ' + y2);
  await keys('G', 300);
  f = await facts();
  assert.equal(f.scrollY, f.maxScrollY, 'G to bottom');
  await keys('gg', 300);
  assert.equal((await facts()).scrollY, 0, 'gg to top');
});

test('find mode highlights every match, n moves, Escape clears', T, async () => {
  await open();
  await keys('/needle', 300);
  let f = await facts();
  assert.equal(f.findHighlights, f.needles, `highlights ${f.findHighlights} vs needles ${f.needles}`);
  assert.equal(f.findCurrent, 1);
  await keys('<enter>', 200);
  const sel1 = await evaluate(b.cdp, page, 'getSelection().toString()');
  assert.equal(sel1.toLowerCase(), 'needle');
  await keys('n', 200);
  f = await facts();
  assert.equal(f.findHighlights, f.needles, 'highlights stay after n');
  await keys('/NEEDLE<enter>', 300);
  f = await facts();
  assert.equal(f.findHighlights, 1, 'smart case: capitals match exactly');
  await keys('<esc>', 200);
  f = await facts();
  assert.equal(f.findHighlights, 0, 'Escape clears highlights');
});

test("the user's mappings: N unmapped, p opens tab search, ? help", T, async () => {
  await open();
  const keys0 = (await facts()).pageKeys;
  await keys('N', 200);
  assert.equal((await facts()).pageKeys, keys0 + 1, 'N reaches the page (unmapped)');
  await keys('p', 800);
  assert.ok((await extensionFrames()).some((u) => u.includes('#vomnibar?mode=tabs')), 'Vomnibar in tab mode');
  await keys('<esc>', 400);
  assert.equal((await extensionFrames()).length, 0, 'Vomnibar closed');
  await keys('?', 800);
  assert.ok((await extensionFrames()).some((u) => u.includes('#help')), 'help shown');
  await keys('<esc>', 400);
  assert.equal((await extensionFrames()).length, 0, 'help closed');
});

test('tabs: e, w and <c-o>', T, async () => {
  const a = await open('/?a');
  const b2 = await open('/?b');
  assert.ok((await activeTabUrl()).endsWith('/?b'));
  await keys('w', 500);
  assert.ok((await activeTabUrl()).endsWith('/?a'), 'w is previousTab');
  await attach(a);
  await keys('e', 500);
  assert.ok((await activeTabUrl()).endsWith('/?b'), 'e is nextTab');
  await attach(b2);
  await keys('<c-o>', 500);
  assert.ok((await activeTabUrl()).endsWith('/?a'), '<c-o> visits the previous tab');
});

test('visual mode extends the selection; marks jump back', T, async () => {
  await open();
  await keys('v', 200);
  assert.equal((await facts()).mode, 'visual');
  await keys('llll', 200);
  assert.equal((await evaluate(b.cdp, page, 'getSelection().toString().length')), 5);
  await keys('<esc>', 200);
  assert.equal((await facts()).mode, 'normal');
  await keys('5j', 500);
  const y = (await facts()).scrollY;
  await keys('ma', 200);
  await keys('G', 300);
  await keys('`a', 300);
  assert.equal((await facts()).scrollY, y, 'mark a');
  await keys('``', 300);
  assert.equal((await facts()).scrollY, (await facts()).maxScrollY, 'back to the previous position');
});

test('an exclusion rule turns the keys off on matching pages', T, async () => {
  await setSettings(b.cdp, b.extensionId, { keyMappings: USER_KEYS, exclusionRules: [{ pattern: 'http://127.0.0.1:*/*', passKeys: '' }] });
  await open();
  const f0 = await facts();
  await keys('jjf', 400);
  const f = await facts();
  assert.equal(f.scrollY, f0.scrollY);
  assert.equal(f.mode, 'normal');
  assert.equal(f.pageKeys, f0.pageKeys + 3);
  await setSettings(b.cdp, b.extensionId, { keyMappings: USER_KEYS, exclusionRules: [{ pattern: 'http://127.0.0.1:*/*', passKeys: 'j' }] });
  await sleep(200);
  await keys('j', 400);
  assert.equal((await facts()).scrollY, f0.scrollY, 'j is a pass key');
  await keys('d', 400);
  assert.ok((await facts()).scrollY > f0.scrollY, 'd still works');
  await setSettings(b.cdp, b.extensionId, { keyMappings: USER_KEYS, exclusionRules: [] });
});

test('the options page imports a Vimium export', T, async () => {
  const { targetId } = await b.cdp('Target.createTarget', { url: `chrome-extension://${b.extensionId}/options.html` });
  const s = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
  await sleep(800);
  const exported = JSON.stringify({ keyMappings: 'map x scrollDown', linkHintCharacters: 'asdf', settingsVersion: '2.4.2',
    exclusionRules: [{ passKeys: '', pattern: 'https?://a.example/*' }, { passKeys: '', pattern: 'https?://a.example/*' }] });
  await evaluate(b.cdp, s, `document.getElementById('import-text').value = ${JSON.stringify(exported)}; document.getElementById('import-paste').click(); true`);
  await sleep(500);
  const report = await evaluate(b.cdp, s, 'document.getElementById("import-report").textContent');
  assert.match(report, /duplicate/);
  const w = await workerSession(b.cdp, b.extensionId);
  const stored = await evaluate(b.cdp, w, 'chrome.storage.local.get("settings").then(r => r.settings)');
  assert.equal(stored.keyMappings, 'map x scrollDown');
  assert.equal(stored.linkHintCharacters, 'asdf');
  assert.equal(stored.exclusionRules.length, 1);
});
