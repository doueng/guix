// Stand-in for the Cookiebot script (consent.cookiebot.com uc.js), served under its real host name (run.mjs maps it to the fixture server).
// It reads the stored consent like the real one: with a choice stored it builds no UI; without one it builds its banner.
// A choice stored during this page load came from the extension's pre-answer, so it is reported through mark().
(() => {
  'use strict';
  // What the page held before this load (the probe's snapshot): a value that appeared since was written this load.
  const p = window.__probe || { startCookies: '', startLocal: [] };
  const cookie = (n) => (document.cookie.split('; ').find((c) => c.startsWith(n + '=')) || '').slice(n.length + 1) || null;
  const fresh = (n) => !p.startCookies.split('; ').some((c) => c.startsWith(n + '=')) && !p.startLocal.includes(n);
  const set = (n, v) => { document.cookie = `${n}=${v}; path=/; max-age=31536000`; };
  const cmp = (window.__cmp = { name: 'cookiebot', loaded: true, uiBuilt: false, stored: null });
  const stored = cookie('CookieConsent');
  window.Cookiebot = { hasResponse: Boolean(stored), dialog: { visible: !stored }, withdraw() {}, hide() {} };
  if (stored) {
    cmp.stored = /marketing:true/.test(stored) ? 'accepted' : 'rejected';
    if (fresh('CookieConsent')) window.mark(cmp.stored);
    return;
  }
  const d = document.createElement('div');
  d.id = 'CybotCookiebotDialog';
  d.className = 'bar';
  d.setAttribute('data-consent-ui', 'cmp');
  d.innerHTML = '<p>This website uses cookies.</p>' +
    '<button id="CybotCookiebotDialogBodyLevelButtonLevelOptinDeclineAll">Deny</button>' +
    '<button id="CybotCookiebotDialogBodyButtonAccept">Allow all</button>';
  const choose = (what) => {
    window.mark(what);
    set('CookieConsent', `{stamp:%27x%27%2Cmarketing:${what === 'accepted'}}`);
    Object.assign(window.Cookiebot, { hasResponse: true, dialog: { visible: false } });
    d.style.display = 'none';
  };
  d.querySelector('#CybotCookiebotDialogBodyLevelButtonLevelOptinDeclineAll').onclick = () => choose('rejected');
  d.querySelector('#CybotCookiebotDialogBodyButtonAccept').onclick = () => choose('accepted');
  document.body.append(d);
  cmp.uiBuilt = true;
})();
