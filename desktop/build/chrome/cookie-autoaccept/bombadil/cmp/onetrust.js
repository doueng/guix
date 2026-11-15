(() => {
  'use strict';
  const p = window.__probe || { startCookies: '', startLocal: [] };
  const cookie = (n) => (document.cookie.split('; ').find((c) => c.startsWith(n + '=')) || '').slice(n.length + 1) || null;
  const fresh = (n) => !p.startCookies.split('; ').some((c) => c.startsWith(n + '=')) && !p.startLocal.includes(n);
  const set = (n, v) => { document.cookie = `${n}=${v}; path=/; max-age=31536000`; };
  const cmp = (window.__cmp = { name: 'onetrust', loaded: true, uiBuilt: false, stored: null });
  const consent = cookie('OptanonConsent');
  if (cookie('OptanonAlertBoxClosed') && consent) {
    cmp.stored = /C0004(%3A|:)1/.test(consent) ? 'accepted' : 'rejected';
    if (fresh('OptanonConsent')) window.mark(cmp.stored);
    return;
  }
  const sdk = document.createElement('div');
  sdk.id = 'onetrust-consent-sdk';
  sdk.innerHTML = '<div id="onetrust-banner-sdk" data-consent-ui="cmp" class="bar" role="region">' +
    '<p id="onetrust-policy-text">We use cookies to personalise content and ads.</p>' +
    '<button id="onetrust-pc-btn-handler">Cookie settings</button>' +
    '<button id="onetrust-reject-all-handler">Reject all</button>' +
    '<button id="onetrust-accept-btn-handler">Accept all cookies</button></div>';
  const choose = (what) => {
    window.mark(what);
    set('OptanonAlertBoxClosed', new Date().toISOString());
    set('OptanonConsent', `groups=C0001%3A1%2CC0004%3A${what === 'accepted' ? 1 : 0}`);
    sdk.firstChild.style.display = 'none';
  };
  sdk.querySelector('#onetrust-reject-all-handler').onclick = () => choose('rejected');
  sdk.querySelector('#onetrust-accept-btn-handler').onclick = () => choose('accepted');
  document.body.append(sdk);
  cmp.uiBuilt = true;
})();
