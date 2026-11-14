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

Layout: `src/vimium_cljs/{keys,commands,hints,exclusions,settings,rank}.cljs` are pure and tested;
`bg/` is the service worker, `content/` the content script, `ui/` the Vomnibar, help and offscreen pages,
`options/` the options page. `ext/` is the unpacked extension.

Logic ported from [Vimium](https://github.com/philc/vimium), MIT, Copyright (c) 2010 Phil Crosby, Ilya Sukhar
(ext/LICENSE.vimium.txt).
