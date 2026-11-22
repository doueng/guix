# vimium-cljs

Vimium's keyboard navigation rewritten in ClojureScript as a Chrome Manifest V3 extension.
How to load it in the flatpak Chrome, what it covers and how it is tested: docs/guide.html

It lives in the guix repo at desktop/build/chrome/vimium-cljs (history imported from ~/projects/vimium-cljs).
`ext/` is what Chrome loads, but its `ext/js/` is build output and not in the repo: `ext-reload load vimium-cljs`
loads it once and `ext-reload vimium-cljs` rebuilds and reloads it after a change.

```sh
bb build              # npm ci + shadow-cljs release into ext/js + load ext/ in headless Chrome (Java from guix)
npm run check-load    # just the load check: manifest files exist and Chrome accepts ext/
npm test              # cljs.test on node
npm run e2e           # headless flatpak Chrome over test/fixtures
npm run bombadil      # property run over the fixtures (Bombadil from the guix repo)
bb test               # tests + release builds + no-eval check + load and guide checks (make lint runs it)
bb e2e                # the e2e suites (make verify ONLY=chrome-extensions runs them)
```

Layout: `src/vimium_cljs/{keys,commands,config,hints,exclusions,rank}.cljs` are pure and tested;
`bg/` is the service worker, `content/` the content script, `ui/` the Vomnibar, help and offscreen pages. `ext/` is the unpacked extension.
`node scripts/vomnibar-lab.mjs DIR [query ...]` screenshots the Vomnibar (light and dark) over a seeded profile;
`node scripts/vomnibar-keys.mjs` counts keystrokes for common Vomnibar tasks;
`node scripts/vomnibar-perf.mjs` times opening and each keystroke over 5000 history entries and 60 tabs;
`node scripts/vomnibar-live.mjs DIR` screenshots a headed Chrome (page, new tab, popup) while wtype types, all inside a throwaway `test-display`, so nothing reaches your screen.

## Changing keys and settings

There is no options page. Key mappings, hint characters, search engines, exclusions and the other settings are
plain data in `src/vimium_cljs/config.cljs`. Edit it, then run `ext-reload vimium-cljs`. `npm test` checks the config is well formed.

## Ctrl+L

The manifest's `vomnibar` command opens the Vomnibar; the pick opens in the current tab. Chrome won't give an
extension Ctrl+L because its own address-bar shortcut uses it, so the command's suggested key is Ctrl+Shift+H, and
on the guix desktop Hyprland sends Ctrl+Shift+H when Chrome gets Ctrl+L (other apps keep Ctrl+L). Nothing to set by
hand. It works from the page, the address bar and chrome:// pages, the last two through a popup Vomnibar that fills
its window. Elsewhere, assign Ctrl+L to it once at chrome://extensions/shortcuts.

## New tab

The extension replaces Chrome's new tab page with the Vomnibar (`ui.html?newtab`), focused, so `t` or Ctrl+T and
typing goes straight to a search. Chrome focuses the address bar on a new tab page, so the replacement opens
`ui.html?newtab` in its place and closes itself. `new-tab-url` in config.cljs still overrides what `t` opens.

## Incognito

The manifest asks for `"incognito": "split"`: incognito windows get their own worker and load the extension's pages
in incognito. Chrome never applies `chrome_url_overrides` there, so that worker swaps Chrome's incognito new tab page
for `ui.html?newtab`, and Ctrl+Shift+N then typing goes to the Vomnibar. Spanning mode can't do that: Chrome opens a
spanning extension's page in a normal window instead. Chrome only lets an unpacked extension into incognito after a
click on Allow in Incognito at chrome://extensions/?id=ceeiidnmpnfgpgccdchgcladeoldljem. No API, flag or policy sets
it, but Chrome keeps it for the extension's fixed id, so it is a one-time click; `ext-reload` prints it when it is off.
`npm run e2e:incognito` checks it headless, and `DISPLAY=:99 node scripts/incognito-live.mjs DIR` screenshots
Ctrl+Shift+N and typing on an Xvfb.

## Cmd+Space b b

The `vomnibar-tabs` command opens the Vomnibar on your open tabs, like Doom's SPC b b. Its suggested key
Ctrl+Shift+K is free in Chrome, so a fresh load assigns it; an already-loaded copy may need it set once at
chrome://extensions/shortcuts. The desktop leader in the guix repo (Hyprland grabs Cmd+Space before Chrome sees it)
sends Ctrl+Shift+K for b b when Chrome is focused. Works on chrome:// pages and from the address bar too, via the popup.

## Cmd+T

The `vomnibar-new-tab` command opens the Vomnibar in new-tab mode: the pick opens in a new tab, as Shift+Enter
does, and Escape opens nothing. Chrome gives no extension Ctrl+T, so on the guix desktop Hyprland sends this
command's key Ctrl+Shift+Y (suggested, so a fresh load assigns it) when Chrome gets Ctrl+T, which kanata makes
of Cmd+T. Works on chrome:// pages via the popup. On the new tab page the Vomnibar is already there, so the
command focuses it and the pick fills that empty tab.

## Tab list side panel

The `tab-panel` command opens the window's tabs in Chrome's side panel (`ext/panel.html`): click switches,
middle-click or × closes. It works next to Chrome's own tab strip, or alone in a Chrome that hides it.
On the guix desktop Alt+E toggles it; elsewhere assign it a key at chrome://extensions/shortcuts.

## Mode badge

A small badge in a bottom corner names the mode keys are in: NORMAL, INSERT (keys type text), HINTS, VISUAL,
VOMNIBAR, PASS KEY or MARK (the find bar stands in for FIND). It takes no clicks, keeps off whatever is clickable
where it can (bottom left, else bottom right) and fades while the pointer is near. `dispatch/indicator` derives it
from the mode, the focus and whether typing is suspended; the frame with the keyboard reports it, through the
worker when that is a frame nobody sees (Docs), and the top frame draws it. A click on the page closes the Vomnibar.

## Google Docs and Sheets

Docs reads keys from a contenteditable in a 1px iframe that always has focus; Sheets from a contenteditable cell editor
it keeps at top -9998px until a cell is edited. `src/vimium_cljs/dispatch.cljs` keeps vimium's own mode over such a
"key catcher": typing until Escape, normal mode (j scrolls the document or grid, f hints menus, the toolbar and sheet
tabs) until i or a click. In a visible field of these apps (a cell being edited, the formula bar) Escape is the app's:
it ends the edit and the next Escape gives normal mode. `test/fixtures/docs.html` and `sheets.html` reproduce the
structure; `npm run e2e:docs`, `npm run bombadil:docs` and `npm run bombadil:sheets` check them, and the badge with them. `npm run gdocs <url>` checks a Doc headless, and
`GDOC_URL=... GSHEET_URL=... node scripts/gdocs-live.mjs` runs a real Chrome window on the test display with real keys
and screenshots. Both type into the document, so give them a scratch Doc and Sheet of your own that anyone with the
link can edit, never someone else's.

Logic ported from [Vimium](https://github.com/philc/vimium), MIT, Copyright (c) 2010 Phil Crosby, Ilya Sukhar
(ext/LICENSE.vimium.txt). The mode kept over Docs' editor follows [SheetKeys](https://github.com/philc/sheetkeys)
(MIT, Copyright (c) Phil Crosby).
