# vimium-cljs

Vimium's keyboard navigation rewritten in ClojureScript as a Chrome Manifest V3 extension.
How to load it in the flatpak Chrome, what it covers and how it is tested: docs/guide.html

`ext/` is what Chrome loads, but its `ext/js/` is build output and not in the repo: run `./build.sh` after every
checkout or pull, or Load unpacked fails with "Could not load background script".

```sh
./build.sh            # npm install + shadow-cljs release into ext/js + load ext/ in headless Chrome (Java/node from guix)
npm run check-load    # just the load check: manifest files exist and Chrome accepts ext/
npm test              # cljs.test on node
npm run e2e           # headless flatpak Chrome over test/fixtures
npm run bombadil      # property run over the fixtures (Bombadil from the guix repo)
./check.sh            # tests + release builds + no-eval check + e2e
```

Layout: `src/vimium_cljs/{keys,commands,config,hints,exclusions,rank}.cljs` are pure and tested;
`bg/` is the service worker, `content/` the content script, `ui/` the Vomnibar, help and offscreen pages. `ext/` is the unpacked extension.
`node scripts/vomnibar-lab.mjs DIR [query ...]` screenshots the Vomnibar (light and dark) over a seeded profile;
`node scripts/vomnibar-keys.mjs` counts keystrokes for common Vomnibar tasks.

## Changing keys and settings

There is no options page. Key mappings, hint characters, search engines, exclusions and the other settings are
plain data in `src/vimium_cljs/config.cljs`. Edit it, run `./build.sh`, then click the reload arrow on the
extension's card at chrome://extensions. `npm test` checks the config is well formed.

## Ctrl+L

The manifest's `vomnibar` command opens the Vomnibar. Chrome won't auto-assign Ctrl+L to an extension because its
own address-bar shortcut uses it, so set it once at chrome://extensions/shortcuts. After that it works from
the page, the address bar and chrome:// pages, the last two through a popup Vomnibar.

## Tab list side panel

The `tab-panel` command opens the window's tabs in Chrome's side panel (`ext/panel.html`): click switches,
middle-click or × closes. It is meant for a Chrome with no tab strip or address bar (Hyprland's
`fullscreen_state = "0 2"` rule in the guix repo). Assign it a key at chrome://extensions/shortcuts, like Ctrl+L.

Logic ported from [Vimium](https://github.com/philc/vimium), MIT, Copyright (c) 2010 Phil Crosby, Ilya Sukhar
(ext/LICENSE.vimium.txt).
