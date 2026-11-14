// Stand-in for the Usercentrics v3 loader (web.cmp.usercentrics.eu), served under its real host name (run.mjs maps it to the fixture server).
// It reads the stored consent like the real one: with a choice stored it builds no UI; without one it builds its banner.
// A choice stored during this page load came from the extension's pre-answer, so it is reported through mark().
(() => {
  'use strict';
  // What the page held before this load (the probe's snapshot): a value that appeared since was written this load.
  const p = window.__probe || { startCookies: '', startLocal: [] };
  const cookie = (n) => (document.cookie.split('; ').find((c) => c.startsWith(n + '=')) || '').slice(n.length + 1) || null;
  const fresh = (n) => !p.startCookies.split('; ').some((c) => c.startsWith(n + '=')) && !p.startLocal.includes(n);
  const set = (n, v) => { document.cookie = `${n}=${v}; path=/; max-age=31536000`; };
  const cmp = (window.__cmp = { name: 'usercentrics', loaded: true, uiBuilt: false, stored: null });
  if (localStorage.getItem('uc_user_interaction') === 'true') {
    const tc = localStorage.getItem('euconsent-v2') || '';
    // Purpose 1 is the first bit after 132 bits of header: char 22 of the base64url string holds it.
    const b64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
    const bits = [...tc].map((c) => b64.indexOf(c).toString(2).padStart(6, '0')).join('');
    cmp.stored = tc.startsWith('user-') ? tc.slice(5) : bits[152] === '1' ? 'accepted' : 'rejected';
    if (fresh('uc_user_interaction')) window.mark(cmp.stored);
    return;
  }
  const host = document.createElement('div');
  host.id = 'usercentrics-root';
  document.body.append(host);
  const root = host.attachShadow({ mode: 'open' });
  root.innerHTML = '<div data-testid="uc-container" data-consent-ui="cmp" style="position:fixed;bottom:0;left:0;right:0;background:#fff;padding:16px">' +
    '<p>We use cookies and similar technologies.</p><button data-testid="uc-deny-all-button">Deny</button><button data-testid="uc-accept-all-button">Accept all</button></div>';
  let accepted = false;
  const close = () => { host.style.display = 'none'; return true; };
  const choose = (what) => { window.mark(what); accepted = what === 'accepted'; localStorage.setItem('uc_user_interaction', 'true'); localStorage.setItem('euconsent-v2', `user-${what}`); close(); };
  root.querySelector('[data-testid=uc-deny-all-button]').onclick = () => choose('rejected');
  root.querySelector('[data-testid=uc-accept-all-button]').onclick = () => choose('accepted');
  window.UC_UI = {
    acceptAllConsents() { choose('accepted'); return Promise.resolve(true); },
    denyAllConsents() { choose('rejected'); return Promise.resolve(true); },
    closeCMP: close,
    areAllConsentsAccepted: () => accepted,
  };
  cmp.uiBuilt = true;
})();
