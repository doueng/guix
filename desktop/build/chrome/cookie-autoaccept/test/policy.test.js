import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  autoconsentConfig,
  badgeFor,
  describeReport,
  emptyReport,
  fallbackAnswer,
  filterRules,
  isEnabledFor,
  normalizeSettings,
  reduceReport,
  setSiteEnabled,
  shouldRunFallback,
  siteKey,
  startReport,
} from '../src/policy.js';

test('default is accept, enabled everywhere', () => {
  const s = normalizeSettings(undefined);
  assert.equal(s.mode, 'accept');
  assert.equal(isEnabledFor(s, 'example.com'), true);
  assert.equal(autoconsentConfig(s, true).autoAction, 'optIn');
});

test('garbage settings are repaired rather than trusted', () => {
  assert.deepEqual(normalizeSettings({ mode: 'eat', disabledSites: ['a.com', 'a.com', 3, ''] }), {
    mode: 'accept',
    disabledSites: ['a.com'],
  });
});

test('reject mode opts out and lets the heuristic click reject buttons', () => {
  const c = autoconsentConfig({ mode: 'reject' }, true);
  assert.equal(c.autoAction, 'optOut');
  assert.notEqual(c.heuristicMode, 'off');
});

test('accept mode keeps the heuristic off, since upstream heuristic has no optIn', () => {
  assert.equal(autoconsentConfig({ mode: 'accept' }, true).heuristicMode, 'off');
});

test('site key strips www and ignores non-web pages', () => {
  assert.equal(siteKey('https://www.bbc.co.uk/news'), 'bbc.co.uk');
  assert.equal(siteKey('http://shop.example.com:8080/x'), 'shop.example.com');
  assert.equal(siteKey('chrome://extensions'), null);
  assert.equal(siteKey('not a url'), null);
});

test('per-site toggle turns one site off and back on', () => {
  let s = setSiteEnabled(undefined, 'news.com', false);
  assert.equal(isEnabledFor(s, 'news.com'), false);
  assert.equal(isEnabledFor(s, 'other.com'), true);
  assert.equal(autoconsentConfig(s, isEnabledFor(s, 'news.com')).enabled, false);
  s = setSiteEnabled(s, 'news.com', true);
  assert.equal(isEnabledFor(s, 'news.com'), true);
  assert.equal(setSiteEnabled(setSiteEnabled(s, 'x.com', false), 'x.com', false).disabledSites.length, 1);
});

const rules = [
  { name: 'generic' },
  { name: 'frame-only', runContext: { main: false, frame: true } },
  { name: 'both', runContext: { main: true, frame: true } },
  { name: 'site', runContext: { urlPattern: '^https://(www\\.)?shop\\.com/' } },
  { name: 'broken-pattern', runContext: { urlPattern: '(' } },
];

test('main frame gets generic and matching site rules, not frame-only ones', () => {
  const names = filterRules(rules, { url: 'https://shop.com/a', mainFrame: true }).map((r) => r.name);
  assert.deepEqual(names, ['generic', 'both', 'site']);
  const other = filterRules(rules, { url: 'https://elsewhere.org/', mainFrame: true }).map((r) => r.name);
  assert.deepEqual(other, ['generic', 'both']);
});

test('subframes get only rules that ask for frames', () => {
  const names = filterRules(rules, { url: 'https://cmp.example/', mainFrame: false }).map((r) => r.name);
  assert.deepEqual(names, ['frame-only', 'both']);
});

test('vendored rules cover the major CMPs, and filtering keeps the payload small', () => {
  const all = JSON.parse(readFileSync(new URL('../vendor/autoconsent/rules.json', import.meta.url))).autoconsent;
  const main = filterRules(all, { url: 'https://example.org/', mainFrame: true });
  const frame = filterRules(all, { url: 'https://example.org/', mainFrame: false });
  assert.ok(main.length < all.length && frame.length < main.length);
  assert.ok(JSON.stringify(frame).length < 200_000);
  const names = all.map((r) => r.name.toLowerCase()).join(' ');
  for (const cmp of ['didomi', 'quantcast', 'trustarc', 'usercentrics', 'sourcepoint']) assert.match(names, new RegExp(cmp));
  assert.ok(all.every((r) => r.optIn), 'full rules carry optIn steps');
});

test('a handled popup produces an accepted report and a badge', () => {
  let r = emptyReport('news.com', {});
  assert.equal(shouldRunFallback(r), true);
  r = reduceReport(r, { type: 'cmpDetected', cmp: 'onetrust' });
  r = reduceReport(r, { type: 'popupFound', cmp: 'onetrust' });
  assert.equal(shouldRunFallback(r), false);
  r = reduceReport(r, { type: 'optInResult', cmp: 'onetrust', result: true });
  r = reduceReport(r, { type: 'autoconsentDone', cmp: 'onetrust' });
  r = reduceReport(r, { type: 'fallbackScanned', afterResult: fallbackAnswer(r).afterResult });
  assert.equal(describeReport(r), 'Accepted cookies (onetrust).');
  assert.equal(badgeFor(r), '\u2713');
});

test('a failed click is reported as such, with no badge', () => {
  let r = reduceReport(emptyReport('a.com', { mode: 'reject' }), { type: 'popupFound', cmp: 'Didomi' });
  r = reduceReport(r, { type: 'optOutResult', cmp: 'Didomi', result: false });
  assert.equal(describeReport(r), 'Found Didomi but could not click it.');
  assert.equal(badgeFor(r), '');
});

test('the fallback is reported when it hid banners, and never runs on a disabled site', () => {
  let r = reduceReport(emptyReport('a.com', {}), { type: 'fallbackHidden', count: 2 });
  assert.equal(describeReport(r), 'Hid 2 cookie banners (generic).');
  assert.equal(badgeFor(r), '\u2713');
  const off = emptyReport('a.com', { disabledSites: ['a.com'] });
  assert.equal(shouldRunFallback(off), false);
  assert.equal(describeReport(off), 'Off for this site.');
  assert.equal(badgeFor(reduceReport(off, { type: 'fallbackHidden', count: 1 })), '');
});

test('the fallback waits while autoconsent works, then may still act on a banner a rule left up', () => {
  let r = reduceReport(emptyReport('cookiebot.com', {}), { type: 'popupFound', cmp: 'Cybotcookiebot' });
  assert.equal(shouldRunFallback(r), false);
  r = reduceReport(r, { type: 'optInResult', cmp: 'Cybotcookiebot', result: true });
  assert.equal(shouldRunFallback(r), true);
  r = reduceReport(r, { type: 'fallbackClicked', count: 1 });
  assert.equal(describeReport(r), 'Accepted cookies (generic button, after Cybotcookiebot).');
  assert.equal(badgeFor(r), '\u2713');
});

// Found by Bombadil: the popup said 'Accepted cookies (Cybotcookiebot).' while the banner was still up,
// because the rule reports success without clicking anything; the fallback only clicks seconds later.
test('a rule\'s claim is not reported until the page was scanned after it', () => {
  let r = reduceReport(emptyReport('shop.com', {}), { type: 'popupFound', cmp: 'Cybotcookiebot' });
  const before = fallbackAnswer(r);
  r = reduceReport(r, { type: 'optInResult', cmp: 'Cybotcookiebot', result: true });
  assert.equal(describeReport(r), 'Found Cybotcookiebot, checking...');
  assert.equal(badgeFor(r), '');
  // A scan that started before the result checks nothing.
  r = reduceReport(r, { type: 'fallbackScanned', afterResult: before.afterResult });
  assert.equal(describeReport(r), 'Found Cybotcookiebot, checking...');
  const after = fallbackAnswer(r);
  assert.equal(after.run, true);
  r = reduceReport(r, { type: 'fallbackScanned', afterResult: after.afterResult });
  assert.equal(describeReport(r), 'Accepted cookies (Cybotcookiebot).');
  assert.equal(badgeFor(r), '\u2713');
});

test('in reject mode, a banner hidden after a rule\'s claim is reported as hidden, not rejected', () => {
  let r = reduceReport(emptyReport('shop.com', { mode: 'reject' }), { type: 'popupFound', cmp: 'Cybotcookiebot' });
  r = reduceReport(r, { type: 'optOutResult', cmp: 'Cybotcookiebot', result: true });
  r = reduceReport(r, { type: 'fallbackHidden', count: 1 });
  r = reduceReport(r, { type: 'fallbackScanned', afterResult: true });
  assert.equal(describeReport(r), 'Hid 1 cookie banner (generic, after Cybotcookiebot).');
});

// Found by Bombadil: the Sourcepoint frame accepted before the main frame's init, which then wiped the
// report, so the popup said 'No cookie popup seen.'
test('a subframe\'s result survives the main frame\'s later init; a new page starts afresh', () => {
  const ctx = { site: 'news.com', settings: {} };
  let r = startReport(null, { type: 'pageStart', mainFrame: true, ...ctx });
  r = startReport(r, { type: 'init', mainFrame: false, ...ctx });
  r = reduceReport(r, { type: 'popupFound', cmp: 'Sourcepoint-frame' });
  r = reduceReport(r, { type: 'optInResult', cmp: 'Sourcepoint-frame', result: true });
  r = reduceReport(r, { type: 'fallbackScanned', afterResult: true });
  r = startReport(r, { type: 'init', mainFrame: true, ...ctx });
  assert.equal(describeReport(r), 'Accepted cookies (Sourcepoint-frame).');
  r = startReport(r, { type: 'pageStart', mainFrame: true, ...ctx });
  assert.equal(describeReport(r), 'No cookie popup seen.');
});

test('unknown messages leave the report unchanged', () => {
  const r = emptyReport('a.com', {});
  assert.equal(reduceReport(r, { type: 'report', state: {} }), r);
});
