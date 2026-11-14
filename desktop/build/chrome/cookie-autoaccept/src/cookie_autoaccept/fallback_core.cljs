(ns cookie-autoaccept.fallback-core
  "Generic consent-banner recognition. Input is plain values, so it needs no DOM.")

(def ^:private consent #"(?i)\b(cookies?|cookie-?richtlinie|consent|gdpr|dsgvo|rgpd|tracking|einwilligung|consentement|consenso|toestemming|samtycke|samtykke|datenschutz|vie privée|privacy)\b")
(def ^:private cookieish #"(?i)\b(cookies?|consent|einwilligung|consentement|consenso|toestemming|samtycke|samtykke|tracking|gdpr|dsgvo|rgpd)\b")
(def ^:private partners #"(?i)\bpartners?\b")
(def ^:private negative #"(?i)\b(password|passwort|sign in to|log in to|subscribe to our newsletter|are you (18|over 18)|age verification)\b")
(def ^:private min-text 20)
(def ^:private max-text 3000)

(defn- squash
  "String(x || '') with whitespace runs collapsed to one space."
  [x]
  (.replace (if (js/Boolean x) (js/String x) "") (js/RegExp. "\\s+" "g") " "))

(defn consent-banner?
  "Whether a banner looks like a cookie notice we may act on. overlay: a fixed/sticky/dialog root or a whole small
  frame; buttons: its clickable controls; fields: its visible text-entry fields; page-share: its share of the page's
  text, 0..1."
  [text overlay buttons fields page-share]
  (let [t (.trim (squash text))
        n (.-length t)]
    (and (js/Boolean overlay)
         (> buttons 0)
         ;; A cookie notice never asks you to type: a field means a sign-up, login or contact form.
         (not (> fields 0))
         (<= min-text n max-text)
         (or (.test cookieish t) (and (.test consent t) (.test partners t)))
         (not (.test negative t))
         ;; A long text that is most of the page's text is the page itself (say a fixed app shell), not a notice.
         (not (and (> page-share 0.6) (> n 1000))))))

;; Strong: accepts everything. Weak: a plain yes. Never: anything that costs money, configures, or says no.
(def ^:private strong #"(?i)^(accept|allow|agree( to)?|enable|alle|tout|todo|tutt[io]|alles|aceptar todo|accepter tout)\b.*\b(all|cookies|alle|tout|todo|tutti|alles)\b|^(alle (akzeptieren|annehmen|zulassen|erlauben)|tout accepter|accepter et fermer|aceptar todo|accetta tutto|alles accepteren|godkänn alla|acceptera alla|accepteer alles)\b")
(def ^:private weak #"(?i)^(accept|i accept|accepter|aceptar|acepto|accetta|accetto|akzeptieren|ich akzeptiere|annehmen|zustimmen|einverstanden|agree|i agree|allow|ok|okay|got it|i understand|understood|consent and continue|agree and (close|continue|proceed)|zustimmen und weiter|accepter et continuer|continue|yes,? i agree|j'accepte|ich stimme zu|godkänn|acceptera|akkoord)\b")
(def ^:private never #"(?i)(subscri|suscri|abonn|abbon|abo\b|pay|buy|£|€|\$|setting|einstellung|paramètre|manage|verwalten|gérer|customi[sz]e|anpassen|personnaliser|preference|präferenz|option|more info|mehr info|details|learn more|reject|decline|deny|refuse|ablehnen|refuser|rechazar|rifiuta|weiger|necessary|essential|notwendig|nécessaires|only|nur|without|ohne|sans)")

(defn pick-accept-button
  "Index of the button to press to accept cookies, or -1. labels: an array of the visible text of each control."
  [labels]
  (let [best #js [-1 0]]
    (.forEach labels
              (fn [raw i]
                (let [label (.trim (.replace (squash raw) (js/RegExp. "[›»>✓✔]" "g") ""))]
                  (when-not (or (identical? label "") (> (.-length label) 40) (.test never label))
                    (let [score (cond (.test strong label) 2 (.test weak label) 1 :else 0)]
                      (when (> score (aget best 1))
                        (aset best 0 i)
                        (aset best 1 score)))))))
    (aget best 0)))

(defn fallback-action
  "What the fallback does with a consent banner: click accept in accept mode when it can, else hide. Hiding a whole
  frame from inside does nothing useful, so in a subframe it only clicks."
  [mode accept-index in-frame]
  (cond (and (identical? mode "accept") (>= accept-index 0)) "click"
        in-frame "none"
        :else "hide"))

(defn backdrop?
  "Whether a fixed element left over the page after hiding a banner is that banner's backdrop: it covers nearly the
  whole viewport (coverage 0..1) and shows no text and no media of its own."
  [coverage text media]
  (and (>= coverage 0.9) (not media) (< (.-length (.trim (if (js/Boolean text) (js/String text) ""))) 20)))
