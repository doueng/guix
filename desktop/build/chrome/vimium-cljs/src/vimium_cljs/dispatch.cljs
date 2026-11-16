;; Which part of the extension a keydown goes to, decided from the frame's focus and mode.
;; Pure, so test.check drives it (test/vimium_cljs/dispatch_test.cljs).
;;
;; Google Docs draws the document itself and reads keys from a contenteditable in an iframe
;; nobody sees (docs-texteventtarget-iframe, 1px tall); Sheets from a contenteditable it
;; places at top -9998px (#waffle-rich-text-editor) until a cell is edited. Focus there is
;; always "typing" by the DOM's account, so, as SheetKeys does for Sheets' always-focused cell editor
;; (https://github.com/philc/sheetkeys, MIT, Copyright (c) Phil Crosby), the mode over such
;; an editor is the extension's own state: Escape suspends typing without blurring, and
;; i or a click resumes it. Docs refocusing its editor (after a menu, say) does not.
(ns vimium-cljs.dispatch
  (:require [clojure.string :as str]
            [vimium-cljs.keys :as keys]))

(def initial {:mode :normal :keys keys/initial :suspended false})

(def ^:private text-keys #{"Enter" "Backspace" "Delete" "Tab"})

(defn edits-text?
  "Whether the key in EV (a map of :key :ctrl :alt :meta) would change the text of an
  editor: a character, Enter, Backspace, Delete or Tab, with no Ctrl, Alt or Meta."
  [{:keys [key ctrl alt meta]}]
  (boolean (and (string? key) (not (or ctrl alt meta))
                (or (= 1 (count key)) (text-keys key)))))

(def ^:private resume-commands
  "Commands that, over a suspended editor, mean: type again."
  #{"enterInsertMode" "focusInput"})

(def ^:private own-handlers
  "Modes whose keys their own handler reads (hint keys, visual movements, a mark letter)."
  #{:hints :visual :marks-create :marks-goto :focus-input})

(def consumes
  "Effects that keep the key from the page."
  #{:swallow :suspend :resume :blur :pending :reset :command :exit})

(defn- normal [st] (assoc st :mode :normal :keys keys/initial))

(defn decide
  "Routes a keydown. CTX is {:trie :pass-keys :enabled}. ST is {:mode :keys :suspended}.
  FOCUS is :page (nothing editable has focus), :field (a visible editable), :catcher (an
  editable you cannot see, which a canvas editor reads keys from: Google Docs' 1px frame,
  Sheets' cell editor placed above the page) or :app-field (a visible editable of such an
  editor: a Sheets cell being edited, the formula bar, a Docs comment box). K is
  {:ks (keys/key-string) :esc? :text? (edits-text?)}. Returns {:state :effect},
  where :effect is a vector headed by
    :type    the key is text for the page; the extension does nothing
    :pass    the page gets the key
    :swallow nobody gets it
    :blur    Escape in a field: blur it
    :pass    also Escape in an app field: the app ends the edit and focuses its catcher again
    :suspend Escape over a catcher: stop typing, keep its focus
    :resume  type again in the catcher
    :pending :reset   keys/step swallowed a key of a longer mapping, or reset
    :command [_ entry count]   run a command
    :escape  Escape with nothing to reset: close what is open (hints, a frame, find)
    :exit    [_ mode]  Escape left MODE for normal mode; undo what MODE left behind
    :handler [_ mode]  MODE's own handler reads the key
    :pass-next [_ left]  passNextKey hands the page this key; LEFT more to go (0: back to normal)."
  [{:keys [trie pass-keys enabled] :or {pass-keys "" enabled true}} {:keys [mode suspended] :as st} focus {:keys [ks esc? text?]}]
  (let [suspended (and suspended (= focus :catcher))]
    (cond
      (= mode :pass-next)
      (if (nil? ks)
        {:state st :effect [:pass]}
        (let [left (dec (:left st 1))]
          {:state (if (pos? left) (assoc st :left left) (dissoc (normal st) :left)) :effect [:pass-next left]}))

      (and esc? (not= mode :normal)) {:state (cond-> (normal st) (= focus :catcher) (assoc :suspended true))
                                      :effect [:exit mode]}
      (own-handlers mode) {:state st :effect [:handler mode]}
      (#{:insert :find} mode) {:state st :effect [:pass]}
      (nil? ks) {:state st :effect [:pass]}

      (and (= focus :field) esc?) {:state (normal st) :effect [:blur]}
      (= focus :field) {:state st :effect [:type]}
      (and (= focus :app-field) esc?) {:state (normal st) :effect [:pass]}
      (= focus :app-field) {:state st :effect [:type]}
      (and (= focus :catcher) (not enabled)) {:state st :effect [:pass]}
      (and (= focus :catcher) (not suspended) esc?) {:state (assoc (normal st) :suspended true) :effect [:suspend]}
      (and (= focus :catcher) (not suspended)) {:state st :effect [:type]}
      (not enabled) {:state st :effect [:pass]}

      :else
      (let [{ks-state :state :keys [result] :as r} (keys/step trie (:keys st) ks esc? pass-keys)
            st (assoc st :keys ks-state)]
        (case result
          :command (if (and suspended (resume-commands (get-in r [:command :command])))
                     {:state (assoc st :suspended false) :effect [:resume]}
                     {:state st :effect [:command (:command r) (:count r)]})
          :pending {:state st :effect [:pending]}
          :reset {:state st :effect [:reset]}
          :pass (cond esc? {:state st :effect [:escape]}
                      ;; an exclusion's pass keys go to the page even here
                      (and suspended text? (not (and (seq pass-keys) (str/includes? pass-keys ks))))
                      {:state st :effect [:swallow]}
                      :else {:state st :effect [:pass]}))))))

(defn consume? [effect] (contains? consumes (first effect)))

(def ^:private key-frame-commands
  "Commands that act on the frame holding the keyboard, even when nobody can see it."
  #{"passNextKey" "enterInsertMode" "LinkHints.activateMode" "LinkHints.activateModeToOpenInNewTab"
    "LinkHints.activateModeToOpenInNewForegroundTab" "LinkHints.activateModeWithQueue"
    "LinkHints.activateModeToCopyLinkUrl"})

(defn command-frame
  "Where COMMAND (with INFO from commands/by-name) runs: :bg in the service worker, :top in
  the top frame, :here in this frame. A frame you cannot see (HIDDEN?) has nothing to scroll,
  find or mark, so its frame commands run in the top frame."
  [command info top? hidden?]
  (cond (:bg info) :bg
        top? :here
        (:top info) :top
        (and hidden? (not (key-frame-commands command))) :top
        :else :here))
