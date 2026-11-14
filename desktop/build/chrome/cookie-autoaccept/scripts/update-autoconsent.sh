#!/usr/bin/env bash
# Re-vendor a pinned @duckduckgo/autoconsent release. The only step that uses the network; run it by hand.
#   scripts/update-autoconsent.sh 18.6.0
set -euo pipefail
version=${1:?usage: $0 VERSION}
repo=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
want=$(npm view "@duckduckgo/autoconsent@$version" dist.integrity)
npm pack --silent "@duckduckgo/autoconsent@$version" >/dev/null
tgz=$(ls duckduckgo-autoconsent-*.tgz)
got=sha512-$(node -e "process.stdout.write(require('crypto').createHash('sha512').update(require('fs').readFileSync('$tgz')).digest('base64'))")
[ "$want" = "$got" ] || { echo "integrity mismatch: $want vs $got" >&2; exit 1; }
mkdir x && tar xzf "$tgz" -C x
d="$repo/vendor/autoconsent"
cp x/package/dist/addon-mv3/content.bundle.js x/package/dist/autoconsent.esm.js x/package/rules/rules.json x/package/LICENSE "$d/"
{
  echo "@duckduckgo/autoconsent $version ($(node -p "require('./x/package/package.json').license"), see LICENSE in this directory)"
  echo "source: $(npm view "@duckduckgo/autoconsent@$version" dist.tarball)"
  echo "integrity: $got"
  echo "git: https://github.com/duckduckgo/autoconsent tag v$version"
  echo "files, unmodified:"
  echo "  content.bundle.js    <- package/dist/addon-mv3/content.bundle.js"
  echo "  autoconsent.esm.js   <- package/dist/autoconsent.esm.js (the service worker imports evalSnippets from it)"
  echo "  rules.json           <- package/rules/rules.json"
  echo "  LICENSE              <- package/LICENSE"
  echo "sha256:"
  (cd "$d" && sha256sum autoconsent.esm.js content.bundle.js LICENSE rules.json)
} > "$d/VERSION"
echo "vendored autoconsent $version; now run: npm test && npm run e2e"
