#!/usr/bin/env bash
# Builds the loadable extension into ext/ (ext/js/ is build output and gitignored), then
# loads ext/ in the headless flatpak Chrome and fails with Chrome's own error if it would not
# load. Java and node come from guix, so this is the one command to run after a checkout or pull.
set -euo pipefail
cd "$(dirname "$0")"
guix shell openjdk@25:jdk node -- bash -c '
  [ -d node_modules ] || npm install --no-audit --no-fund
  npx shadow-cljs release bg content ui options
  node scripts/check-load.mjs' 2>&1 \
  | grep -v -E 'sun.misc|terminally deprecated|Please consider reporting|will be removed'
