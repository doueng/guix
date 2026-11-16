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
      last: withLast ? probe.last : null,
    };
  };
  if (window.top !== window) {
    setInterval(() => parent.postMessage({ probeFrame: location.host + location.pathname, facts: window.__probeFacts(false) }, '*'), 150);
  }
})();
