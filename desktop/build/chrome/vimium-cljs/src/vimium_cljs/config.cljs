;; Your settings, as plain data. There is no options page: edit this file, run ./build.sh,
;; then press the reload arrow on the extension's card at chrome://extensions.
;; The values started as the Vimium export in ~/Downloads/vimium-options.json; anything
;; it did not set keeps Vimium's default.
(ns vimium-cljs.config)

(def key-mappings
  "Applied in order over Vimium's default key map (commands/default-mappings-text).
  [key command] maps a key, [key command {option value}] passes options (as Vimium's
  `map j scrollDown count=3`), and [key nil] unmaps it. Keys are written as in
  Vimium: a, A, <c-o>, <a-f>, gg."
  [["i" "focusInput"]
   ["w" "previousTab"]
   ["e" "nextTab"]
   ["J" nil]
   ["K" nil]
   ["h" "goBack"]
   ["l" "goForward"]
   ["p" "Vomnibar.activateTabSelection"]
   ["<c-o>" "visitPreviousTab"]
   ["N" nil]])

(def link-hint-characters "sadfjklewcmpgh")

(def exclusions
  "A page whose URL matches :pattern (* is any text) gets no keys when :pass-keys is
  empty; otherwise only the keys in :pass-keys go to the page."
  [{:pattern "https?://go.dev/*" :pass-keys ""}
   {:pattern "https?://www.typelit.io/*" :pass-keys ""}])

(def search-url
  "Where text typed in the Vomnibar goes when it is not a URL or an engine keyword."
  "https://www.google.com/search?q=")

(def search-engines
  "Keyword -> engine for the Vomnibar: `w cats` searches Wikipedia. %s is the query."
  {"w" {:url "https://www.wikipedia.org/w/index.php?title=Special:Search&search=%s" :name "Wikipedia"}})

(def scroll-step "Pixels per j or k." 60)
(def smooth-scroll true)
(def hide-hud false)
(def grab-back-focus "Undo a page's autofocus until you click or type." true)
(def regex-find-mode false)
(def ignore-keyboard-layout false)
(def new-tab-url "What t opens; nil is Chrome's new tab page." nil)
(def link-hint-css "Extra CSS for the link-hint markers." "")

(def previous-patterns "prev,previous,back,older,<,\u2039,\u2190,\u00ab,\u226a,<<")
(def next-patterns "next,more,newer,>,\u203a,\u2192,\u00bb,\u226b,>>")
