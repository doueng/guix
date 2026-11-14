// Runs at document_start in every frame of an enabled site (the worker registers it, leaving disabled sites out).
// 1. Hides known consent banners before they paint: opacity 0, so autoconsent and the fallback still find and answer
//    them. The worker sends prehideRelease once the page was checked; a banner root added after that is hidden again.
//    Without a release, the hiding ends after HOLD_MS, so nothing that merely looks like a banner stays hidden.
// 2. In the top frame, answers a known CMP before its script runs: when a script from a CMP host is added to the page,
//    it writes the consent that CMP reads at start (src/cmps.js), unless the page already has a value.
(() => {
  'use strict';
  const { earlyMode, prehideSelectors, cmpForHost, cookieString } = globalThis.cookieAutoaccept;
  const HOLD_MS = 10000;
  const WATCH_MS = 30000;
  const isTop = window.top === window;
  const selector = prehideSelectors.join(',');

  const style = document.createElement('style');
  style.id = 'cookie-autoaccept-prehide';
  // One rule per selector: a selector this browser cannot parse then drops only its own rule.
  style.textContent = prehideSelectors
    .map((s) => `${s}{opacity:0!important;pointer-events:none!important}`)
    .join('\n');
  const root = () => document.head || document.documentElement;

  let hiding = false;
  let holdTimer = 0;
  const released = new WeakSet();
  function hide() {
    if (hiding) return;
    hiding = true;
    style.media = 'all';
    if (!style.isConnected && root()) root().append(style);
    clearTimeout(holdTimer);
    holdTimer = setTimeout(release, HOLD_MS);
  }
  function release() {
    if (!hiding) return;
    hiding = false;
    clearTimeout(holdTimer);
    style.media = 'not all';
    for (const el of matches()) released.add(el);
  }
  function matches() {
    try {
      return document.querySelectorAll(selector);
    } catch {
      return [];
    }
  }

  // Top frame: CMP scripts seen as they are added, before they run.
  const answered = new Set();
  function preanswer(node) {
    if (node.tagName !== 'SCRIPT' || !node.src) return;
    let host;
    try {
      host = new URL(node.src, location.href).hostname;
    } catch {
      return;
    }
    const cmp = cmpForHost(host);
    if (!cmp || answered.has(cmp.name)) return;
    answered.add(cmp.name);
    const id = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
    let wrote = 0;
    for (const w of cmp.writes(earlyMode, { now: Date.now(), id })) {
      try {
        if (w.store === 'cookie' && !document.cookie.split('; ').some((c) => c.startsWith(`${w.name}=`))) {
          document.cookie = cookieString(w);
          wrote++;
        }
        if (w.store === 'local' && localStorage.getItem(w.name) === null) {
          localStorage.setItem(w.name, w.value);
          wrote++;
        }
      } catch {
        // Storage can be blocked (sandboxed or cookie-less documents); the CMP then asks and autoconsent answers.
      }
    }
    if (wrote) chrome.runtime.sendMessage({ type: 'preanswered', cmp: cmp.name }).catch(() => {});
  }

  // After a release, a banner root in an added subtree hides again. The observer callback runs as a microtask of the
  // task that added it, so before any paint and before any animation-frame callback could see it. Only the added
  // subtrees are matched, not the whole document.
  const isNewBanner = (n) =>
    (n.matches(selector) && !released.has(n)) || [...n.querySelectorAll(selector)].some((el) => !released.has(el));

  const observer = new MutationObserver((records) => {
    if (!style.isConnected && root()) root().append(style);
    for (const r of records) {
      for (const n of r.addedNodes) {
        if (n.nodeType !== 1) continue;
        if (isTop) preanswer(n);
        if (!hiding) {
          try {
            if (isNewBanner(n)) hide();
          } catch {
            // An unparsable selector in the joined list; the stylesheet still drops only that rule.
          }
        }
      }
    }
  });
  observer.observe(document, { childList: true, subtree: true });
  setTimeout(() => observer.disconnect(), WATCH_MS);
  hide();

  chrome.runtime.onMessage.addListener((msg) => {
    if (msg?.type === 'prehideRelease') release();
  });

  // A subframe's own URL decides whether this script runs, but its top site's toggle is what counts.
  if (!isTop) {
    chrome.runtime.sendMessage({ type: 'earlyQuery' }).then((r) => {
      if (r && !r.enabled) {
        release();
        observer.disconnect();
        style.remove();
      }
    }, () => {});
  }
})();
