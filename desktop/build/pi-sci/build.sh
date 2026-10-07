#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p dist
if [ ! -d node_modules/@earendil-works/pi-coding-agent ]; then
  npm ci --ignore-scripts
fi
node scripts/prepare-search.mjs
clojure -M -m pi.prepare
clojure -J-Xmx2g -M:compile -m cljs.main -co compiler.edn -c pi.sci
mv dist/sci.pending.js dist/sci.js
