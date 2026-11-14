#!/usr/bin/env bash
# Unit tests, release builds (warnings are errors), a check that no bundle uses eval,
# then the headless-Chrome e2e run. Java comes from guix.
set -euo pipefail
cd "$(dirname "$0")"
[ -d node_modules ] || npm install
guix shell openjdk@25:jdk node -- bash -c 'npx shadow-cljs compile test && node out/test.js && npx shadow-cljs release bg content ui options' 2>&1 \
  | grep -v -E 'sun.misc|terminally deprecated|Please consider reporting|will be removed'
if grep -l -E '(^|[^.a-zA-Z_$])eval\(|new Function\(' ext/js/*.js; then echo 'eval in a bundle' >&2; exit 1; fi
ls -l ext/js/*.js | awk '{print $5, $9}'
node scripts/check-load.mjs
node scripts/check-guide.mjs
node --test --test-concurrency=1 test/e2e/run.mjs
