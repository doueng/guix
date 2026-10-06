#!/bin/sh
# Unit and headless GPUI tests; never contact or alter the running shell.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
cd "$here"
GUIX_PROFILE="$here/.guix-profile"
if [ ! -r "$GUIX_PROFILE/etc/profile" ]; then
    echo 'dusk: build profile missing; run make dusk first' >&2
    exit 1
fi
. "$GUIX_PROFILE/etc/profile"
export DUSK_PROFILE="$GUIX_PROFILE"
export RUSTFLAGS="-C link-arg=-Wl,-rpath,$GUIX_PROFILE/lib"
export SSL_CERT_DIR="$GUIX_PROFILE/etc/ssl/certs"
export CARGO_NET_GIT_FETCH_WITH_CLI=true

bb --classpath "$here/runtime:$here/../../configs/dusk" "$here/test.bb"
cargo test --release --locked --features ui-tests "$@"
