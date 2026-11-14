// The CMPs the extension can answer before they run: the script hosts that identify each one, its banner roots, and
// the consent values it reads at start. A classic script shared by the early content script, the service worker
// (import for its side effect) and the node tests (vm). Writes are only made where the value is absent, so a choice the
// user already made is never overwritten.
(function (global) {
  'use strict';
  const YEAR_S = 365 * 24 * 3600;
  // IAB TCF v2 core string. Vendor list version and CMP ids are fixed guesses; a CMP that wants a newer list re-asks.
  const VENDOR_LIST_VERSION = 100;
  const MAX_VENDOR = 1500;

  /** A TCF v2.2 TC string (core segment): every purpose, special feature and vendor yes for accept, all no for reject. */
  function tcString({ accept, now, cmpId }) {
    const bits = [];
    const put = (v, n) => {
      for (let i = n - 1; i >= 0; i--) bits.push(Math.floor(v / 2 ** i) % 2);
    };
    const flags = (count, width) => {
      for (let i = 0; i < width; i++) bits.push(accept && i < count ? 1 : 0);
    };
    const letters = (s) => {
      for (const c of s) put(c.charCodeAt(0) - 65, 6);
    };
    const ds = Math.round(now / 100);
    put(2, 6); // version
    put(ds, 36); // created
    put(ds, 36); // last updated
    put(cmpId, 12);
    put(1, 12); // CMP version
    put(1, 6); // consent screen
    letters('EN');
    put(VENDOR_LIST_VERSION, 12);
    put(5, 6); // TCF policy version (2.2)
    put(1, 1); // service specific
    put(0, 1); // non-standard texts
    flags(2, 12); // special feature opt-ins
    flags(11, 24); // purpose consents
    put(0, 24); // purpose legitimate interests
    put(0, 1); // purpose one treatment
    letters('AA'); // publisher country, unknown
    if (accept) {
      put(MAX_VENDOR, 16);
      put(1, 1); // range encoding
      put(1, 12); // one entry
      put(1, 1); // a range
      put(1, 16);
      put(MAX_VENDOR, 16);
    } else {
      put(0, 16);
      put(0, 1);
    }
    put(0, 16); // vendor legitimate interests: none
    put(0, 1);
    put(0, 12); // publisher restrictions: none
    while (bits.length % 8) bits.push(0);
    let bin = '';
    for (let i = 0; i < bits.length; i += 8) bin += String.fromCharCode(parseInt(bits.slice(i, i + 8).join(''), 2));
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  const cookie = (name, value) => ({ store: 'cookie', name, value });
  const local = (name, value) => ({ store: 'local', name, value });

  const CMPS = [
    {
      name: 'onetrust',
      hosts: ['cookielaw.org', 'onetrust.com', 'optanon.blob.core.windows.net'],
      prehide: ['#onetrust-banner-sdk', '#onetrust-consent-sdk', '.onetrust-pc-dark-filter'],
      writes(mode, { now, id }) {
        const v = mode === 'accept' ? 1 : 0;
        const groups = `C0001:1,C0002:${v},C0003:${v},C0004:${v},C0005:${v}`;
        const consent = [
          'isGpcEnabled=0', `datestamp=${encodeURIComponent(new Date(now).toUTCString())}`, 'version=202501.1.0',
          'isIABGlobal=false', 'hosts=', `consentId=${id}`, 'interactionCount=1', 'isAnonUser=1',
          'landingPath=NotLandingPage', `groups=${encodeURIComponent(groups)}`, 'AwaitingReconsent=false',
        ].join('&');
        return [cookie('OptanonAlertBoxClosed', new Date(now).toISOString()), cookie('OptanonConsent', consent)];
      },
    },
    {
      name: 'cookiebot',
      hosts: ['cookiebot.com', 'cookiebot.eu'],
      prehide: ['#CybotCookiebotDialog', '#CybotCookiebotDialogBodyUnderlay'],
      writes(mode, { now, id }) {
        const b = mode === 'accept';
        return [cookie('CookieConsent',
          `{stamp:%27${id}%27%2Cnecessary:true%2Cpreferences:${b}%2Cstatistics:${b}%2Cmarketing:${b}%2Cmethod:%27explicit%27%2Cver:1%2Cutc:${now}%2Cregion:%27eu%27}`)];
      },
    },
    {
      name: 'didomi',
      hosts: ['privacy-center.org'],
      prehide: ['#didomi-host', '#didomi-popup', '#didomi-notice'],
      writes(mode, { now, id }) {
        const purposes = ['cookies', 'select_basic_ads', 'create_ads_profile', 'select_personalized_ads', 'measure_ad_performance',
          'market_research', 'improve_products', 'create_content_profile', 'select_personalized_content', 'measure_content_performance'];
        const on = mode === 'accept';
        const t = new Date(now).toISOString();
        const token = btoa(JSON.stringify({
          user_id: id, created: t, updated: t, version: 2,
          purposes: on ? { enabled: purposes, disabled: [] } : { enabled: [], disabled: purposes },
          vendors: on ? { enabled: ['c:all'], disabled: [] } : { enabled: [], disabled: ['c:all'] },
        }));
        return [local('didomi_token', token), cookie('didomi_token', token), cookie('euconsent-v2', tcString({ accept: on, now, cmpId: 7 }))];
      },
    },
    {
      name: 'usercentrics',
      hosts: ['usercentrics.eu'],
      prehide: ['#usercentrics-root', '#usercentrics-cmp-ui'],
      writes(mode, { now }) {
        return [local('uc_user_interaction', 'true'), local('euconsent-v2', tcString({ accept: mode === 'accept', now, cmpId: 5 }))];
      },
    },
    {
      name: 'quantcast',
      hosts: ['quantcast.com', 'consensu.org', 'inmobi.com'],
      prehide: ['#qc-cmp2-container', '#qc-cmp2-main'],
      writes(mode, { now }) {
        return [cookie('euconsent-v2', tcString({ accept: mode === 'accept', now, cmpId: 10 })), cookie('addtl_consent', '1~')];
      },
    },
  ];

  /** The CMP whose script is served from host (a hostname), or null. */
  function cmpForHost(host) {
    const h = String(host || '').toLowerCase();
    return CMPS.find((c) => c.hosts.some((d) => h === d || h.endsWith('.' + d))) || null;
  }

  /** The cookie string to assign to document.cookie for one write. */
  const cookieString = (w) => `${w.name}=${w.value}; path=/; max-age=${YEAR_S}; SameSite=Lax`;

  global.cookieAutoaccept = Object.assign(global.cookieAutoaccept || {}, { CMPS, cmpForHost, cookieString, tcString });
})(globalThis);
