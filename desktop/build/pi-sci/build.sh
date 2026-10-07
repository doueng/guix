#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p dist
clojure -M -m pi.prepare
clojure -J-Xmx2g -M:compile -m cljs.main -co compiler.edn -c pi.sci
mv dist/sci.pending.js dist/sci.js
