(ns ^:dev/always cookie-autoaccept.cmps
  "The CMPs the extension can answer before they run (the table is in cmps.edn) and the consent values each reads
  at start. Writes are only made where the value is absent, so a choice the user already made is never overwritten.
  Plain JavaScript arrays and objects, so the early content script carries no cljs.core data structures."
  (:require-macros [cookie-autoaccept.data :as data]))

(def ^:private year-s (* 365 24 3600))
;; IAB TCF v2 core string. Vendor list version and CMP ids are fixed guesses; a CMP that wants a newer list re-asks.
(def ^:private vendor-list-version 100)
(def ^:private max-vendor 1500)

(def cmps
  "Array of {name, hosts, prehide}."
  (data/cmp-table))

(defn tc-string
  "A TCF v2.2 TC string (core segment): every purpose, special feature and vendor yes for accept, all no for reject."
  [accept now cmp-id]
  (let [bits #js []
        put (fn [v n] (loop [i (dec n)] (when (>= i 0) (.push bits (mod (js/Math.floor (/ v (js/Math.pow 2 i))) 2)) (recur (dec i)))))
        flags (fn [n width] (dotimes [i width] (.push bits (if (and accept (< i n)) 1 0))))
        letters (fn [s] (dotimes [i (.-length s)] (put (- (.charCodeAt s i) 65) 6)))
        ds (js/Math.round (/ now 100))]
    (put 2 6)                                     ; version
    (put ds 36)                                   ; created
    (put ds 36)                                   ; last updated
    (put cmp-id 12)
    (put 1 12)                                    ; CMP version
    (put 1 6)                                     ; consent screen
    (letters "EN")
    (put vendor-list-version 12)
    (put 5 6)                                     ; TCF policy version (2.2)
    (put 1 1)                                     ; service specific
    (put 0 1)                                     ; non-standard texts
    (flags 2 12)                                  ; special feature opt-ins
    (flags 11 24)                                 ; purpose consents
    (put 0 24)                                    ; purpose legitimate interests
    (put 0 1)                                     ; purpose one treatment
    (letters "AA")                                ; publisher country, unknown
    (if accept
      (do (put max-vendor 16)
          (put 1 1)                               ; range encoding
          (put 1 12)                              ; one entry
          (put 1 1)                               ; a range
          (put 1 16)
          (put max-vendor 16))
      (do (put 0 16)
          (put 0 1)))
    (put 0 16)                                    ; vendor legitimate interests: none
    (put 0 1)
    (put 0 12)                                    ; publisher restrictions: none
    (while (pos? (mod (.-length bits) 8)) (.push bits 0))
    (let [bin (loop [i 0 out ""]
                (if (< i (.-length bits))
                  (recur (+ i 8) (str out (js/String.fromCharCode (js/parseInt (.join (.slice bits i (+ i 8)) "") 2))))
                  out))]
      (-> (js/btoa bin) (.replace (js/RegExp. "\\+" "g") "-") (.replace (js/RegExp. "/" "g") "_")
          (.replace (js/RegExp. "=+$") "")))))

(defn- cookie [name value] #js {:store "cookie" :name name :value value})
(defn- local [name value] #js {:store "local" :name name :value value})

(def ^:private didomi-purposes
  #js ["cookies" "select_basic_ads" "create_ads_profile" "select_personalized_ads" "measure_ad_performance"
       "market_research" "improve_products" "create_content_profile" "select_personalized_content"
       "measure_content_performance"])

(defn writes
  "The consent values the CMP named cmp-name reads at start, for mode (accept or reject): an array of
  {store: cookie|local, name, value}. now is the time in ms, id a fresh random id."
  [cmp-name mode now id]
  (let [on (= mode "accept")
        iso (.toISOString (js/Date. now))]
    (case cmp-name
      "onetrust"
      (let [v (if on 1 0)
            groups (str "C0001:1,C0002:" v ",C0003:" v ",C0004:" v ",C0005:" v)
            consent (.join #js ["isGpcEnabled=0" (str "datestamp=" (js/encodeURIComponent (.toUTCString (js/Date. now))))
                                "version=202501.1.0" "isIABGlobal=false" "hosts=" (str "consentId=" id)
                                "interactionCount=1" "isAnonUser=1" "landingPath=NotLandingPage"
                                (str "groups=" (js/encodeURIComponent groups)) "AwaitingReconsent=false"]
                           "&")]
        #js [(cookie "OptanonAlertBoxClosed" iso) (cookie "OptanonConsent" consent)])

      "cookiebot"
      #js [(cookie "CookieConsent"
                   (str "{stamp:%27" id "%27%2Cnecessary:true%2Cpreferences:" on "%2Cstatistics:" on "%2Cmarketing:" on
                        "%2Cmethod:%27explicit%27%2Cver:1%2Cutc:" now "%2Cregion:%27eu%27}"))]

      "didomi"
      (let [token (js/btoa (js/JSON.stringify
                            #js {:user_id id :created iso :updated iso :version 2
                                 :purposes (if on #js {:enabled didomi-purposes :disabled #js []}
                                               #js {:enabled #js [] :disabled didomi-purposes})
                                 :vendors (if on #js {:enabled #js ["c:all"] :disabled #js []}
                                              #js {:enabled #js [] :disabled #js ["c:all"]})}))]
        #js [(local "didomi_token" token) (cookie "didomi_token" token) (cookie "euconsent-v2" (tc-string on now 7))])

      "usercentrics"
      #js [(local "uc_user_interaction" "true") (local "euconsent-v2" (tc-string on now 5))]

      "quantcast"
      #js [(cookie "euconsent-v2" (tc-string on now 10)) (cookie "addtl_consent" "1~")]

      #js [])))

(defn ^js cmp-for-host
  "The CMP whose script is served from host (a hostname), or nil."
  [host]
  (let [h (.toLowerCase (str (or host "")))]
    (.find cmps (fn [^js c] (.some (.-hosts c) (fn [d] (or (= h d) (.endsWith h (str "." d)))))))))

(defn cookie-string
  "The cookie string to assign to document.cookie for one write."
  [^js w]
  (str (.-name w) "=" (.-value w) "; path=/; max-age=" year-s "; SameSite=Lax"))
