#!/bin/sh
set -eu
cd "$(dirname "$0")"
./build.sh
npm ci --ignore-scripts
npm run check
npm run lint
npm test
(cd ../bbh && bb test:workflow)
sh -n build.sh verify.sh
npm audit --audit-level=high
