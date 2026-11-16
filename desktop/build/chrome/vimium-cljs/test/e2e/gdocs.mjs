// The Google Docs checks on a real Doc, in headless Chrome: node test/e2e/gdocs.mjs <doc-url>, or GDOC_URL.
// It types into the Doc, so use a scratch Doc of your own that anyone with the link can edit: a stranger's public
// Doc that allows suggestions keeps the typed keys as anonymous suggestions. Docs' key catcher is a contenteditable
// in a 1px iframe, and its editor div scrolls; this shows the focus, scrolling and hint behaviour.
import { launch, sleep, evaluate } from '../lib/chrome.mjs';
import { controller } from '../lib/server.mjs';

const url = process.argv[2] || process.env.GDOC_URL;
if (!url) {
  console.error('usage: node test/e2e/gdocs.mjs <url of a scratch Doc you own, editable by anyone with the link>');
  process.exit(2);
}
const b = await launch({ width: 1280, height: 900 });
const results = [];
const check = (name, ok, detail) => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}: ${name}${detail ? ' ' + JSON.stringify(detail) : ''}`); };
try {
  const { targetId } = await b.cdp('Target.createTarget', { url });
  const s = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
  const ctl = controller(b.cdp, async () => s);
  await b.cdp('Page.enable', {}, s);
  await b.cdp('Page.bringToFront', {}, s);
  await sleep(Number(process.env.WAIT || 10000));
  // Mirror hints into attributes, as on the fixtures.
  await evaluate(b.cdp, s, `(() => { const m = document.createElement('meta'); m.name = 'vimium-cljs-test'; document.head.appendChild(m); })()`);
  const state = () => evaluate(b.cdp, s, `(() => {
    const f = document.querySelector('.docs-texteventtarget-iframe'), fd = f && f.contentDocument, a = fd && fd.activeElement;
    const ed = document.querySelector('.kix-appview-editor');
    const menus = [...document.querySelectorAll('.goog-menu')].filter((m) => getComputedStyle(m).display !== 'none' && getComputedStyle(m).visibility !== 'hidden' && m.getBoundingClientRect().height > 0).length;
    return { catcher: !!f, editorFocused: !!(a && a.isContentEditable && document.activeElement === f), editorTop: ed ? ed.scrollTop : null, menus,
             suspended: fd && fd.documentElement.dataset.vimiumCljsSuspended === '1',
             fileHint: (document.querySelector('#docs-menubar [role=menuitem]') || {}).dataset?.vimiumCljsHint || null };
  })()`);
  let st = await state();
  check('Docs has its key catcher frame and editor div', st.catcher && st.editorTop !== null, st);
  check('the editor keeps the focus Docs gives it on load', st.editorFocused, st);
  await ctl('click', { x: 640, y: 500 });
  await sleep(500);
  await ctl('keys', { seq: 'jjj', gap: 150 });
  await sleep(800);
  st = await state();
  check('after a click, j is typing (the document does not scroll)', st.editorFocused && st.editorTop === 0, st);
  await ctl('keys', { seq: '<esc>jjj', gap: 150 });
  await sleep(1000);
  st = await state();
  check('Escape then j scrolls the document, the editor keeps focus', st.editorTop > 0 && st.editorFocused && st.suspended, st);
  await ctl('keys', { seq: 'f' });
  await sleep(1000);
  st = await state();
  check('f hints the menubar', !!st.fileHint, st);
  if (st.fileHint) {
    await ctl('keys', { seq: st.fileHint, gap: 150 });
    await sleep(800);
    st = await state();
    check('picking the File hint opens its menu', st.menus > 0, st);
    await ctl('keys', { seq: '<esc>' });
    await sleep(500);
  }
  await ctl('click', { x: 640, y: 500 });
  await sleep(500);
  st = await state();
  check('a click types again', !st.suspended && st.editorFocused, st);
  if (process.env.SHOT) {
    const { data } = await b.cdp('Page.captureScreenshot', { format: 'png' }, s);
    (await import('node:fs')).writeFileSync(process.env.SHOT, Buffer.from(data, 'base64'));
  }
} finally {
  b.close();
  process.exitCode = results.length && results.every(Boolean) ? 0 : 1;
  setTimeout(() => process.exit(process.exitCode), 2000).unref();
}
