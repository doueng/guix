import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { launch, available, sleep, evaluate, workerSession } from '../lib/chrome.mjs';
import { startServer, controller } from '../lib/server.mjs';

const skip = available ? false : 'no headless Chrome (install the com.google.Chrome flatpak or set CHROME)';
const T = { skip, timeout: 40000 };

let b, srv, port, page;
const ctl = () => controller(b.cdp, async () => page);
const facts = () => evaluate(b.cdp, page, 'window.__probeFacts()');
const trace = process.env.E2E_TRACE ? (...a) => console.error('  ·', ...a) : () => {};
const keys = async (seq, wait = 250) => { trace('keys', seq); await ctl()('keys', { seq }); await sleep(wait); };
async function until(pred, ms = 5000) {
  const t0 = Date.now();
  let f;
  while (Date.now() - t0 < ms) {
    try { f = await facts(); if (pred(f)) return f; } catch {}
    await sleep(100);
  }
  return f;
}
const sessions = {};
async function attach(targetId) {
  page = sessions[targetId] ??= (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
}
async function open(path = '/', frames = 2) {
  const { targetId } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}${path}` });
  await attach(targetId);
  await b.cdp('Page.enable', {}, page);
  await b.cdp('Page.bringToFront', {}, page);
  const f = await until((f) => f.ready && Object.keys(f.frames).length >= frames, 10000);
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
  const y0 = (await facts()).scrollY;
  await keys('jj', 500);
  assert.ok((await facts()).scrollY > y0, 'j scrolls after Escape');
});

test('insert mode shows no caret in any field; Escape gives it back (T223)', T, async () => {
  await open();
  const caret = (sel) => evaluate(b.cdp, page, `getComputedStyle(${sel}).caretColor`);
  const clear = 'rgba(0, 0, 0, 0)';
  for (const sel of ['document.getElementById("text")', 'document.getElementById("area")',
                     'document.getElementById("editable")',
                     'document.getElementById("host").shadowRoot.getElementById("shadowinput")']) {
    assert.notEqual(await caret(sel), clear, 'a caret in normal mode: ' + sel);
    await clickOn(sel);
    await sleep(150);
    assert.equal(await caret(sel), clear, 'no caret while typing: ' + sel);
    await keys('<esc>', 200);
    assert.notEqual(await caret(sel), clear, 'the caret is back after Escape: ' + sel);
  }
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

const tube = () => evaluate(b.cdp, page, 'window.__tube()');
async function openTube(layout) {
  const { targetId } = await b.cdp('Target.createTarget', { url: `http://127.0.0.1:${port}/tube.html?layout=${layout}` });
  await attach(targetId);
  await b.cdp('Page.enable', {}, page);
  await b.cdp('Page.bringToFront', {}, page);
  for (let i = 0; i < 100 && !(await tube().catch(() => ({}))).ready; i++) await sleep(100);
  assert.ok((await tube()).ready, 'content script ready');
}
const inGuide = (id) => `document.querySelector('tube-app').shadowRoot.getElementById('${id}')`;

for (const [layout, main, max] of [['page', 'page', 'pageMax'], ['app', 'feed', 'feedMax']]) {
  test(`scrolling a YouTube-like page (${layout} scrolls): j and G move the ${main}, not the guide`, T, async () => {
    await openTube(layout);
    await keys('jj', 500);
    let t = await tube();
    assert.ok(t[main] > 0, `jj scrolls the ${main}: ${JSON.stringify(t)}`);
    assert.equal(t.guide, 0, 'the guide stays');
    await keys('G', 400);
    t = await tube();
    assert.equal(t[main], t[max], `G to the ${main}'s bottom: ${JSON.stringify(t)}`);
    assert.equal(t.guide, 0, 'the guide stays after G');
    await keys('gg', 400);
    assert.equal((await tube())[main], 0, 'gg to the top');

    await clickOn(inGuide('g3'));
    t = await tube();
    assert.match(t.url, /feed=3/);
    await keys('jj', 500);
    t = await tube();
    assert.ok(t[main] > 0, `after a guide link navigates in place, j scrolls the ${main}: ${JSON.stringify(t)}`);
    assert.equal(t.guide, 0, 'and not the guide');

    await clickOn(inGuide('g1'));
    await keys('gg', 400);
    await clickOn(inGuide('guide-title'));
    const before = await tube();
    await keys('jj', 500);
    t = await tube();
    assert.ok(t.guide > 0, `after a click in the guide, j scrolls it: ${JSON.stringify(t)}`);
    assert.equal(t[main], before[main], `and not the ${main}`);
    await keys('G', 400);
    t = await tube();
    assert.ok(t.guide > 100, 'G scrolls the guide to its end');
    assert.equal(t[main], before[main], `G leaves the ${main}`);
    if (layout === 'page') {
      await keys('jj', 500);
      assert.ok((await tube()).page > before.page, "past the guide's end, j scrolls the page");
    }
  });
}

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

test("Vomnibar: an open tab's exact title ranks first, Enter switches without a duplicate, Escape gives focus back", T, async () => {
  const notes = await open('/notes.html', 0);
  const home = await open('/');
  const ctlv = controller(b.cdp, async () => page, b.extensionId);
  const tabs0 = await ctlv('tabs');
  await clickOn("document.getElementById('text')");
  await keys('<esc>', 150);
  await evaluate(b.cdp, page, "document.getElementById('text').focus(), true");
  const w = await workerSession(b.cdp, b.extensionId);
  await evaluate(b.cdp, w, 'chrome.tabs.query({active: true, lastFocusedWindow: true}).then(t => vimiumCljsVomnibarCommand(t[0])).then(() => true)');
  let v;
  for (let i = 0; i < 40 && !(v?.open && v.focused); i++) { await sleep(50); v = await ctlv('vomnibar'); }
  assert.ok(v.open && v.focused, 'open with the input focused');
  await keys('<esc>', 1200);
  assert.equal((await ctlv('vomnibar')).open, false, 'closed');
  assert.equal((await facts()).active, 'text', 'focus back in the field');
  await evaluate(b.cdp, page, 'document.activeElement.blur(), true');
  await keys('o', 500);
  await ctl()('keys', { seq: 'Vomnibar field notes', gap: 0 });
  await sleep(400);
  v = await ctlv('vomnibar');
  assert.equal(v.value, 'Vomnibar field notes', 'no keystroke lost');
  assert.equal(v.items[0]?.url, `http://127.0.0.1:${port}/notes.html`, 'exact title first: ' + JSON.stringify(v.items.slice(0, 3)));
  assert.equal(v.items[0]?.type, 'Tab');
  await keys('<enter>', 800);
  const tabs1 = await ctlv('tabs');
  assert.equal(tabs1.urls.length, tabs0.urls.length, 'no new tab');
  assert.equal(tabs1.active, `http://127.0.0.1:${port}/notes.html`, 'switched to the notes tab');
  for (const t of [notes, home]) await b.cdp('Target.closeTarget', { targetId: t });
});

test('the Vomnibar command (Ctrl+Shift+H, sent by Ctrl+L) opens the Vomnibar in the page', T, async () => {
  await open();
  const w = await workerSession(b.cdp, b.extensionId);
  const cmd = await evaluate(b.cdp, w, "chrome.commands.getAll().then(cs => cs.find(c => c.name === 'vomnibar')?.shortcut || '')");
  assert.equal(cmd, 'Ctrl+Shift+H', 'suggested key assigned');
  await evaluate(b.cdp, w, 'chrome.tabs.query({active: true, lastFocusedWindow: true}).then(t => vimiumCljsVomnibarCommand(t[0])).then(() => true)');
  await sleep(600);
  const frames = await extensionFrames();
  assert.ok(frames.some((u) => u.includes('#vomnibar') && !u.includes('popup=1')), `in-page Vomnibar: ${frames}`);
  await keys('<esc>', 300);
});

test('the vomnibar-tabs command (Ctrl+Shift+K, sent by Cmd+Space b b) opens the tab selector in the page', T, async () => {
  await open();
  const w = await workerSession(b.cdp, b.extensionId);
  const cmd = await evaluate(b.cdp, w, "chrome.commands.getAll().then(cs => cs.find(c => c.name === 'vomnibar-tabs')?.shortcut || '')");
  assert.equal(cmd, 'Ctrl+Shift+K', 'suggested key assigned');
  await evaluate(b.cdp, w, "chrome.tabs.query({active: true, lastFocusedWindow: true}).then(t => vimiumCljsVomnibarCommand(t[0], 'tabs')).then(() => true)");
  await sleep(600);
  const frames = await extensionFrames();
  assert.ok(frames.some((u) => u.includes('#vomnibar?mode=tabs') && !u.includes('popup=1')), `in-page tab Vomnibar: ${frames}`);
  await keys('<esc>', 300);
});

test('the vomnibar-new-tab command (Ctrl+Shift+Y, sent by Cmd+T) opens the pick in a new tab', T, async () => {
  const home = await open();
  const ctlv = controller(b.cdp, async () => page, b.extensionId);
  const w = await workerSession(b.cdp, b.extensionId);
  const cmd = await evaluate(b.cdp, w, "chrome.commands.getAll().then(cs => cs.find(c => c.name === 'vomnibar-new-tab')?.shortcut || '')");
  assert.equal(cmd, 'Ctrl+Shift+Y', 'suggested key assigned');
  const before = (await ctlv('tabs')).urls.length;
  await evaluate(b.cdp, w, "chrome.tabs.query({active: true, lastFocusedWindow: true}).then(t => vimiumCljsVomnibarCommand(t[0], 'omni', true)).then(() => true)");
  await sleep(600);
  assert.ok((await extensionFrames()).some((u) => u.includes('#vomnibar?mode=omni&newTab=1')), 'in-page Vomnibar in new-tab mode');
  const target = `http://127.0.0.1:${port}/notes.html?cmdt=1`;
  await ctl()('keys', { seq: target, gap: 0 });
  await sleep(400);
  await keys('<enter>', 1000);
  const now = await ctlv('tabs');
  assert.equal(now.urls.length, before + 1, 'one new tab');
  assert.equal(now.active, target, 'the new tab shows the pick');
  assert.equal((await b.cdp('Target.getTargetInfo', { targetId: home })).targetInfo.url, `http://127.0.0.1:${port}/`, 'the page stayed');
  const opened = (await b.cdp('Target.getTargets')).targetInfos.find((t) => t.url === target);
  for (const t of [opened?.targetId, home]) if (t) await b.cdp('Target.closeTarget', { targetId: t });
});

test('the vomnibar-new-tab command on a chrome:// page opens a popup whose pick opens in a new tab', T, async () => {
  const { targetId } = await b.cdp('Target.createTarget', { url: 'chrome://version' });
  await sleep(500);
  const w = await workerSession(b.cdp, b.extensionId);
  await evaluate(b.cdp, w, `chrome.tabs.query({}).then(ts => vimiumCljsVomnibarCommand(ts.find(t => t.url.startsWith('chrome://version')), 'omni', true)).then(() => true)`);
  let popup;
  for (let i = 0; i < 30 && !popup; i++) {
    popup = (await b.cdp('Target.getTargets')).targetInfos.find((t) => t.type === 'page' && t.url.includes('popup=1') && t.url.includes('newTab=1'));
    if (!popup) await sleep(100);
  }
  assert.ok(popup, 'popup Vomnibar in new-tab mode');
  const ps = (await b.cdp('Target.attachToTarget', { targetId: popup.targetId, flatten: true })).sessionId;
  for (let i = 0; i < 30; i++) {
    if (await evaluate(b.cdp, ps, "!!document.getElementById('query') && !document.getElementById('vomnibar').hidden").catch(() => false)) break;
    await sleep(100);
  }
  const target = `http://127.0.0.1:${port}/frame.html?cmdt=1`;
  await evaluate(b.cdp, ps, `(() => { const q = document.getElementById('query'); q.value = ${JSON.stringify(target)};
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); return true; })()`);
  let opened;
  for (let i = 0; i < 30 && !opened; i++) {
    await sleep(100);
    opened = (await b.cdp('Target.getTargets')).targetInfos.find((t) => t.type === 'page' && t.url === target);
  }
  assert.ok(opened, 'the pick opened in a new tab');
  assert.ok((await b.cdp('Target.getTargetInfo', { targetId })).targetInfo.url.startsWith('chrome://version'), 'the chrome:// tab stayed');
  for (const t of [opened.targetId, targetId]) await b.cdp('Target.closeTarget', { targetId: t });
});

test('the Vomnibar command opens a popup on chrome:// pages and the choice opens in that tab', T, async () => {
  const { targetId } = await b.cdp('Target.createTarget', { url: 'chrome://version' });
  const s = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
  await b.cdp('Page.bringToFront', {}, s);
  await sleep(500);
  const w = await workerSession(b.cdp, b.extensionId);
  await evaluate(b.cdp, w, `chrome.tabs.query({}).then(ts => vimiumCljsVomnibarCommand(ts.find(t => t.url.startsWith('chrome://version')))).then(() => true)`);
  let popup;
  for (let i = 0; i < 30 && !popup; i++) {
    const { targetInfos } = await b.cdp('Target.getTargets');
    popup = targetInfos.find((t) => t.type === 'page' && t.url.includes('ui.html#vomnibar') && t.url.includes('popup=1'));
    if (!popup) await sleep(100);
  }
  assert.ok(popup, 'popup Vomnibar');
  const ps = (await b.cdp('Target.attachToTarget', { targetId: popup.targetId, flatten: true })).sessionId;
  for (let i = 0; i < 30; i++) {
    if (await evaluate(b.cdp, ps, "!!document.getElementById('query') && !document.getElementById('vomnibar').hidden").catch(() => false)) break;
    await sleep(100);
  }
  const target = `http://127.0.0.1:${port}/frame.html`;
  await evaluate(b.cdp, ps, `(() => { const q = document.getElementById('query'); q.value = ${JSON.stringify(target)};
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); return true; })()`);
  let url;
  for (let i = 0; i < 30 && url !== target; i++) {
    await sleep(100);
    ({ targetInfo: { url } } = await b.cdp('Target.getTargetInfo', { targetId }));
  }
  assert.equal(url, target, 'the chrome:// tab went to the chosen URL');
  const { targetInfos } = await b.cdp('Target.getTargets');
  assert.ok(!targetInfos.some((t) => t.targetId === popup.targetId), 'popup closed');
});

test('the popup Vomnibar fills its window: no margin, no scrollbar, keys at the bottom', T, async () => {
  const { targetId } = await b.cdp('Target.createTarget', { url: 'chrome://version' });
  const w = await workerSession(b.cdp, b.extensionId);
  await evaluate(b.cdp, w, `chrome.tabs.query({}).then(ts => vimiumCljsVomnibarCommand(ts.find(t => t.url.startsWith('chrome://version')), 'tabs')).then(() => true)`);
  let popup;
  for (let i = 0; i < 30 && !popup; i++) {
    popup = (await b.cdp('Target.getTargets')).targetInfos.find((t) => t.type === 'page' && t.url.includes('popup=1'));
    if (!popup) await sleep(100);
  }
  assert.ok(popup, 'popup Vomnibar');
  const ps = (await b.cdp('Target.attachToTarget', { targetId: popup.targetId, flatten: true })).sessionId;
  let box;
  for (let i = 0; i < 30 && !box?.rows; i++) {
    await sleep(100);
    box = await evaluate(b.cdp, ps, `(() => { const r = document.getElementById('vomnibar').getBoundingClientRect(), k = document.getElementById('keys').getBoundingClientRect();
      return { left: r.left, top: r.top, right: innerWidth - r.right, bottom: innerHeight - r.bottom, keysBottom: innerHeight - k.bottom,
        rows: document.querySelectorAll('#list li').length, page: getComputedStyle(document.body).backgroundColor,
        card: getComputedStyle(document.getElementById('vomnibar')).backgroundColor,
        scroll: document.documentElement.scrollHeight > innerHeight || innerWidth > document.documentElement.clientWidth }; })()`).catch(() => null);
  }
  assert.deepEqual([box.left, box.top, box.right, box.bottom, box.keysBottom], [0, 0, 0, 0, 0], `card edge to edge: ${JSON.stringify(box)}`);
  assert.equal(box.page, box.card, 'no other colour around the card');
  assert.equal(box.scroll, false, 'no scrollbar');
  await b.cdp('Target.closeTarget', { targetId: popup.targetId });
  await b.cdp('Target.closeTarget', { targetId });
});

test('a new tab is the Vomnibar, focused, and opens what you pick in that tab', T, async () => {
  const w = await workerSession(b.cdp, b.extensionId);
  const before = await evaluate(b.cdp, w, 'chrome.tabs.query({}).then(ts => ts.length)');
  await evaluate(b.cdp, w, 'chrome.tabs.create({}).then(() => true)');
  let nt;
  for (let i = 0; i < 40 && !nt; i++) {
    nt = (await b.cdp('Target.getTargets')).targetInfos.find((t) => t.type === 'page' && t.url.endsWith('/ui.html?newtab'));
    if (!nt) await sleep(100);
  }
  assert.ok(nt, 'chrome://newtab moved on to ui.html?newtab');
  const s = (await b.cdp('Target.attachToTarget', { targetId: nt.targetId, flatten: true })).sessionId;
  let st;
  for (let i = 0; i < 30 && !st?.rows; i++) {
    await sleep(100);
    st = await evaluate(b.cdp, s, `({ input: document.activeElement?.id, rows: document.querySelectorAll('#list li').length, title: document.title,
      shown: !document.getElementById('vomnibar').hidden })`).catch(() => null);
  }
  assert.deepEqual([st.input, st.shown, st.title], ['query', true, 'New Tab'], JSON.stringify(st));
  assert.equal(await evaluate(b.cdp, w, 'chrome.tabs.query({}).then(ts => ts.length)'), before + 1, 'one new tab, the new tab page closed');
  const target = `http://127.0.0.1:${port}/frame.html?from-new-tab`;
  page = s;
  await ctl()('keys', { seq: '<esc>' });
  assert.ok(await evaluate(b.cdp, s, "!document.getElementById('vomnibar').hidden"), 'Escape keeps the new tab page');
  await evaluate(b.cdp, s, `(() => { const q = document.getElementById('query'); q.value = ${JSON.stringify(target)}; q.dispatchEvent(new Event('input')); return true; })()`);
  await ctl()('keys', { seq: '<enter>' });
  let url;
  for (let i = 0; i < 30 && url !== target; i++) { await sleep(100); ({ targetInfo: { url } } = await b.cdp('Target.getTargetInfo', { targetId: nt.targetId })); }
  assert.equal(url, target, 'opened in the new tab itself');
  await b.cdp('Target.closeTarget', { targetId: nt.targetId });
});

test('keys typed right after Ctrl+L, before the Vomnibar has loaded, land in it', T, async () => {
  await open();
  const w = await workerSession(b.cdp, b.extensionId);
  const f0 = await facts();
  await evaluate(b.cdp, w, 'chrome.tabs.query({active: true, lastFocusedWindow: true}).then(t => { vimiumCljsVomnibarCommand(t[0]); return true; })');
  await ctl()('keys', { seq: 'jjkk notes', gap: 0 });
  const ctlv = controller(b.cdp, async () => page, b.extensionId);
  let v;
  for (let i = 0; i < 30 && v?.value !== 'jjkk notes'; i++) { await sleep(50); v = await ctlv('vomnibar'); }
  assert.equal(v.value, 'jjkk notes', 'every key in the input');
  const f1 = await facts();
  assert.deepEqual([f1.scrollY, f1.pageKeys, f1.mode], [f0.scrollY, f0.pageKeys, 'normal'], 'no key reached the page');
  await keys('<esc>', 300);
});

test('the side-panel tab list shows the window\'s tabs; a click switches, × closes', T, async () => {
  const a = await open('/?panel-a');
  await open('/?panel-b');
  const { targetId } = await b.cdp('Target.createTarget', { url: `chrome-extension://${b.extensionId}/panel.html` });
  const s = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
  const titles = () => evaluate(b.cdp, s, '[...document.querySelectorAll(".tab")].map(r => r.title)');
  let rows = [];
  for (let i = 0; i < 30 && !rows.some((t) => t.includes('?panel-b')); i++) { await sleep(100); rows = await titles().catch(() => []); }
  assert.ok(rows.some((t) => t.includes('?panel-a')) && rows.some((t) => t.includes('?panel-b')), `rows: ${rows}`);
  await evaluate(b.cdp, s, `[...document.querySelectorAll('.tab')].find(r => r.title.includes('?panel-a')).click(), true`);
  await sleep(400);
  assert.ok((await activeTabUrl()).endsWith('/?panel-a'), 'clicking a row activates its tab');
  await evaluate(b.cdp, s, `[...document.querySelectorAll('.tab')].find(r => r.title.includes('?panel-b')).querySelector('.close').click(), true`);
  await sleep(400);
  rows = await titles();
  assert.ok(!rows.some((t) => t.includes('?panel-b')), 'the closed tab left the list');
  await attach(a);
});
