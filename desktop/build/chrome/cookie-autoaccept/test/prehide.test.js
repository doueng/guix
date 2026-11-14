import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { prehideSelectors, render } from '../scripts/build-prehide.mjs';

test('the generated prehide list is current with the vendored rules and the CMP table', () => {
  const file = readFileSync(new URL('../src/prehide-selectors.js', import.meta.url), 'utf8');
  assert.equal(file, render(prehideSelectors()), 'run node scripts/build-prehide.mjs');
});

test('the prehide list covers the major CMP roots and no page-wide element', () => {
  const ctx = vm.createContext({});
  vm.runInContext(readFileSync(new URL('../src/prehide-selectors.js', import.meta.url), 'utf8'), ctx);
  const list = ctx.cookieAutoaccept.prehideSelectors;
  for (const s of ['#onetrust-banner-sdk', '#CybotCookiebotDialog', '#didomi-host', '#qc-cmp2-container', '#usercentrics-root',
    "div[id^='sp_message_container_']", '#truste-consent-track']) assert.ok(list.includes(s), s);
  for (const s of ['html', 'body', 'main', '*', 'div', '#app', '#root']) assert.ok(!list.includes(s), s);
});
