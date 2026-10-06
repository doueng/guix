#!/bin/sh
# The binary's RUNPATH points at .guix-profile/lib, so dlopen'ed libraries
# (libwayland-client, libvulkan) and the Vulkan drivers come from the same
# profile at runtime without LD_LIBRARY_PATH leaking into launched programs.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
cd "$here"

guix package --profile="$here/.guix-profile" --manifest="$here/manifest.scm" >/dev/null

GUIX_PROFILE="$here/.guix-profile"
. "$GUIX_PROFILE/etc/profile"

export DUSK_PROFILE="$here/.guix-profile"
export RUSTFLAGS="-C link-arg=-Wl,-rpath,$here/.guix-profile/lib"
export SSL_CERT_DIR="$GUIX_PROFILE/etc/ssl/certs"
export CARGO_NET_GIT_FETCH_WITH_CLI=true

cargo build --release "$@"
