// Generic fallback for banners autoconsent did not handle, in light and open shadow DOM and in small consent frames.
// Accept mode clicks the banner's accept button when it has one; otherwise (and in reject mode) it hides the banner.
// It asks the service worker first, and stops watching for late banners after 30 s.
(function () {
  'use strict';
  const { isConsentBanner, pickAcceptButton, fallbackAction, isBackdrop } = globalThis.cookieAutoaccept;
  const RETRIES_MS = [3000, 7000, 12000];
  const WATCH_MS = 30000;
  const DEBOUNCE_MS = 700;
  const MAX_WALK = 10000;
  const HINT = '[id*=cookie i],[class*=cookie i],[id*=consent i],[class*=consent i],[id*=gdpr i],[class*=gdpr i],' +
    '[role=dialog],[role=alertdialog],[aria-modal=true],dialog[open]';
  const CONTROLS = 'button,[role=button],input[type=button],input[type=submit],a';
  const FIELDS = 'textarea,input:not([type]),input[type=text],input[type=email],input[type=password],input[type=tel],input[type=search],input[type=number]';
  const DONE = 'data-cookie-autoaccept';
  const inFrame = window.top !== window;

  function shadowRoots(root) {
    const found = [];
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    for (let n = 0, el = walker.currentNode; el && n < MAX_WALK; n++, el = walker.nextNode()) {
      if (el.shadowRoot) found.push(el.shadowRoot, ...shadowRoots(el.shadowRoot));
    }
    return found;
  }

  function parentAcross(el) {
    return el.parentElement || (el.getRootNode() instanceof ShadowRoot ? el.getRootNode().host : null);
  }

  // The outermost fixed or sticky ancestor (crossing shadow hosts), else a dialog-like element itself.
  function overlayRoot(el) {
    let best = null;
    for (let n = el; n && n !== document.body && n !== document.documentElement; n = parentAcross(n)) {
      const pos = getComputedStyle(n).position;
      if (pos === 'fixed' || pos === 'sticky') best = n;
    }
    if (best) return best;
    return el.matches('[role=dialog],[role=alertdialog],[aria-modal=true],dialog[open]') ? el : null;
  }

  function visible(el) {
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
  }

  // Text, visible controls and visible text fields of a banner, looking into open shadow roots below it.
  function contents(el) {
    let text = el.innerText || '';
    const controls = [...el.querySelectorAll(CONTROLS)];
    const fields = [...el.querySelectorAll(FIELDS)];
    for (const sr of el.shadowRoot ? [el.shadowRoot, ...shadowRoots(el.shadowRoot)] : shadowRoots(el)) {
      for (const child of sr.children) text += ' ' + (child.innerText || '');
      controls.push(...sr.querySelectorAll(CONTROLS));
      fields.push(...sr.querySelectorAll(FIELDS));
    }
    const shown = controls.filter(visible);
    const labels = shown.map((c) => c.innerText || c.value || c.getAttribute('aria-label') || '');
    return { text: text.slice(0, 4000), controls: shown, labels, fields: fields.filter(visible).length };
  }

  function unlockScroll() {
    for (const el of [document.documentElement, document.body]) {
      if (el && getComputedStyle(el).overflow === 'hidden') el.style.setProperty('overflow', 'auto', 'important');
    }
  }

  // One banner: returns 'click', 'hide' or null.
  function act(root, mode, pageText) {
    const { text, controls, labels, fields } = contents(root);
    const pageShare = pageText ? text.length / Math.max(pageText(), 1) : 0;
    if (!isConsentBanner({ text, overlay: true, buttons: controls.length, fields, pageShare })) return null;
    const acceptIndex = pickAcceptButton(labels);
    const action = fallbackAction({ mode, acceptIndex, inFrame: root === document.body && inFrame });
    root.setAttribute(DONE, action);
    if (action === 'click') controls[acceptIndex].click();
    if (action === 'hide') root.style.setProperty('display', 'none', 'important');
    return action === 'none' ? null : action;
  }

  // A modal's dimmed backdrop is often a separate element, and would still block the page once its banner is hidden.
  function hideBackdrops() {
    for (let i = 0; i < 3; i++) {
      let found = null;
      for (let n = document.elementFromPoint(innerWidth / 2, innerHeight / 2); n && n !== document.body && n !== document.documentElement; n = parentAcross(n)) {
        if (getComputedStyle(n).position !== 'fixed') continue;
        const r = n.getBoundingClientRect();
        const media = /^(IFRAME|VIDEO|CANVAS|IMG|svg)$/.test(n.tagName) || Boolean(n.querySelector('iframe,video,canvas,img,svg'));
        if (isBackdrop({ coverage: (r.width * r.height) / (innerWidth * innerHeight), text: n.innerText, media })) found = n;
      }
      if (!found) return;
      found.setAttribute(DONE, 'backdrop');
      found.style.setProperty('display', 'none', 'important');
    }
  }

  function scan(mode) {
    const tally = { click: 0, hide: 0 };
    // A small frame that is all banner, such as a CMP message iframe.
    if (inFrame) {
      if (document.body && !document.body.hasAttribute(DONE) && visible(document.body)) {
        const a = act(document.body, mode, null);
        if (a) tally[a]++;
      }
      return tally;
    }
    const roots = [document, ...shadowRoots(document.documentElement)];
    const candidates = new Set();
    for (const r of roots) for (const el of r.querySelectorAll(HINT)) candidates.add(el);
    if (document.body) for (const el of document.body.children) candidates.add(el);
    let bodyLength = null;
    const pageText = () => (bodyLength ??= (document.body?.innerText || '').length);
    const seen = new Set();
    for (const c of candidates) {
      const root = overlayRoot(c);
      if (!root || seen.has(root) || root.hasAttribute(DONE) || !visible(root)) continue;
      seen.add(root);
      const a = act(root, mode, pageText);
      if (a) tally[a]++;
    }
    if (tally.hide) hideBackdrops();
    if (tally.click || tally.hide) unlockScroll();
    return tally;
  }

  async function ask() {
    try {
      return await chrome.runtime.sendMessage({ type: 'fallbackQuery' });
    } catch {
      return null;
    }
  }

  async function runOnce() {
    const answer = await ask();
    if (!answer?.run) return;
    const { click, hide } = scan(answer.mode);
    const send = (type, count) => count && chrome.runtime.sendMessage({ type, count }).catch(() => {});
    send('fallbackClicked', click);
    send('fallbackHidden', hide);
    // The page was looked at after the rule's result, so the popup may now repeat the rule's claim.
    if (!inFrame) chrome.runtime.sendMessage({ type: 'fallbackScanned', afterResult: Boolean(answer.afterResult) }).catch(() => {});
  }

  let timer = 0;
  const schedule = () => {
    clearTimeout(timer);
    timer = setTimeout(runOnce, DEBOUNCE_MS);
  };
  const observer = new MutationObserver(schedule);
  for (const ms of RETRIES_MS) setTimeout(runOnce, ms);
  setTimeout(() => {
    if (document.documentElement) observer.observe(document.documentElement, { childList: true, subtree: true });
  }, RETRIES_MS[0]);
  setTimeout(() => {
    observer.disconnect();
    clearTimeout(timer);
  }, WATCH_MS);
})();
