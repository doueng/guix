#!/bin/sh
set -eu
cd "$(dirname "$0")"
./build.sh
npm ci --ignore-scripts
npm run check
npm run lint
npm test
bun test ../../../tests/pi-codemode.test.ts
node ../../pi/agent/skills/pstack/poteto-mode/scripts/check-pi-port.mjs
sh -n build.sh verify.sh
npm audit --audit-level=high
