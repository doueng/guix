// Service worker: answers the autoconsent content script, runs vendored eval snippets in the page, keeps a per-tab
// report for the popup, and keeps the early content script and the blocking rules in step with the settings.
// Nothing leaves the browser.
import { evalSnippets } from '../vendor/autoconsent/autoconsent.esm.js';
import './cmps.js';
import {
  autoconsentConfig,
  badgeFor,
  blockRules,
  earlyScript,
  emptyReport,
  fallbackAnswer,
  filterRules,
  isEnabledFor,
  normalizeSettings,
  reduceReport,
  siteKey,
  startReport,
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

// The early script's registration and the blocking rules follow the stored settings, one change at a time.
let applying = Promise.resolve();
function applySettings() {
  applying = applying
    .then(async () => {
      const settings = await getSettings();
      const script = earlyScript(settings);
      const [have] = await chrome.scripting.getRegisteredContentScripts({ ids: [script.id] });
      await (have ? chrome.scripting.updateContentScripts([script]) : chrome.scripting.registerContentScripts([script]));
      const hosts = globalThis.cookieAutoaccept.CMPS.flatMap((c) => c.hosts);
      const old = await chrome.declarativeNetRequest.getDynamicRules();
      await chrome.declarativeNetRequest.updateDynamicRules({ removeRuleIds: old.map((r) => r.id), addRules: blockRules(settings, hosts) });
    })
    .catch((e) => console.error('cookie-autoaccept: applying settings failed', e));
  return applying;
}
chrome.runtime.onInstalled.addListener(() => applySettings());
chrome.runtime.onStartup.addListener(() => applySettings());
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.settings) applySettings();
});

const release = (tabId) => chrome.tabs.sendMessage(tabId, { type: 'prehideRelease' }).catch(() => {});

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
  if (msg.type === 'applySettings') {
    // From the popup: the tab it reloads next must already get the new early script.
    await chrome.storage.local.set({ settings: normalizeSettings(msg.settings) });
    await applySettings();
    return true;
  }
  const tabId = sender.tab?.id;
  const frameId = sender.frameId ?? 0;
  if (tabId === undefined) return undefined;
  const site = siteKey(sender.tab.url);

  switch (msg.type) {
    case 'pageStart':
      if (frameId === 0) await updateReport(tabId, async () => startReport(null, { type: 'pageStart', mainFrame: true, site, settings: await getSettings() }));
      return undefined;
    case 'init': {
      const settings = await getSettings();
      const enabled = isEnabledFor(settings, site);
      await updateReport(tabId, (r) => startReport(r, { type: 'init', mainFrame: frameId === 0, site, settings }));
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
    case 'earlyQuery':
      return { enabled: isEnabledFor(await getSettings(), site) };
    case 'fallbackQuery': {
      const report = await updateReport(tabId, async (r) => r ?? emptyReport(site, await getSettings()));
      return fallbackAnswer(report);
    }
    case 'preanswered':
      if (frameId !== 0) return undefined;
    // falls through
    case 'cmpDetected':
    case 'popupFound':
    case 'optInResult':
    case 'optOutResult':
    case 'autoconsentDone':
    case 'fallbackHidden':
    case 'fallbackClicked':
    case 'fallbackScanned':
      if (msg.type === 'fallbackScanned' && frameId !== 0) return undefined;
      await updateReport(tabId, async (r) => reduceReport(r ?? emptyReport(site, await getSettings()), msg));
      // The page has been checked, or a rule could not click: what is still hidden early may show now.
      if (msg.type === 'fallbackScanned' || (msg.type.endsWith('Result') && !msg.result)) release(tabId);
      return undefined;
    default:
      return undefined;
  }
}

const ANSWERED = new Set(['fallbackQuery', 'earlyQuery', 'applySettings']);
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!ANSWERED.has(msg?.type)) {
    handle(msg, sender).catch(() => {});
    return false;
  }
  handle(msg, sender).then(sendResponse, () => sendResponse(null));
  return true;
});

// The content bundle opens a port to signal liveness; nothing to do with it.
chrome.runtime.onConnect.addListener(() => {});

chrome.tabs.onRemoved.addListener((tabId) => chrome.storage.session.remove(reportKey(tabId)));
