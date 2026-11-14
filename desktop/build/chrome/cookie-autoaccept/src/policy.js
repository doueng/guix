// Pure decision logic shared by the service worker and the popup. No chrome.* here, so node --test can run it.

export const MODES = ['accept', 'reject'];
export const DEFAULT_SETTINGS = Object.freeze({ mode: 'accept', disabledSites: [] });

/** Settings as stored, repaired: unknown mode falls back to accept, sites deduplicated. */
export function normalizeSettings(raw) {
  const mode = MODES.includes(raw?.mode) ? raw.mode : DEFAULT_SETTINGS.mode;
  const sites = Array.isArray(raw?.disabledSites) ? raw.disabledSites.filter((s) => typeof s === 'string' && s) : [];
  return { mode, disabledSites: [...new Set(sites)] };
}

/** The key a per-site toggle is stored under: the http(s) hostname without a leading www., else null. */
export function siteKey(url) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return null;
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
  return u.hostname.replace(/^www\./, '') || null;
}

export function isEnabledFor(settings, site) {
  return site !== null && !normalizeSettings(settings).disabledSites.includes(site);
}

export function setSiteEnabled(settings, site, enabled) {
  const s = normalizeSettings(settings);
  const rest = s.disabledSites.filter((d) => d !== site);
  return { ...s, disabledSites: enabled ? rest : [...rest, site] };
}

/**
 * The autoconsent Config for a frame. Accept uses optIn with heuristics off, because the upstream heuristic rule
 * has no optIn and throws; reject uses optOut with the tier1 heuristic (reject or acknowledge-only banners).
 */
export function autoconsentConfig(settings, enabled) {
  const { mode } = normalizeSettings(settings);
  return {
    enabled,
    autoAction: mode === 'accept' ? 'optIn' : 'optOut',
    disabledCmps: [],
    enablePrehide: true,
    enableCosmeticRules: true,
    enableGeneratedRules: true,
    enableHeuristicDetection: mode === 'reject',
    heuristicMode: mode === 'reject' ? 'tier1' : 'off',
    enablePopupMutationObserver: true,
    detectRetries: 20,
    isMainWorld: false,
    prehideTimeout: 2000,
    visualTest: false,
    performanceLoggingEnabled: false,
    logs: { lifecycle: false, rulesteps: false, detectionsteps: false, evals: false, errors: false, messages: false, waits: false },
  };
}

/** Rules that can apply in this frame: the content script would skip the rest, so do not send them. */
export function filterRules(rules, { url, mainFrame }) {
  return rules.filter((rule) => {
    const ctx = rule.runContext || {};
    if (mainFrame ? ctx.main === false : ctx.frame !== true) return false;
    if (!ctx.urlPattern) return true;
    try {
      return new RegExp(ctx.urlPattern).test(url);
    } catch {
      return false;
    }
  });
}

export function emptyReport(site, settings) {
  return {
    site,
    mode: normalizeSettings(settings).mode,
    disabled: !isEnabledFor(settings, site),
    cmp: null,
    popup: false,
    result: null,
    done: false,
    // Whether the main frame's fallback scanned the page after the rule reported its result.
    checked: false,
    hidden: 0,
    clicked: 0,
  };
}

/**
 * The tab's report when a frame starts: a new main-frame document starts a fresh one, and otherwise (a frame's
 * init) it keeps what other frames reported, since a CMP in a subframe can finish before the main frame's init.
 */
export function startReport(report, { type, mainFrame, site, settings }) {
  if (type === 'pageStart' && mainFrame) return emptyReport(site, settings);
  return report ?? emptyReport(site, settings);
}

/** Fold one content-script message into the tab's report. Unknown messages leave it unchanged. */
export function reduceReport(report, msg) {
  switch (msg?.type) {
    case 'cmpDetected':
      return report.cmp ? report : { ...report, cmp: msg.cmp };
    case 'popupFound':
      return { ...report, cmp: msg.cmp, popup: true };
    case 'optInResult':
    case 'optOutResult':
      return { ...report, cmp: msg.cmp, result: Boolean(msg.result), checked: false };
    case 'autoconsentDone':
      return { ...report, cmp: msg.cmp, done: true, result: report.result ?? true };
    case 'fallbackHidden':
      return { ...report, hidden: report.hidden + (Number(msg.count) || 0) };
    case 'fallbackClicked':
      return { ...report, clicked: (report.clicked || 0) + (Number(msg.count) || 0) };
    case 'fallbackScanned':
      return msg.afterResult && report.result !== null && !report.checked ? { ...report, checked: true } : report;
    default:
      return report;
  }
}

/**
 * The generic fallback runs where the extension is on, except while autoconsent is working on a popup it found.
 * After autoconsent finishes it may still run: some upstream rules report success yet leave the banner up.
 */
export function shouldRunFallback(report) {
  return Boolean(report) && !report.disabled && !(report.popup && report.result === null && !report.done);
}

/**
 * The service worker's answer to the fallback's query. afterResult says the rule's result was in when the scan
 * started, so that scan checks the rule's claim: some rules report success and leave the banner up.
 */
export function fallbackAnswer(report) {
  return { run: shouldRunFallback(report), mode: report.mode, disabled: report.disabled, afterResult: report.result !== null };
}

const banners = (n) => `${n} cookie banner${n > 1 ? 's' : ''}`;

/** One line for the popup. */
export function describeReport(report) {
  if (!report || report.site === null) return 'Not a web page.';
  if (report.disabled) return 'Off for this site.';
  const verb = report.mode === 'accept' ? 'Accepted' : 'Rejected';
  if (report.clicked > 0) return `Accepted cookies (generic button${report.cmp ? `, after ${report.cmp}` : ''}).`;
  // A banner the fallback still had to hide after a rule's result means the rule's claim did not hold.
  if (report.hidden > 0 && report.result !== null) return `Hid ${banners(report.hidden)} (generic, after ${report.cmp}).`;
  if (report.popup && report.result === true && !report.checked) return `Found ${report.cmp}, checking...`;
  if (report.popup && report.result === true) return `${verb} cookies (${report.cmp}).`;
  if (report.popup && report.result === false) return `Found ${report.cmp} but could not click it.`;
  if (report.popup) return `Found ${report.cmp}, working...`;
  if (report.hidden > 0) return `Hid ${banners(report.hidden)} (generic).`;
  if (report.done) return `Handled ${report.cmp} (hidden, no popup shown).`;
  return 'No cookie popup seen.';
}

/** Badge text for the toolbar icon. */
export function badgeFor(report) {
  if (!report || report.disabled) return '';
  if ((report.popup && report.result && report.checked) || report.hidden > 0 || report.clicked > 0) return '✓';
  return '';
}
