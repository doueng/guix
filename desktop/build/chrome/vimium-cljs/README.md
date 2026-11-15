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
bb e2e                # the e2e suites (make chrome-e2e runs them)
```

Layout: `src/vimium_cljs/{keys,commands,config,hints,exclusions,rank}.cljs` are pure and tested;
`bg/` is the service worker, `content/` the content script, `ui/` the Vomnibar, help and offscreen pages. `ext/` is the unpacked extension.
`node scripts/vomnibar-lab.mjs DIR [query ...]` screenshots the Vomnibar (light and dark) over a seeded profile;
`node scripts/vomnibar-keys.mjs` counts keystrokes for common Vomnibar tasks;
`node scripts/vomnibar-perf.mjs` times opening and each keystroke over 5000 history entries and 60 tabs;
`DISPLAY=:99 node scripts/vomnibar-live.mjs DIR` screenshots a headed Chrome on an Xvfb (page, new tab, popup) while xdotool types.

## Changing keys and settings

There is no options page. Key mappings, hint characters, search engines, exclusions and the other settings are
plain data in `src/vimium_cljs/config.cljs`. Edit it, then run `ext-reload vimium-cljs`. `npm test` checks the config is well formed.

## Ctrl+L

The manifest's `vomnibar` command opens the Vomnibar. Chrome won't auto-assign Ctrl+L to an extension because its
own address-bar shortcut uses it, so set it once at chrome://extensions/shortcuts. After that it works from
the page, the address bar and chrome:// pages, the last two through a popup Vomnibar that fills its window.

## New tab

The extension replaces Chrome's new tab page with the Vomnibar (`ui.html?newtab`), focused, so `t` or Ctrl+T and
typing goes straight to a search. Chrome focuses the address bar on a new tab page, so the replacement opens
`ui.html?newtab` in its place and closes itself. `new-tab-url` in config.cljs still overrides what `t` opens.

## Cmd+Space b b

The `vomnibar-tabs` command opens the Vomnibar on your open tabs, like Doom's SPC b b. Its suggested key
Ctrl+Shift+K is free in Chrome, so a fresh load assigns it; an already-loaded copy may need it set once at
chrome://extensions/shortcuts. The desktop leader in the guix repo (Hyprland grabs Cmd+Space before Chrome sees it)
sends Ctrl+Shift+K for b b when Chrome is focused. Works on chrome:// pages and from the address bar too, via the popup.

## Tab list side panel

The `tab-panel` command opens the window's tabs in Chrome's side panel (`ext/panel.html`): click switches,
middle-click or × closes. It is meant for a Chrome with no tab strip or address bar (Hyprland's
`fullscreen_state = "0 2"` rule in the guix repo). Assign it a key at chrome://extensions/shortcuts, like Ctrl+L.

Logic ported from [Vimium](https://github.com/philc/vimium), MIT, Copyright (c) 2010 Phil Crosby, Ilya Sukhar
(ext/LICENSE.vimium.txt).
