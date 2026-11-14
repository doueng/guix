// Service worker: answers the autoconsent content script, runs vendored eval snippets in the page, and keeps a
// per-tab report for the popup. Nothing leaves the browser.
import { evalSnippets } from '../vendor/autoconsent/autoconsent.esm.js';
import {
  autoconsentConfig,
  badgeFor,
  emptyReport,
  filterRules,
  isEnabledFor,
  normalizeSettings,
  reduceReport,
  shouldRunFallback,
  siteKey,
} from './policy.js';

let rulesPromise = null;
function loadRules() {
  rulesPromise ??= fetch(chrome.runtime.getURL('vendor/autoconsent/rules.json'))
    .then((r) => r.json())
    .then((j) => j.autoconsent);
  return rulesPromise;
}

async function getSettings() {
  return normalizeSettings((await chrome.storage.local.get('settings')).settings);
}

const reportKey = (tabId) => `tab:${tabId}`;

// Reports change from many frames at once; chain the updates per tab so none is lost.
const pending = new Map();
function updateReport(tabId, fn) {
  const next = (pending.get(tabId) || Promise.resolve()).then(async () => {
    const key = reportKey(tabId);
    const before = (await chrome.storage.session.get(key))[key];
    const after = await fn(before);
    if (after !== before) {
      await chrome.storage.session.set({ [key]: after });
      chrome.action.setBadgeText({ tabId, text: badgeFor(after) }).catch(() => {});
    }
    return after;
  });
  pending.set(tabId, next.catch(() => {}));
  return next;
}

async function handle(msg, sender) {
  const tabId = sender.tab?.id;
  const frameId = sender.frameId ?? 0;
  if (tabId === undefined) return undefined;
  const site = siteKey(sender.tab.url);

  switch (msg.type) {
    case 'init': {
      const settings = await getSettings();
      const enabled = isEnabledFor(settings, site);
      if (frameId === 0) await updateReport(tabId, () => emptyReport(site, settings));
      const rules = enabled ? filterRules(await loadRules(), { url: msg.url || sender.url, mainFrame: frameId === 0 }) : [];
      await chrome.tabs.sendMessage(
        tabId,
        { type: 'initResp', rules: { autoconsent: rules }, config: autoconsentConfig(settings, enabled) },
        { frameId },
      );
      return undefined;
    }
    case 'eval': {
      // Only vendored snippet functions run in the page; a bare code string is refused.
      const fn = msg.snippetId && evalSnippets[msg.snippetId];
      let result = false;
      if (fn) {
        try {
          const [res] = await chrome.scripting.executeScript({ target: { tabId, frameIds: [frameId] }, world: 'MAIN', func: fn });
          result = res?.result;
        } catch {
          result = false;
        }
      }
      await chrome.tabs.sendMessage(tabId, { type: 'evalResp', id: msg.id, result }, { frameId });
      return undefined;
    }
    case 'fallbackQuery': {
      const report = await updateReport(tabId, async (r) => r ?? emptyReport(site, await getSettings()));
      return { run: shouldRunFallback(report), mode: report.mode, disabled: report.disabled };
    }
    case 'cmpDetected':
    case 'popupFound':
    case 'optInResult':
    case 'optOutResult':
    case 'autoconsentDone':
    case 'fallbackHidden':
    case 'fallbackClicked':
      await updateReport(tabId, async (r) => reduceReport(r ?? emptyReport(site, await getSettings()), msg));
      return undefined;
    default:
      return undefined;
  }
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg?.type !== 'fallbackQuery') {
    handle(msg, sender).catch(() => {});
    return false;
  }
  handle(msg, sender).then(sendResponse, () => sendResponse({ run: false }));
  return true;
});

// The content bundle opens a port to signal liveness; nothing to do with it.
chrome.runtime.onConnect.addListener(() => {});

chrome.tabs.onRemoved.addListener((tabId) => chrome.storage.session.remove(reportKey(tabId)));
