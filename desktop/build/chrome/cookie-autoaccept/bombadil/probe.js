(() => {
  'use strict';
  const isTop = window.top === window;
  const ACCEPT = /^(accept|agree|allow|ok\b|okay|got it|i agree|yes)/i;
  const REJECT = /(reject|deny|disagree|decline|refuse|necessary|required only|only)/i;
  const AUTOCONSENT_STYLES = 'style#autoconsent-prehide, style#autoconsent-css-rules';

  const state = {
    pageId: `${Math.round(performance.timeOrigin)}-${Math.random().toString(36).slice(2, 8)}`,
    path: location.pathname,
    host: location.hostname,
    settings: null,
    acts: [],
    popup: null,
    bannerSince: null,
    blockedSince: null,
    keepHiddenSince: null,
    startCookies: document.cookie,
    startLocal: (() => { try { return Object.keys(localStorage); } catch { return []; } })(),
  };
  try {
    const x = new XMLHttpRequest();
    x.open('GET', '/__state', false);
    x.send();
    state.settings = JSON.parse(x.responseText);
  } catch {
    state.settings = null;
  }

  const labelOf = (el) =>
    String((el && (el.innerText || el.value || (el.getAttribute && el.getAttribute('aria-label')) || el.id || el.tagName)) || '')
      .replace(/\s+/g, ' ').trim().slice(0, 60);
  const inConsent = (path) => path.some((n) => n instanceof Element && n.hasAttribute('data-consent-ui'));
  const isAccept = (label) => ACCEPT.test(label) && !REJECT.test(label);

  function record(act) {
    act.t = Date.now();
    act.frame = !isTop;
    state.acts.push(act);
    if (!isTop) parent.postMessage({ __probe: act }, '*');
  }

  let lastFrameClick = null;
  if (isTop) {
    // Registered before the fixture's own listeners, so they never see the probe's messages.
    addEventListener('message', (e) => {
      if (!e.data || !e.data.__probe) return;
      e.stopImmediatePropagation();
      const act = e.data.__probe;
      if (act.kind === 'click') lastFrameClick = act;
      if (!act.trusted) state.acts.push(act);
    }, true);
  }

  // window.event is undefined in listeners inside a shadow tree, so track the dispatching click here.
  let dispatching = null;
  addEventListener('click', (e) => {
    dispatching = e;
    setTimeout(() => { if (dispatching === e) dispatching = null; }, 0);
    const path = e.composedPath();
    const el = path[0] instanceof Element ? path[0] : null;
    const label = labelOf(el);
    if (e.isTrusted) {
      if (!isTop) parent.postMessage({ __probe: { kind: 'click', trusted: true, t: Date.now(), frame: true } }, '*');
      return;
    }
    const link = el && el.closest('a[href]');
    const href = link ? link.getAttribute('href') : null;
    const submit = Boolean(el && el.closest('form') && el.matches('button:not([type=button]):not([type=reset]), input[type=submit]'));
    record({
      kind: 'click',
      trusted: false,
      label,
      inConsent: inConsent(path),
      accept: isAccept(label),
      navigates: Boolean(href && !href.startsWith('#') && !href.startsWith('javascript:')),
      submits: submit,
    });
  }, true);

  addEventListener('submit', (e) => {
    if (!e.isTrusted) record({ kind: 'submit', label: labelOf(e.target) });
  }, true);

  let markFn = null;
  function wrappedMark(what) {
    const ev = dispatching || window.event;
    let by = 'script';
    if (ev && ev.type === 'message') by = lastFrameClick && lastFrameClick.trusted ? 'user' : 'script';
    else if (ev && ev.isTrusted) by = 'user';
    record({ kind: 'decision', what, by });
    return markFn && markFn(what);
  }
  Object.defineProperty(window, 'mark', {
    configurable: true,
    get: () => (markFn ? wrappedMark : undefined),
    set: (f) => { markFn = f; },
  });

  function shadowRoots(root) {
    const found = [];
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    for (let el = walker.currentNode; el; el = walker.nextNode()) {
      if (el.shadowRoot) found.push(el.shadowRoot, ...shadowRoots(el.shadowRoot));
    }
    return found;
  }
  const everywhere = (sel) => {
    if (!document.documentElement) return [];
    const out = [...document.querySelectorAll(sel)];
    for (const r of shadowRoots(document.documentElement)) out.push(...r.querySelectorAll(sel));
    return out;
  };
  const shown = (el) => {
    if (!el.isConnected) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && el.checkVisibility({ opacityProperty: true, visibilityProperty: true });
  };
  const parentAcross = (el) => el.parentElement || (el.getRootNode() instanceof ShadowRoot ? el.getRootNode().host : null);

  function blocker() {
    const vw = innerWidth, vh = innerHeight;
    let el = document.elementFromPoint(vw / 2, vh / 2);
    while (el && el.shadowRoot) {
      const inner = el.shadowRoot.elementFromPoint(vw / 2, vh / 2);
      if (!inner || inner === el) break;
      el = inner;
    }
    for (let n = el; n && n !== document.body && n !== document.documentElement; n = parentAcross(n)) {
      const pos = getComputedStyle(n).position;
      if (pos !== 'fixed' && pos !== 'sticky') continue;
      const r = n.getBoundingClientRect();
      if (r.width * r.height >= 0.5 * vw * vh) return `overlay ${n.id || n.tagName.toLowerCase()}`;
    }
    for (const n of [document.documentElement, document.body]) {
      if (!n) continue;
      const cs = getComputedStyle(n);
      if (cs.overflowY === 'hidden' || cs.overflow === 'hidden') return `scroll lock on ${n.tagName.toLowerCase()}`;
      if (n === document.body && cs.position === 'fixed') return 'scroll lock: body position fixed';
    }
    return null;
  }

  function facts() {
    const consent = everywhere('[data-consent-ui]');
    const visible = consent.filter(shown);
    const kinds = visible.map((el) => el.getAttribute('data-consent-ui') || 'banner');
    const now = Date.now();
    if (visible.length) state.bannerSince ??= now;
    else state.bannerSince = null;
    const block = visible.length ? null : blocker();
    if (block) state.blockedSince ??= now;
    else state.blockedSince = null;
    const marks = everywhere('[data-cookie-autoaccept]').map((el) => el.getAttribute('data-cookie-autoaccept'));
    const keepHidden = everywhere('[data-keep]').filter((el) => !shown(el)).map((el) => el.id || el.tagName);
    if (keepHidden.length) state.keepHiddenSince ??= now;
    else state.keepHiddenSince = null;
    const early = document.getElementById('cookie-autoaccept-prehide');
    const cmp = window.__cmp || null;
    return {
      pageId: state.pageId,
      path: state.path,
      host: state.host,
      settings: state.settings,
      ready: document.readyState === 'complete',
      ageMs: Math.round(performance.now()),
      bannerVisible: visible.length > 0,
      cmpBannerVisible: kinds.includes('cmp'),
      cmpFrames,
      bannerKinds: kinds,
      bannerMs: state.bannerSince === null ? 0 : now - state.bannerSince,
      consentCount: consent.length,
      blocker: block,
      blockedMs: state.blockedSince === null ? 0 : now - state.blockedSince,
      keepHidden,
      keepHiddenMs: state.keepHiddenSince === null ? 0 : now - state.keepHiddenSince,
      earlyScript: Boolean(early),
      prehideActive: Boolean(early && early.media !== 'not all'),
      cmp: cmp && { name: cmp.name, uiBuilt: cmp.uiBuilt, stored: cmp.stored },
      cmpBlocked: Boolean(window.__cmpBlocked),
      autoconsentStyles: document.querySelectorAll(AUTOCONSENT_STYLES).length,
      marks,
      acts: state.acts.slice(-40),
      popup: state.popup,
    };
  }
  // Bombadil samples only settled states, missing briefly shown banners, so count every frame one is visible.
  let cmpFrames = 0;
  const tick = () => {
    if (everywhere('[data-consent-ui="cmp"]').some(shown)) cmpFrames++;
    if (performance.now() < 20000) requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);

  window.__probeFacts = facts;
  window.__probe = state;
})();
