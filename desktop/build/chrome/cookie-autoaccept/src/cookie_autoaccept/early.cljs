(ns ^:dev/always cookie-autoaccept.early
  "Runs at document_start in every frame of an enabled site (the worker registers it, leaving disabled sites out).
  1. Hides known consent banners before they paint: opacity 0, so autoconsent and the fallback still find and answer
     them. The worker sends prehideRelease once the page was checked; a banner root added after that is hidden
     again. Without a release, the hiding ends after hold-ms, so nothing that merely looks like a banner stays hidden.
  2. In the top frame, answers a known CMP before its script runs: when a script from a CMP host is added to the
     page, it writes the consent that CMP reads at start (cmps.cljs), unless the page already has a value.
  Interop only, so this script carries no cljs.core data structures."
  (:require [cookie-autoaccept.cmps :as cmps])
  (:require-macros [cookie-autoaccept.data :as data]))

(def ^:private hold-ms 10000)
(def ^:private watch-ms 30000)

(defn init []
  (let [mode (.-earlyMode ^js (.-cookieAutoaccept js/globalThis))
        top? (identical? js/window.top js/window)
        selectors (data/prehide-selectors)
        selector (.join selectors ",")
        style (js/document.createElement "style")
        root #(or js/document.head js/document.documentElement)
        hiding #js [false]
        hold-timer #js [0]
        released (js/WeakSet.)
        answered (js/Set.)
        matches #(try (js/document.querySelectorAll selector) (catch :default _ #js []))
        attach! #(when (and (not (.-isConnected style)) (root)) (.append (root) style))
        release (fn []
                  (when (aget hiding 0)
                    (aset hiding 0 false)
                    (js/clearTimeout (aget hold-timer 0))
                    (set! (.-media style) "not all")
                    (.forEach (matches) (fn [el] (.add released el)))))
        hide (fn []
               (when-not (aget hiding 0)
                 (aset hiding 0 true)
                 (set! (.-media style) "all")
                 (attach!)
                 (js/clearTimeout (aget hold-timer 0))
                 (aset hold-timer 0 (js/setTimeout release hold-ms))))
        ;; Top frame: CMP scripts seen as they are added, before they run.
        preanswer (fn [^js node]
                    (when (and (identical? (.-tagName node) "SCRIPT") (js/Boolean (.-src node)))
                      (when-let [host (try (.-hostname (js/URL. (.-src node) js/location.href)) (catch :default _ nil))]
                        (let [^js cmp (cmps/cmp-for-host host)]
                          (when (and cmp (not (.has answered (.-name cmp))))
                            (.add answered (.-name cmp))
                            (let [id (str (.toString (js/Date.now) 36) "-" (.slice (.toString (js/Math.random) 36) 2 10))
                                  wrote #js [0]]
                              (.forEach (cmps/writes (.-name cmp) mode (js/Date.now) id)
                                        (fn [^js w]
                                          (try
                                            (when (and (identical? (.-store w) "cookie")
                                                       (not (.some (.split js/document.cookie "; ")
                                                                   #(.startsWith % (str (.-name w) "=")))))
                                              (set! js/document.cookie (cmps/cookie-string w))
                                              (aset wrote 0 (inc (aget wrote 0))))
                                            (when (and (identical? (.-store w) "local") (nil? (js/localStorage.getItem (.-name w))))
                                              (js/localStorage.setItem (.-name w) (.-value w))
                                              (aset wrote 0 (inc (aget wrote 0))))
                                            ;; Storage can be blocked (sandboxed or cookie-less documents); the CMP then
                                            ;; asks and autoconsent answers.
                                            (catch :default _ nil))))
                              (when (pos? (aget wrote 0))
                                (-> (js/chrome.runtime.sendMessage #js {:type "preanswered" :cmp (.-name cmp)})
                                    (.catch (fn [_]))))))))))
        ;; After a release, a banner root in an added subtree hides again. The observer callback runs as a microtask
        ;; of the task that added it, so before any paint and before any animation-frame callback could see it. Only
        ;; the added subtrees are matched, not the whole document.
        new-banner? (fn [^js n]
                      (or (and (.matches n selector) (not (.has released n)))
                          (.some (js/Array.from (.querySelectorAll n selector)) #(not (.has released %)))))
        observer (js/MutationObserver.
                  (fn [records]
                    (attach!)
                    (.forEach records
                              (fn [^js r]
                                (.forEach (.-addedNodes r)
                                          (fn [^js n]
                                            (when (identical? 1 (.-nodeType n))
                                              (when top? (preanswer n))
                                              (when-not (aget hiding 0)
                                                ;; An unparsable selector in the joined list; the stylesheet still
                                                ;; drops only that rule.
                                                (try (when (new-banner? n) (hide)) (catch :default _ nil))))))))))]
    (set! (.-id style) "cookie-autoaccept-prehide")
    ;; One rule per selector: a selector this browser cannot parse then drops only its own rule.
    (set! (.-textContent style)
          (.join (.map selectors #(str % "{opacity:0!important;pointer-events:none!important}")) "\n"))
    (.observe observer js/document #js {:childList true :subtree true})
    (js/setTimeout #(.disconnect observer) watch-ms)
    (hide)
    (js/chrome.runtime.onMessage.addListener
     (fn [^js msg] (when (and msg (identical? (.-type msg) "prehideRelease")) (release))))
    ;; A subframe's own URL decides whether this script runs, but its top site's toggle is what counts.
    (when-not top?
      (-> (js/chrome.runtime.sendMessage #js {:type "earlyQuery"})
          (.then (fn [^js r]
                   (when (and r (not (.-enabled r)))
                     (release)
                     (.disconnect observer)
                     (.remove style)))
                 (fn [_]))))))
