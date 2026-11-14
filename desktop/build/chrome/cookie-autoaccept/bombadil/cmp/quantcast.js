// Stand-in for the InMobi (Quantcast) Choice CMP (cmp.inmobi.com choice.js), served under its real host name (run.mjs maps it to the fixture server).
// It reads the stored consent like the real one: with a choice stored it builds no UI; without one it builds its banner.
// A choice stored during this page load came from the extension's pre-answer, so it is reported through mark().
(() => {
  'use strict';
  // What the page held before this load (the probe's snapshot): a value that appeared since was written this load.
  const p = window.__probe || { startCookies: '', startLocal: [] };
  const cookie = (n) => (document.cookie.split('; ').find((c) => c.startsWith(n + '=')) || '').slice(n.length + 1) || null;
  const fresh = (n) => !p.startCookies.split('; ').some((c) => c.startsWith(n + '=')) && !p.startLocal.includes(n);
  const set = (n, v) => { document.cookie = `${n}=${v}; path=/; max-age=31536000`; };
  const cmp = (window.__cmp = { name: 'quantcast', loaded: true, uiBuilt: false, stored: null });
  const tc = cookie('euconsent-v2');
  if (tc) {
    const b64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
    const bits = [...tc].map((c) => b64.indexOf(c).toString(2).padStart(6, '0')).join('');
    cmp.stored = tc.startsWith('user-') ? tc.slice(5) : bits[152] === '1' ? 'accepted' : 'rejected';
    if (fresh('euconsent-v2')) window.mark(cmp.stored);
    return;
  }
  const c = document.createElement('div');
  c.id = 'qc-cmp2-container';
  c.innerHTML = '<div id="qc-cmp2-main"><div id="qc-cmp2-ui" data-consent-ui="cmp" class="bar"><p>We value your privacy. We and our partners store cookies.</p>' +
    '<div class="qc-cmp2-summary-buttons"><button mode="secondary">Disagree</button><button mode="primary">Agree</button></div></div></div>';
  const choose = (what) => { window.mark(what); set('euconsent-v2', `user-${what}`); c.remove(); };
  c.querySelector('[mode=secondary]').onclick = () => choose('rejected');
  c.querySelector('[mode=primary]').onclick = () => choose('accepted');
  document.body.append(c);
  cmp.uiBuilt = true;
})();
