;; Command table and default key mappings. The names, descriptions and
;; defaults follow Vimium 2.4 (background_scripts/all_commands.js and
;; commands.js, MIT, Copyright (c) Phil Crosby and Ilya Sukhar).
(ns vimium-cljs.commands)

(def groups
  [[:page "Navigating the page"]
   [:hints "Link hints"]
   [:vomnibar "Using the Vomnibar"]
   [:find "Using find"]
   [:history "Navigating history"]
   [:tabs "Manipulating tabs"]
   [:misc "Miscellaneous"]])

;; :bg      runs in the service worker
;; :once    takes no count (Vimium's noRepeat)
;; :top     runs only in the top frame
;; :options option names the command accepts besides count
(def table
  [["scrollDown" :page "Scroll down" {}]
   ["scrollUp" :page "Scroll up" {}]
   ["scrollToTop" :page "Scroll to the top of the page" {:once true}]
   ["scrollToBottom" :page "Scroll to the bottom of the page" {:once true}]
   ["scrollPageDown" :page "Scroll a half page down" {}]
   ["scrollPageUp" :page "Scroll a half page up" {}]
   ["scrollFullPageDown" :page "Scroll a full page down" {}]
   ["scrollFullPageUp" :page "Scroll a full page up" {}]
   ["scrollLeft" :page "Scroll left" {}]
   ["scrollRight" :page "Scroll right" {}]
   ["scrollToLeft" :page "Scroll all the way to the left" {:once true}]
   ["scrollToRight" :page "Scroll all the way to the right" {:once true}]
   ["reload" :page "Reload the page" {:bg true :once true :options #{"hard"}}]
   ["copyCurrentUrl" :page "Copy the current URL to the clipboard" {:once true}]
   ["openCopiedUrlInCurrentTab" :page "Open the clipboard's URL in the current tab" {:once true}]
   ["openCopiedUrlInNewTab" :page "Open the clipboard's URL in a new tab" {:once true}]
   ["goUp" :page "Go up the URL hierarchy" {}]
   ["goToRoot" :page "Go to the root of current URL hierarchy" {:once true}]
   ["enterInsertMode" :page "Enter insert mode" {:once true}]
   ["enterVisualMode" :page "Enter visual mode" {:once true}]
   ["enterVisualLineMode" :page "Enter visual line mode" {:once true}]
   ["passNextKey" :page "Pass the next key to the page" {}]
   ["focusInput" :page "Focus the first text input on the page" {}]
   ["goPrevious" :page "Follow the link labeled previous or <" {:once true}]
   ["goNext" :page "Follow the link labeled next or >" {:once true}]
   ["nextFrame" :page "Select the next frame on the page" {:bg true}]
   ["mainFrame" :page "Select the page's main/top frame" {:top true :once true}]
   ["Marks.activateCreateMode" :page "Create a new mark" {:once true}]
   ["Marks.activateGotoMode" :page "Jump to a mark" {:once true}]
   ["LinkHints.activateMode" :hints "Open a link in the current tab" {}]
   ["LinkHints.activateModeToOpenInNewTab" :hints "Open a link in a new tab" {}]
   ["LinkHints.activateModeToOpenInNewForegroundTab" :hints "Open a link in a new tab & switch to it" {}]
   ["LinkHints.activateModeWithQueue" :hints "Open multiple links in a new tab" {:once true}]
   ["LinkHints.activateModeToCopyLinkUrl" :hints "Copy a link URL to the clipboard" {}]
   ["Vomnibar.activate" :vomnibar "Open URL, bookmark or history entry" {:top true :once true}]
   ["Vomnibar.activateInNewTab" :vomnibar "Open URL, bookmark or history entry in a new tab" {:top true :once true}]
   ["Vomnibar.activateBookmarks" :vomnibar "Open a bookmark" {:top true :once true}]
   ["Vomnibar.activateBookmarksInNewTab" :vomnibar "Open a bookmark in a new tab" {:top true :once true}]
   ["Vomnibar.activateTabSelection" :vomnibar "Search through your open tabs" {:top true :once true}]
   ["Vomnibar.activateEditUrl" :vomnibar "Edit the current URL" {:top true :once true}]
   ["Vomnibar.activateEditUrlInNewTab" :vomnibar "Edit the current URL and open in a new tab" {:top true :once true}]
   ["enterFindMode" :find "Enter find mode" {:once true}]
   ["performFind" :find "Cycle forward to the next find match" {}]
   ["performBackwardsFind" :find "Cycle backward to the previous find match" {}]
   ["findSelected" :find "Find the selected text" {}]
   ["findSelectedBackwards" :find "Find the selected text, searching backwards" {}]
   ["goBack" :history "Go back in history" {}]
   ["goForward" :history "Go forward in history" {}]
   ["createTab" :tabs "Create new tab" {:bg true}]
   ["previousTab" :tabs "Go one tab left" {:bg true}]
   ["nextTab" :tabs "Go one tab right" {:bg true}]
   ["visitPreviousTab" :tabs "Go to previously-visited tab" {:bg true}]
   ["firstTab" :tabs "Go to the first tab" {:bg true :once true}]
   ["lastTab" :tabs "Go to the last tab" {:bg true :once true}]
   ["duplicateTab" :tabs "Duplicate current tab" {:bg true}]
   ["togglePinTab" :tabs "Pin or unpin current tab" {:bg true :once true}]
   ["toggleMuteTab" :tabs "Mute or unmute current tab" {:bg true :once true}]
   ["removeTab" :tabs "Close current tab" {:bg true}]
   ["restoreTab" :tabs "Restore closed tab" {:bg true}]
   ["moveTabToNewWindow" :tabs "Move tab to new window" {:bg true :once true}]
   ["closeTabsOnLeft" :tabs "Close tabs on the left" {:bg true}]
   ["closeTabsOnRight" :tabs "Close tabs on the right" {:bg true}]
   ["closeOtherTabs" :tabs "Close all other tabs" {:bg true :once true}]
   ["moveTabLeft" :tabs "Move tab to the left" {:bg true}]
   ["moveTabRight" :tabs "Move tab to the right" {:bg true}]
   ["zoomIn" :tabs "Zoom in" {:bg true}]
   ["zoomOut" :tabs "Zoom out" {:bg true}]
   ["zoomReset" :tabs "Reset zoom" {:bg true :once true}]
   ["toggleViewSource" :misc "View page source" {:once true}]
   ["showHelp" :misc "Show help" {:top true :once true}]])

(def by-name
  (into {} (map (fn [[n g d o]] [n (assoc o :name n :group g :desc d)])) table))

;; Vimium commands this port leaves out, with the reason shown on the options page.
(def left-out
  {"LinkHints.activateModeToDownloadLink" "needs the downloads permission"
   "LinkHints.activateModeToOpenIncognito" "incognito is not enabled for unpacked extensions by default"
   "Vomnibar.activateCommandSelection" "not ported yet"
   "setZoom" "not ported yet"})

(def default-mappings
  [["j" "scrollDown"] ["k" "scrollUp"] ["h" "scrollLeft"] ["l" "scrollRight"]
   ["gg" "scrollToTop"] ["G" "scrollToBottom"] ["zH" "scrollToLeft"] ["zL" "scrollToRight"]
   ["<c-e>" "scrollDown"] ["<c-y>" "scrollUp"] ["d" "scrollPageDown"] ["u" "scrollPageUp"]
   ["r" "reload"] ["R" "reload hard"] ["yy" "copyCurrentUrl"]
   ["p" "openCopiedUrlInCurrentTab"] ["P" "openCopiedUrlInNewTab"]
   ["gi" "focusInput"] ["[[" "goPrevious"] ["]]" "goNext"] ["gf" "nextFrame"] ["gF" "mainFrame"]
   ["gu" "goUp"] ["gU" "goToRoot"] ["i" "enterInsertMode"] ["v" "enterVisualMode"] ["V" "enterVisualLineMode"]
   ["f" "LinkHints.activateMode"] ["F" "LinkHints.activateModeToOpenInNewTab"]
   ["<a-f>" "LinkHints.activateModeWithQueue"] ["yf" "LinkHints.activateModeToCopyLinkUrl"]
   ["/" "enterFindMode"] ["n" "performFind"] ["N" "performBackwardsFind"]
   ["*" "findSelected"] ["#" "findSelectedBackwards"]
   ["o" "Vomnibar.activate"] ["O" "Vomnibar.activateInNewTab"] ["T" "Vomnibar.activateTabSelection"]
   ["b" "Vomnibar.activateBookmarks"] ["B" "Vomnibar.activateBookmarksInNewTab"]
   ["ge" "Vomnibar.activateEditUrl"] ["gE" "Vomnibar.activateEditUrlInNewTab"]
   ["H" "goBack"] ["L" "goForward"]
   ["K" "nextTab"] ["J" "previousTab"] ["gt" "nextTab"] ["gT" "previousTab"] ["^" "visitPreviousTab"]
   ["<<" "moveTabLeft"] [">>" "moveTabRight"] ["g0" "firstTab"] ["g$" "lastTab"]
   ["W" "moveTabToNewWindow"] ["t" "createTab"] ["yt" "duplicateTab"] ["x" "removeTab"] ["X" "restoreTab"]
   ["<a-p>" "togglePinTab"] ["<a-m>" "toggleMuteTab"] ["zi" "zoomIn"] ["zo" "zoomOut"] ["z0" "zoomReset"]
   ["m" "Marks.activateCreateMode"] ["`" "Marks.activateGotoMode"]
   ["?" "showHelp"] ["gs" "toggleViewSource"]])

(def default-mappings-text
  (apply str (map (fn [[k c]] (str "map " k " " c "\n")) default-mappings)))
