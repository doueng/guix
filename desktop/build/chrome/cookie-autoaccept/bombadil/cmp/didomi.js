// Stand-in for the Didomi SDK (sdk.privacy-center.org loader.js), served under its real host name (run.mjs maps it to the fixture server).
// It reads the stored consent like the real one: with a choice stored it builds no UI; without one it builds its banner.
// A choice stored during this page load came from the extension's pre-answer, so it is reported through mark().
(() => {
  'use strict';
  // What the page held before this load (the probe's snapshot): a value that appeared since was written this load.
  const p = window.__probe || { startCookies: '', startLocal: [] };
  const cookie = (n) => (document.cookie.split('; ').find((c) => c.startsWith(n + '=')) || '').slice(n.length + 1) || null;
  const fresh = (n) => !p.startCookies.split('; ').some((c) => c.startsWith(n + '=')) && !p.startLocal.includes(n);
  const set = (n, v) => { document.cookie = `${n}=${v}; path=/; max-age=31536000`; };
  const cmp = (window.__cmp = { name: 'didomi', loaded: true, uiBuilt: false, stored: null });
  const token = localStorage.getItem('didomi_token');
  if (token) {
    let enabled = [];
    try { enabled = JSON.parse(atob(token)).purposes.enabled; } catch {}
    cmp.stored = enabled.length ? 'accepted' : 'rejected';
    if (fresh('didomi_token')) window.mark(cmp.stored);
    return;
  }
  const host = document.createElement('div');
  host.id = 'didomi-host';
  host.innerHTML = '<div id="didomi-notice" data-consent-ui="cmp" class="didomi-notice-banner bar"><p>We and our partners use cookies.</p>' +
    '<button id="didomi-notice-disagree-button">Disagree</button><button id="didomi-notice-agree-button">Agree and close</button></div>';
  const choose = (what) => {
    window.mark(what);
    localStorage.setItem('didomi_token', btoa(JSON.stringify({ purposes: { enabled: what === 'accepted' ? ['cookies'] : [] } })));
    host.innerHTML = '';
  };
  host.querySelector('#didomi-notice-disagree-button').onclick = () => choose('rejected');
  host.querySelector('#didomi-notice-agree-button').onclick = () => choose('accepted');
  document.body.append(host);
  cmp.uiBuilt = true;
})();
