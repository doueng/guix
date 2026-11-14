// Generic consent-banner recognition. A classic script (content scripts cannot import), so it attaches to
// globalThis; the node tests load it with vm. Input is plain data, so it needs no DOM.
(function (global) {
  'use strict';
  const CONSENT = /\b(cookies?|cookie-?richtlinie|consent|gdpr|dsgvo|rgpd|tracking|einwilligung|consentement|consenso|toestemming|samtycke|samtykke|datenschutz|vie privée|privacy)\b/i;
  const COOKIEISH = /\b(cookies?|consent|einwilligung|consentement|consenso|toestemming|samtycke|samtykke|tracking|gdpr|dsgvo|rgpd)\b/i;
  const NEGATIVE = /\b(password|passwort|sign in to|log in to|subscribe to our newsletter|are you (18|over 18)|age verification)\b/i;
  const MIN_TEXT = 20;
  const MAX_TEXT = 3000;

  /**
   * banner: { text, overlay: fixed/sticky/dialog root or a whole small frame, buttons: clickable controls,
   * fields: visible text-entry fields, pageShare: its share of the page's text, 0..1 }.
   * True when it looks like a cookie notice we may act on.
   */
  function isConsentBanner(banner) {
    if (!banner || !banner.overlay || !(banner.buttons > 0)) return false;
    // A cookie notice never asks you to type: a field means a sign-up, login or contact form.
    if (banner.fields > 0) return false;
    const text = String(banner.text || '').replace(/\s+/g, ' ').trim();
    if (text.length < MIN_TEXT || text.length > MAX_TEXT) return false;
    if (!COOKIEISH.test(text) && !(CONSENT.test(text) && /\bpartners?\b/i.test(text))) return false;
    if (NEGATIVE.test(text)) return false;
    // A long text that is most of the page's text is the page itself (say a fixed app shell), not a notice.
    if (banner.pageShare > 0.6 && text.length > 1000) return false;
    return true;
  }

  // Strong: accepts everything. Weak: a plain yes. Never: anything that costs money, configures, or says no.
  const STRONG = /^(accept|allow|agree( to)?|enable|alle|tout|todo|tutt[io]|alles|aceptar todo|accepter tout)\b.*\b(all|cookies|alle|tout|todo|tutti|alles)\b|^(alle (akzeptieren|annehmen|zulassen|erlauben)|tout accepter|accepter et fermer|aceptar todo|accetta tutto|alles accepteren|godkänn alla|acceptera alla|accepteer alles)\b/i;
  const WEAK = /^(accept|i accept|accepter|aceptar|acepto|accetta|accetto|akzeptieren|ich akzeptiere|annehmen|zustimmen|einverstanden|agree|i agree|allow|ok|okay|got it|i understand|understood|consent and continue|agree and (close|continue|proceed)|zustimmen und weiter|accepter et continuer|continue|yes,? i agree|j'accepte|ich stimme zu|godkänn|acceptera|akkoord)\b/i;
  const NEVER = /(subscri|suscri|abonn|abbon|abo\b|pay|buy|£|€|\$|setting|einstellung|paramètre|manage|verwalten|gérer|customi[sz]e|anpassen|personnaliser|preference|präferenz|option|more info|mehr info|details|learn more|reject|decline|deny|refuse|ablehnen|refuser|rechazar|rifiuta|weiger|necessary|essential|notwendig|nécessaires|only|nur|without|ohne|sans)/i;

  /** Index of the button to press to accept cookies, or -1. labels: the visible text of each control. */
  function pickAcceptButton(labels) {
    let best = -1;
    let bestScore = 0;
    labels.forEach((raw, i) => {
      const label = String(raw || '').replace(/\s+/g, ' ').replace(/[›»>✓✔]/g, '').trim();
      if (!label || label.length > 40 || NEVER.test(label)) return;
      const score = STRONG.test(label) ? 2 : WEAK.test(label) ? 1 : 0;
      if (score > bestScore) {
        best = i;
        bestScore = score;
      }
    });
    return best;
  }

  /**
   * What the fallback does with a consent banner: click accept in accept mode when it can, else hide.
   * Hiding a whole frame from inside does nothing useful, so in a subframe it only clicks.
   */
  function fallbackAction({ mode, acceptIndex, inFrame }) {
    if (mode === 'accept' && acceptIndex >= 0) return 'click';
    return inFrame ? 'none' : 'hide';
  }

  /**
   * Whether a fixed element left over the page after hiding a banner is that banner's backdrop: it covers nearly the
   * whole viewport ({ coverage: 0..1 }) and shows no text and no media of its own.
   */
  function isBackdrop({ coverage, text, media }) {
    return coverage >= 0.9 && !media && String(text || '').trim().length < 20;
  }

  global.cookieAutoaccept = Object.assign(global.cookieAutoaccept || {}, { isConsentBanner, pickAcceptButton, fallbackAction, isBackdrop });
})(globalThis);
