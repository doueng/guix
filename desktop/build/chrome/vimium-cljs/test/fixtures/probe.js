(() => {
  const probe = (window.__probe = { pageKeys: [], fieldKeys: [], frames: {}, hintsSince: 0, last: null });
  const deepAll = (root, sel, out = []) => {
    for (const el of root.querySelectorAll('*')) {
      if (el.matches(sel)) out.push(el);
      if (el.shadowRoot) deepAll(el.shadowRoot, sel, out);
    }
    return out;
  };
  const visible = (el) => {
    const w = innerWidth, h = innerHeight;
    for (const r of el.getClientRects()) {
      const l = Math.max(0, r.left), t = Math.max(0, r.top), rr = Math.min(w, r.right), b = Math.min(h, r.bottom);
      if (rr - l < 3 || b - t < 3) continue;
      const s = getComputedStyle(el);
      if (s.visibility === 'hidden' || s.opacity === '0') return false;
      const root = el.getRootNode();
      const hit = root.elementFromPoint((l + rr) / 2, (t + b) / 2);
      return !hit || hit === el || el.contains(hit) || hit.contains(el);
    }
    return false;
  };
  const textCount = (q) => {
    let n = 0;
    const walk = (root) => {
      const tw = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
      let node;
      while ((node = tw.nextNode())) {
        if (node.nodeType === 3) {
          const p = node.parentElement;
          if (!p || /^(SCRIPT|STYLE|TEXTAREA|NOSCRIPT)$/.test(p.tagName) || !p.getClientRects().length) continue;
          n += node.data.toLowerCase().split(q).length - 1;
        } else if (node.shadowRoot) walk(node.shadowRoot);
      }
    };
    if (document.body) walk(document.body);
    return n;
  };
  const field = (el) => el && (el.matches?.('input,textarea,[contenteditable]'));
  addEventListener('keydown', (e) => {
    const t = e.composedPath()[0];
    (field(t) ? probe.fieldKeys : probe.pageKeys).push(e.key);
  });
  addEventListener('message', (e) => {
    if (e.data && e.data.probeFrame) probe.frames[e.data.probeFrame] = e.data.facts;
  });
  let lastMode = '';
  // The badge the page should show, read from the DOM only: the mode and suspension the content script
  // mirrors into the frame that has the keyboard, and what has the focus there. null when the keyboard is in
  // a frame this page cannot read or in the extension's own UI (the Vomnibar is checked by its action).
  const textTypes = ['', 'text', 'search', 'email', 'url', 'number', 'password', 'date', 'datetime-local', 'month', 'time', 'week', 'tel'];
  const editable = (el) => !!el && el.nodeType === 1 && (el.tagName === 'INPUT'
    ? textTypes.includes((el.getAttribute('type') || '').toLowerCase()) && !el.disabled && !el.readOnly
    : el.tagName === 'TEXTAREA' ? !el.disabled && !el.readOnly
    : el.tagName === 'SELECT' || el.isContentEditable || el.getAttribute('role') === 'textbox');
  const deepActive = (d) => { let a = d.activeElement; while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement; return a; };
  const outOfView = (el) => { const r = el.getBoundingClientRect(), v = el.ownerDocument.defaultView;
    return r.bottom <= 0 || r.right <= 0 || r.top >= v.innerHeight || r.left >= v.innerWidth; };
  // above or left of the page, where no scrolling reaches (Sheets' idle cell editor)
  const offPage = (el) => { const r = el.getBoundingClientRect(), v = el.ownerDocument.defaultView;
    return r.bottom + v.scrollY <= 0 || r.right + v.scrollX <= 0; };
  const expectedBadge = () => {
    if (!document.hasFocus()) return null;
    let d = document, hidden = false;
    const a = document.activeElement;
    if (a && /^(IFRAME|FRAME)$/.test(a.tagName)) {
      try { d = a.contentDocument; } catch { return null; }
      if (!d || !d.documentElement.dataset.vimiumCljsMode && !d.querySelector('[contenteditable]')) return null;
      const r = a.getBoundingClientRect();
      hidden = r.width <= 3 || r.height <= 3 || outOfView(a);
    }
    if (a && a.tagName === 'VIMIUM-CLJS-UI') return null;
    const ds = d.documentElement.dataset, mode = ds.vimiumCljsMode || 'normal';
    const named = { hints: 'hints', visual: 'visual', find: 'find', 'pass-next': 'pass', 'marks-create': 'mark', 'marks-goto': 'mark',
      insert: 'insert', 'focus-input': 'insert' }[mode];
    if (named) return named;
    const el = deepActive(d);
    if (!editable(el)) return 'normal';
    if (hidden || offPage(el)) return ds.vimiumCljsSuspended === '1' ? 'normal' : 'insert';
    return 'insert';
  };
  let lastExpected = null, expectedSince = 0;
  window.__probeFacts = (withLast = true) => {
    const mode = document.documentElement.dataset.vimiumCljsMode || 'normal';
    if (mode !== lastMode) { lastMode = mode; probe.hintsSince = mode === 'hints' ? Date.now() : 0; probe.hintsScrollY = Math.round(scrollY); }
    const links = deepAll(document, 'a[href]').map((a) => ({ id: a.id, visible: visible(a), hint: a.dataset.vimiumCljsHint || null }));
    const hl = window.CSS && CSS.highlights;
    return {
      url: location.href,
      ready: document.documentElement.dataset.vimiumCljsReady === '1',
      mode,
      hintsMs: probe.hintsSince ? Date.now() - probe.hintsSince : 0,
      hintsScrollY: probe.hintsScrollY,
      scrollY: Math.round(scrollY),
      maxScrollY: Math.max(0, document.documentElement.scrollHeight - innerHeight),
      links,
      findHighlights: hl && hl.get('vimium-cljs-find') ? hl.get('vimium-cljs-find').size : 0,
      findCurrent: hl && hl.get('vimium-cljs-find-current') ? hl.get('vimium-cljs-find-current').size : 0,
      needles: textCount('needle'),
      pageKeys: probe.pageKeys.length,
      fieldKeys: probe.fieldKeys.join(''),
      fields: deepAll(document, 'input,textarea,[contenteditable]').map((f) => ({ id: f.id, value: f.value ?? f.textContent })),
      active: (() => { let a = document.activeElement; while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement; return a ? a.id || a.tagName : null; })(),
      frames: probe.frames,
      docs: window.__docsFacts ? window.__docsFacts() : null,
      sheet: window.__sheetFacts ? window.__sheetFacts() : null,
      indicator: document.documentElement.dataset.vimiumCljsIndicator || null,
      expected: (() => { const e = expectedBadge(); if (e !== lastExpected) { lastExpected = e; expectedSince = Date.now(); } return e; })(),
      expectedMs: Date.now() - expectedSince,
      last: withLast ? probe.last : null,
    };
  };
  if (window.top !== window) {
    setInterval(() => parent.postMessage({ probeFrame: location.host + location.pathname, facts: window.__probeFacts(false) }, '*'), 150);
  }
})();
