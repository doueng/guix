import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const ctx = vm.createContext({ btoa, atob });
vm.runInContext(readFileSync(new URL('../src/cmps.js', import.meta.url), 'utf8'), ctx);
const { CMPS, cmpForHost, cookieString, tcString } = ctx.cookieAutoaccept;
const NOW = Date.UTC(2026, 10, 14, 12, 0, 0);
const writes = (name, mode) => Object.fromEntries(CMPS.find((c) => c.name === name).writes(mode, { now: NOW, id: 'id1' }).map((w) => [`${w.store}:${w.name}`, w.value]));

// Field layout of the IAB TCF v2 core segment, from the spec, independent of the encoder.
function decodeTc(s) {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/'));
  const bits = [...bin].map((c) => c.charCodeAt(0).toString(2).padStart(8, '0')).join('');
  let i = 0;
  const int = (n) => { const v = parseInt(bits.slice(i, i + n), 2); i += n; return v; };
  const flags = (n) => { const f = [...bits.slice(i, i + n)].map(Number); i += n; return f; };
  const out = { version: int(6), created: int(36), updated: int(36), cmpId: int(12), cmpVersion: int(12), screen: int(6) };
  out.language = String.fromCharCode(65 + int(6), 65 + int(6));
  Object.assign(out, { vendorList: int(12), policy: int(6), serviceSpecific: int(1), nonStandard: int(1) });
  out.specialFeatures = flags(12);
  out.purposes = flags(24);
  out.purposesLI = flags(24);
  out.purposeOne = int(1);
  out.country = String.fromCharCode(65 + int(6), 65 + int(6));
  out.maxVendor = int(16);
  out.vendors = new Set();
  if (int(1)) {
    for (let n = int(12); n > 0; n--) {
      if (int(1)) { const a = int(16), b = int(16); for (let v = a; v <= b; v++) out.vendors.add(v); } else out.vendors.add(int(16));
    }
  } else flags(out.maxVendor).forEach((f, k) => f && out.vendors.add(k + 1));
  return out;
}

test('the TC string says yes to every purpose and vendor for accept, and no to all for reject', () => {
  const yes = decodeTc(tcString({ accept: true, now: NOW, cmpId: 10 }));
  assert.equal(yes.version, 2);
  assert.equal(yes.cmpId, 10);
  assert.equal(yes.created, NOW / 100);
  assert.equal(yes.language, 'EN');
  assert.deepEqual(yes.purposes.slice(0, 11), Array(11).fill(1));
  assert.deepEqual(yes.specialFeatures.slice(0, 2), [1, 1]);
  assert.ok(yes.vendors.has(1) && yes.vendors.has(755) && yes.vendors.size === yes.maxVendor);
  const no = decodeTc(tcString({ accept: false, now: NOW, cmpId: 10 }));
  assert.ok(no.purposes.every((b) => b === 0) && no.specialFeatures.every((b) => b === 0));
  assert.equal(no.vendors.size, 0);
  assert.match(tcString({ accept: true, now: NOW, cmpId: 10 }), /^[A-Za-z0-9_-]+$/);
});

test('each CMP gets its own consent values, matching the mode', () => {
  assert.match(writes('onetrust', 'accept')['cookie:OptanonConsent'], /groups=C0001%3A1%2CC0002%3A1%2CC0003%3A1%2CC0004%3A1/);
  assert.match(writes('onetrust', 'reject')['cookie:OptanonConsent'], /groups=C0001%3A1%2CC0002%3A0%2CC0003%3A0%2CC0004%3A0/);
  assert.equal(writes('onetrust', 'accept')['cookie:OptanonAlertBoxClosed'], new Date(NOW).toISOString());
  assert.match(writes('cookiebot', 'accept')['cookie:CookieConsent'], /marketing:true/);
  assert.match(writes('cookiebot', 'reject')['cookie:CookieConsent'], /marketing:false.*method:%27explicit%27/);
  const didomi = JSON.parse(atob(writes('didomi', 'reject')['local:didomi_token']));
  assert.deepEqual(didomi.purposes.enabled, []);
  assert.ok(didomi.purposes.disabled.includes('cookies'));
  assert.equal(decodeTc(writes('quantcast', 'reject')['cookie:euconsent-v2']).vendors.size, 0);
  assert.equal(decodeTc(writes('quantcast', 'accept')['cookie:euconsent-v2']).purposes[0], 1);
  assert.equal(writes('usercentrics', 'accept')['local:uc_user_interaction'], 'true');
});

test('a CMP is recognised by its script host and subdomains, not by look-alikes', () => {
  assert.equal(cmpForHost('cdn.cookielaw.org').name, 'onetrust');
  assert.equal(cmpForHost('optanon.blob.core.windows.net').name, 'onetrust');
  assert.equal(cmpForHost('other.blob.core.windows.net'), null);
  assert.equal(cmpForHost('consent.cookiebot.com').name, 'cookiebot');
  assert.equal(cmpForHost('sdk.privacy-center.org').name, 'didomi');
  assert.equal(cmpForHost('web.cmp.usercentrics.eu').name, 'usercentrics');
  assert.equal(cmpForHost('cmp.inmobi.com').name, 'quantcast');
  assert.equal(cmpForHost('evilcookielaw.org'), null);
  assert.equal(cmpForHost('cookielaw.org.example.com'), null);
  assert.equal(cmpForHost(''), null);
});

test('consent cookies are first-party, site-wide and kept for a year', () => {
  assert.equal(cookieString({ name: 'a', value: 'b' }), 'a=b; path=/; max-age=31536000; SameSite=Lax');
});
