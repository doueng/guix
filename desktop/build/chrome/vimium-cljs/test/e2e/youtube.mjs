// Check on the real site: opens a URL in the headless Chrome with ext/ loaded, optionally
// clicks the element a JS expression names, presses keys and reports which elements scrolled.
//   node test/e2e/youtube.mjs [url] [keys] [click-expr]
// With no arguments it is the YouTube regression check: on a long results page, click
// Subscriptions in the guide (YouTube navigates in place), go back, press j five times, and
// exit 1 unless the page scrolled and the guide did not. Needs network; logged out.
import { launch, sleep, evaluate, setSettings } from '../lib/chrome.mjs';
import { controller } from '../lib/server.mjs';

const url = process.argv[2] || 'https://www.youtube.com/results?search_query=clojure';
const seq = process.argv[3] || 'jjjjj';
const regression = process.argv.length <= 2;
const clickExpr = process.argv[4] || (regression ? "[...document.querySelectorAll('ytd-guide-entry-renderer a#endpoint')].find(a => /Subscriptions/.test(a.textContent))" : undefined);
const back = process.env.BACK || regression;
const height = Number(process.env.HEIGHT || 600);
const b = await launch({ width: 1400, height });
try {
  await setSettings(b.cdp, b.extensionId, { grabBackFocus: true, exclusionRules: [] });
  // Skip YouTube's cookie-consent interstitial (SOCS=CAI is "reject all").
  await b.cdp('Storage.setCookies', { cookies: [{ name: 'SOCS', value: 'CAI', domain: '.youtube.com', path: '/', secure: true }] });
  const { targetId } = await b.cdp('Target.createTarget', { url });
  const s = (await b.cdp('Target.attachToTarget', { targetId, flatten: true })).sessionId;
  const ctl = controller(b.cdp, async () => s);
  await b.cdp('Page.enable', {}, s);
  await b.cdp('Page.bringToFront', {}, s);
  await sleep(Number(process.env.WAIT || 12000));
  const snap = `(() => {
    const out = {};
    const walk = (root) => { for (const el of root.querySelectorAll('*')) {
      if (el.scrollTop || el.scrollLeft) out[(el.id ? el.tagName + '#' + el.id : el.tagName) + '@' + Math.round(el.getBoundingClientRect().left)] = el.scrollTop;
      if (el.shadowRoot) walk(el.shadowRoot); } };
    walk(document);
    let a = document.activeElement; while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;
    const cands = [];
    const walk2 = (root) => { for (const el of root.querySelectorAll('*')) {
      const s = getComputedStyle(el);
      if (el.scrollHeight > el.clientHeight + 1 && el.clientHeight > 0 && /auto|scroll|hidden/.test(s.overflowY)) cands.push((el.id ? el.tagName + '#' + el.id : el.tagName) + ':' + s.overflowY + ':' + s.position + ':' + el.clientHeight + '/' + el.scrollHeight);
      if (el.shadowRoot) walk2(el.shadowRoot); } };
    walk2(document);
    return { url: location.href, active: a && (a.tagName + '#' + a.id), cands, scrollY: scrollY, root: document.scrollingElement.scrollHeight + '/' + innerHeight, scrolled: out };
  })()`;
  if (clickExpr) {
    const r = await evaluate(b.cdp, s, `(() => { const e = (${clickExpr}); if (!e) return null; const r = e.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2, e.textContent.trim().slice(0, 40)]; })()`);
    console.log('click', JSON.stringify(r));
    if (r) { await ctl('click', { x: r[0], y: r[1] }); await sleep(5000); }
    console.log('clicked to', await evaluate(b.cdp, s, 'location.href'));
    // BACK=1: go back to the start URL in place, as the user would with H.
    if (back) { await evaluate(b.cdp, s, 'history.back()'); await sleep(5000); }
  }
  console.log('before', JSON.stringify(await evaluate(b.cdp, s, snap)));
  if (process.env.PROBE) console.log('probe', JSON.stringify(await evaluate(b.cdp, s, process.env.PROBE)));
  await ctl('keys', { seq, gap: 150 });
  await sleep(1500);
  const after = await evaluate(b.cdp, s, snap);
  console.log('after ', JSON.stringify(after));
  if (regression) {
    const guide = Object.entries(after.scrolled).filter(([k]) => k.includes('guide')).map(([, v]) => v).reduce((a, v) => a + v, 0);
    const ok = after.scrollY > 0 && guide === 0;
    console.log(ok ? 'PASS: j scrolled the page, not the guide' : `FAIL: page scrollY ${after.scrollY}, guide scrolled ${guide}`);
    process.exitCode = ok ? 0 : 1;
  }
  if (process.env.SHOT) {
    const { data } = await b.cdp('Page.captureScreenshot', { format: 'png' }, s);
    (await import('node:fs')).writeFileSync(process.env.SHOT, Buffer.from(data, 'base64'));
  }
} finally { b.close(); setTimeout(() => process.exit(process.exitCode ?? 0), 2000).unref(); }
