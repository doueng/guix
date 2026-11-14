import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const ctx = vm.createContext({});
vm.runInContext(readFileSync(new URL('../src/fallback-core.js', import.meta.url), 'utf8'), ctx);
const { isConsentBanner, pickAcceptButton, fallbackAction } = ctx.cookieAutoaccept;

const banner = (text, extra = {}) => ({ text, overlay: true, buttons: 2, pageShare: 0.05, ...extra });

test('recognises cookie notices in several languages', () => {
  assert.ok(isConsentBanner(banner('We use cookies to improve your experience. Accept all / Manage')));
  assert.ok(isConsentBanner(banner('Wir verwenden Cookies und brauchen Ihre Einwilligung. Alle akzeptieren')));
  assert.ok(isConsentBanner(banner('Nous utilisons des cookies avec votre consentement. Accepter')));
  assert.ok(isConsentBanner(banner('We value your privacy. We and our partners store and access personal data.')));
});

test('ignores overlays that are not about consent', () => {
  assert.ok(!isConsentBanner(banner('Subscribe to our newsletter for weekly deals and discounts!')));
  assert.ok(!isConsentBanner(banner('Sign in to continue. Password required. We use cookies.')));
  assert.ok(!isConsentBanner(banner('Read our privacy policy and terms of service before you buy.')));
});

test('needs a fixed overlay with a control', () => {
  assert.ok(!isConsentBanner(banner('We use cookies to improve your experience.', { overlay: false })));
  assert.ok(!isConsentBanner(banner('We use cookies to improve your experience.', { buttons: 0 })));
});

test('leaves the page alone when the overlay is the page', () => {
  const article = 'We use cookies. ' + 'Long article text. '.repeat(120);
  assert.ok(!isConsentBanner(banner(article, { pageShare: 0.9 })));
  assert.ok(!isConsentBanner(banner('cookies ' + 'x'.repeat(4000))));
});

test('accepts a full-screen consent wall with a long text, as on lemonde.fr', () => {
  const wall = 'Reject all cookies. Le Monde and its partners may use cookies and similar technologies. ' + 'Details. '.repeat(190);
  assert.ok(wall.length > 1500);
  assert.ok(isConsentBanner(banner(wall, { pageShare: 0.2 })));
});

test('picks the accept-all button over a plain yes', () => {
  assert.equal(pickAcceptButton(['Deny', 'Customize ›', 'Allow all']), 2);
  assert.equal(pickAcceptButton(['OK', 'Accept all cookies']), 1);
  assert.equal(pickAcceptButton(['Einstellungen', 'Alle akzeptieren']), 1);
  assert.equal(pickAcceptButton(['Paramètres', 'Tout accepter']), 1);
  assert.equal(pickAcceptButton(['Settings', 'OK']), 1);
  assert.equal(pickAcceptButton(['Consent and continue ›', 'Subscribe now ›', 'Preferences ›']), 0);
  assert.equal(pickAcceptButton(['Configuración de Cookies', 'Acepto y continúo gratis', 'Rechazo y me suscribo']), 1);
});

test('never presses buttons that pay, configure or refuse', () => {
  assert.equal(pickAcceptButton(['Reject all and subscribe for £5 per month', 'Accept all']), 1);
  assert.equal(pickAcceptButton(['Accept all and subscribe', 'Accept only necessary', 'Manage settings']), -1);
  assert.equal(pickAcceptButton(['Reject all', 'Decline', 'Nur notwendige']), -1);
  assert.equal(pickAcceptButton(['Accetto e mi abbono', 'Acepto y me suscribo']), -1);
  assert.equal(pickAcceptButton(['Learn more', 'Privacy policy']), -1);
});

test('accept mode clicks, reject mode and button-less banners only hide, frames never hide themselves', () => {
  assert.equal(fallbackAction({ mode: 'accept', acceptIndex: 1, inFrame: false }), 'click');
  assert.equal(fallbackAction({ mode: 'accept', acceptIndex: -1, inFrame: false }), 'hide');
  assert.equal(fallbackAction({ mode: 'reject', acceptIndex: 1, inFrame: false }), 'hide');
  assert.equal(fallbackAction({ mode: 'accept', acceptIndex: 0, inFrame: true }), 'click');
  assert.equal(fallbackAction({ mode: 'reject', acceptIndex: 0, inFrame: true }), 'none');
});
