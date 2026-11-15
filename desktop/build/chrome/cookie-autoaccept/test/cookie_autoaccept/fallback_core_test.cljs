(ns cookie-autoaccept.fallback-core-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [cookie-autoaccept.fallback-core :as f]))

(defn- banner
  "consent-banner? of a banner with this text: an overlay with two buttons, 5% of the page's text, unless extra says
  otherwise."
  ([text] (banner text {}))
  ([text extra]
   (let [{:keys [overlay buttons fields pageShare]} (merge {:overlay true :buttons 2 :pageShare 0.05} extra)]
     (f/consent-banner? text overlay buttons fields pageShare))))

(defn- pick [labels] (f/pick-accept-button (clj->js labels)))

(deftest recognises-cookie-notices-in-several-languages
  (is (banner "We use cookies to improve your experience. Accept all / Manage"))
  (is (banner "Wir verwenden Cookies und brauchen Ihre Einwilligung. Alle akzeptieren"))
  (is (banner "Nous utilisons des cookies avec votre consentement. Accepter"))
  (is (banner "We value your privacy. We and our partners store and access personal data.")))

(deftest ignores-overlays-that-are-not-about-consent
  (is (not (banner "Subscribe to our newsletter for weekly deals and discounts!")))
  (is (not (banner "Sign in to continue. Password required. We use cookies.")))
  (is (not (banner "Read our privacy policy and terms of service before you buy."))))

(deftest ignores-a-dialog-that-asks-you-to-type-such-as-an-email-sign-up
  (is (not (banner "Join our mailing list. By clicking I agree you consent to receive our marketing emails about new products." {:fields 1})))
  (is (banner "We use cookies to improve your experience." {:fields 0})))

(deftest an-empty-full-viewport-layer-left-after-hiding-a-banner-is-its-backdrop-content-is-not
  (is (f/backdrop? 1 "" false))
  (is (f/backdrop? 0.95 "  " false))
  (is (not (f/backdrop? 1 "Welcome back! Here is your dashboard with today's numbers." false)))
  (is (not (f/backdrop? 1 "" true)) "a full-screen video or map")
  (is (not (f/backdrop? 0.3 "" false)) "a small empty box"))

(deftest needs-a-fixed-overlay-with-a-control
  (is (not (banner "We use cookies to improve your experience." {:overlay false})))
  (is (not (banner "We use cookies to improve your experience." {:buttons 0}))))

(deftest leaves-the-page-alone-when-the-overlay-is-the-page
  (is (not (banner (str "We use cookies. " (str/join (repeat 120 "Long article text. "))) {:pageShare 0.9})))
  (is (not (banner (str "cookies " (str/join (repeat 4000 "x")))))))

(deftest accepts-a-full-screen-consent-wall-with-a-long-text-as-on-lemonde
  (let [wall (str "Reject all cookies. Le Monde and its partners may use cookies and similar technologies. "
                  (str/join (repeat 190 "Details. ")))]
    (is (> (count wall) 1500))
    (is (banner wall {:pageShare 0.2}))))

(deftest picks-the-accept-all-button-over-a-plain-yes
  (is (= 2 (pick ["Deny" "Customize ›" "Allow all"])))
  (is (= 1 (pick ["OK" "Accept all cookies"])))
  (is (= 1 (pick ["Einstellungen" "Alle akzeptieren"])))
  (is (= 1 (pick ["Paramètres" "Tout accepter"])))
  (is (= 1 (pick ["Settings" "OK"])))
  (is (= 0 (pick ["Consent and continue ›" "Subscribe now ›" "Preferences ›"])))
  (is (= 1 (pick ["Configuración de Cookies" "Acepto y continúo gratis" "Rechazo y me suscribo"]))))

(deftest never-presses-buttons-that-pay-configure-or-refuse
  (is (= 1 (pick ["Reject all and subscribe for £5 per month" "Accept all"])))
  (is (= -1 (pick ["Accept all and subscribe" "Accept only necessary" "Manage settings"])))
  (is (= -1 (pick ["Reject all" "Decline" "Nur notwendige"])))
  (is (= -1 (pick ["Accetto e mi abbono" "Acepto y me suscribo"])))
  (is (= -1 (pick ["Learn more" "Privacy policy"]))))

(deftest accept-mode-clicks-reject-mode-and-button-less-banners-only-hide-frames-never-hide-themselves
  (is (= "click" (f/fallback-action "accept" 1 false)))
  (is (= "hide" (f/fallback-action "accept" -1 false)))
  (is (= "hide" (f/fallback-action "reject" 1 false)))
  (is (= "click" (f/fallback-action "accept" 0 true)))
  (is (= "none" (f/fallback-action "reject" 0 true))))
