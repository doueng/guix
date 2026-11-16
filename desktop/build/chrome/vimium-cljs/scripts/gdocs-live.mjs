// Google Docs and Sheets in a real Chrome window on the test display: keys typed with wtype, screenshots
// with grim, clicks through CDP (trusted events; headless sway has no pointer device).
//   GDOC_URL=... GSHEET_URL=... node scripts/gdocs-live.mjs [out-dir] [docs|sheets|docs-fixture|sheets-fixture ...]
// The Doc and Sheet must be yours to type into (shared: anyone with the link can edit); without the
// variables only the fixtures run. Never point it at someone else's document: typed keys are saved there.
import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { launch, sleep, evaluate } from '../test/lib/chrome.mjs';
import { startServer } from '../test/lib/server.mjs';

const out = process.argv[2] || '/tmp/gdocs-live';
const only = process.argv.slice(3);
mkdirSync(out, { recursive: true });
if (!process.env.TEST_DISPLAY) {
  const r = spawnSync('test-display', ['--size', '1280x900', '--', process.execPath, import.meta.filename, ...process.argv.slice(2)], { stdio: 'inherit' });
  process.exit(r.status ?? 1);
}
const run = (cmd, ...a) => execFileSync(cmd, a).toString().trim();
// Each wtype adds a virtual keyboard and removes it on exit, and with no keyboard left Chrome loses
// focus and Docs closes its menus. One wtype that sleeps keeps the seat's keyboard for the run.
const keeper = spawn('wtype', ['-s', '3600000', '-k', 'Shift_L'], { stdio: 'ignore' });
process.on('exit', () => keeper.kill());
const type = async (text) => { run('wtype', '-s', '150', '-d', '60', text); await sleep(700); };
const key = async (k) => { run('wtype', '-s', '150', '-k', k); await sleep(700); };

// What vimium and the app see, from the top page.
const STATE = `(() => {
  const desc = (el) => el ? el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') : null;
  const f = document.querySelector('.docs-texteventtarget-iframe'), fd = f && f.contentDocument;
  const a = document.activeElement, inner = a && a.tagName === 'IFRAME' ? (() => { try { return a.contentDocument.activeElement; } catch { return null; } })() : null;
  const scroller = document.querySelector('.kix-appview-editor, .native-scrollbar-y, #editor');
  const html = fd && fd.documentElement.dataset.vimiumCljsSuspended !== undefined ? fd.documentElement : document.documentElement;
  const shown = (m) => getComputedStyle(m).display !== 'none' && getComputedStyle(m).visibility !== 'hidden' && m.getBoundingClientRect().height > 0;
  const hint = (sel) => (document.querySelector(sel) || {}).dataset?.vimiumCljsHint || null;
  const ed = document.getElementById('waffle-rich-text-editor');
  return { url: location.href, focus: document.hasFocus(), active: desc(a), inner: desc(inner), editable: !!((inner || a) && (inner || a).isContentEditable),
    cellEditing: !!(ed && ed.getBoundingClientRect().top > 0),
    scrollTop: scroller ? Math.round(scroller.scrollTop) : null, suspended: html.dataset.vimiumCljsSuspended === '1',
    hints: document.querySelectorAll('[data-vimium-cljs-hint]').length,
    fileHint: hint('#docs-file-menu, #m-file'), tabHint: hint('.docs-sheet-tab'),
    menus: [...document.querySelectorAll('.goog-menu')].filter(shown).length,
    formula: (document.querySelector('#t-formula-bar-input-container .cell-input') || {}).textContent ?? null,
    text: window.__docsFacts ? window.__docsFacts().text : window.__sheetFacts ? window.__sheetFacts().cells : null };
})()`;

const { server, port } = await startServer(async () => null);
const pages = {
  docs: { url: process.env.GDOC_URL, wait: 12000, click: [640, 300] },
  sheets: { url: process.env.GSHEET_URL, wait: 12000, click: [250, 260] },
  'docs-fixture': { url: `http://127.0.0.1:${port}/docs.html`, wait: 2000, click: [640, 400] },
  'sheets-fixture': { url: `http://127.0.0.1:${port}/sheets.html`, wait: 2000, click: [250, 200] },
};
const report = {};
const b = await launch({ headed: true, width: 1280, height: 900 });
try {
  for (const [name, p] of Object.entries(pages)) {
    if ((only.length && !only.includes(name)) || !p.url) continue;
    const sheet = name.startsWith('sheets');
    const r = (report[name] = []);
    const shot = (n) => { const f = join(out, `${name}-${n}.png`); run('grim', f); return f; };
    const check = (n, what, ok, st) => { r.push({ what, ok: !!ok, shot: shot(n), st }); console.log(`${ok ? 'PASS' : 'FAIL'}: ${name}: ${what} ${JSON.stringify(st)}`); };
    const { targetId } = await b.cdp('Target.createTarget', { url: p.url });
    const s = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
    await b.cdp('Page.bringToFront', {}, s);
    await sleep(p.wait);
    // Mirror hints into attributes, as on the fixtures.
    await evaluate(b.cdp, s, `(() => { if (!document.querySelector('meta[name=vimium-cljs-test]')) { const m = document.createElement('meta'); m.name = 'vimium-cljs-test'; document.head.appendChild(m); } })()`);
    const st = () => evaluate(b.cdp, s, STATE);
    const click = async ([x, y]) => {
      for (const type of ['mousePressed', 'mouseReleased']) await b.cdp('Input.dispatchMouseEvent', { type, x, y, button: 'left', clickCount: 1 }, s);
      await sleep(800);
    };
    const center = (sel) => evaluate(b.cdp, s, `(() => { const r = document.querySelector('${sel}').getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; })()`);
    let a = await st();
    if (name === 'docs' && await evaluate(b.cdp, s, `(() => { const e = document.querySelector('.kix-appview-editor'); return e.scrollHeight < e.clientHeight * 2; })()`)) {
      await click(p.click);  // a scratch Doc too short to scroll gets blank lines first
      for (let i = 0; i < 6; i++) run('wtype', '-s', '100', '-k', 'Return', '-k', 'Return', '-k', 'Return', '-k', 'Return', '-k', 'Return', '-k', 'Return', '-k', 'Return', '-k', 'Return');
      run('wtype', '-M', 'ctrl', '-k', 'Home', '-m', 'ctrl');
      await sleep(1500);
      a = await st();
    }
    check('1-loaded', 'on load the app keeps its keyboard', a.editable && a.focus, a);
    await click(p.click);
    const { url: url0, scrollTop: top0 } = await st();
    await type(sheet ? 'jkfhl' : 'vimium typing test jjjkhl ');
    if (sheet) await key('Return');
    a = await st();
    check('2-typed', 'after a click, letters are typing: nothing scrolls, no hints, no history move', Math.abs(a.scrollTop - top0) < 40 && a.url === url0 && !a.suspended && a.hints === 0 && a.editable, a);
    if (sheet) {
      await key('Up');
      a = await st();
      check('2b-cell', 'the typed letters are the cell\'s value', a.formula === 'jkfhl' || (a.text && a.text.includes('jkfhl')), a);
    }
    await key('Escape');
    a = await st();
    check('3-escape', 'Escape: normal mode, the app keeps its editor focused', a.suspended && a.editable && a.focus, a);
    await type('jjj');
    a = await st();
    check('4-scrolled', 'j scrolls', a.scrollTop > 0 && a.suspended, a);
    await type('gg');
    await type('f');
    a = await st();
    check('5-hints', 'f shows hints on the menus and toolbar', a.hints > 0 && a.fileHint, a);
    if (sheet) check('5b-tab-hint', 'the sheet tab has a hint', !!a.tabHint, a);
    if (a.fileHint) {
      await type(a.fileHint);
      a = await st();
      check('6-file-menu', 'picking the File hint opens its menu', a.menus > 0, a);
      await key('Escape');
      await key('Escape');
    } else await key('Escape');
    await click(p.click);
    a = await st();
    check('7-click', 'a click types again', !a.suspended && a.editable, a);
    if (sheet) {
      await type('x');
      a = await st();
      check('8-cell-edit', 'a letter starts editing the cell', a.cellEditing && !a.suspended, a);
      await key('Escape');
      a = await st();
      check('9-cell-escape', 'Escape in a cell is the Sheet\'s: it ends the edit and typing goes on', !a.cellEditing && !a.suspended && a.editable, a);
      await key('Down');
      await key('Up');
      a = await st();
      check('9b-cell-kept', 'the edit was cancelled: arrows move between cells and the cell keeps its value', a.formula === 'jkfhl' && !a.suspended, a);
      await key('Escape');
      a = await st();
      check('10-second-escape', 'a second Escape gives normal mode', a.suspended && a.editable, a);
      await click(await center('#t-formula-bar-input-container .cell-input'));
      await type('=1+1');
      a = await st();
      check('11-formula', 'the formula bar takes typing', !a.suspended && a.hints === 0 && a.formula.includes('1+1'), a);
      await key('Escape');
      a = await st();
      check('12-formula-escape', 'Escape in the formula bar is the Sheet\'s: the grid keeps the keyboard', a.active === 'div#waffle-rich-text-editor' && !a.suspended, a);
    }
    await b.cdp('Target.closeTarget', { targetId });
  }
} finally {
  writeFileSync(join(out, 'report.json'), JSON.stringify(report, null, 1));
  b.close(); server.close(); setTimeout(() => process.exit(Object.values(report).every((r) => r.every((c) => c.ok)) ? 0 : 1), 1600);
}
