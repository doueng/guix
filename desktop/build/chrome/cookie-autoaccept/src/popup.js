import { describeReport, emptyReport, isEnabledFor, normalizeSettings, setSiteEnabled, siteKey } from './policy.js';

const $ = (id) => document.getElementById(id);

async function main() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const site = siteKey(tab?.url || '');
  let settings = normalizeSettings((await chrome.storage.local.get('settings')).settings);
  const key = `tab:${tab?.id}`;
  const report = (await chrome.storage.session.get(key))[key] ?? emptyReport(site, settings);

  $('site').textContent = site ?? '';
  $('status').textContent = describeReport(site === null ? { site: null } : report);
  $('enabled').checked = isEnabledFor(settings, site);
  $('enabled').disabled = site === null;
  for (const r of document.querySelectorAll('input[name=mode]')) r.checked = r.value === settings.mode;

  async function save(next) {
    settings = next;
    await chrome.storage.local.set({ settings });
    if (tab?.id !== undefined && site !== null) chrome.tabs.reload(tab.id);
    window.close();
  }
  $('enabled').addEventListener('change', (e) => save(setSiteEnabled(settings, site, e.target.checked)));
  for (const r of document.querySelectorAll('input[name=mode]')) {
    r.addEventListener('change', () => save({ ...settings, mode: r.value }));
  }
}

main();
