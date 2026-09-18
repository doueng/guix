#!/usr/bin/env bash
set -euo pipefail

stop() {
  printf 'STOP: %s\n' "$*" >&2
  exit 1
}

[[ $EUID -eq 0 ]] || stop 'Run with sudo bash transfer-desktop.sh.'
[[ $(findmnt -nro UUID /) == 72bd8b6f-0e7f-46f2-a181-6560211eabe7 ]] ||
  stop 'This transfer is only for the internal NixOS root.'
[[ $(readlink -f /run/current-system) == /nix/store/* ]] ||
  stop 'NixOS is not the running system.'

KIT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$KIT/local/familiar-desktop-os-prepare"
DEV=/dev/disk/by-uuid/c694c1fc-a241-459a-a60d-1c829f1b9c30
PART=/dev/disk/by-partuuid/0f2eb3d1-bd42-440d-aa93-a63ec8f79033
NAME=guix-desktop-os-prepare

[[ -f $SRC/system.scm && -f $SRC/sources.json ]] ||
  stop 'Snapshot incomplete; regenerate with desktop.py.'
for _ in $(seq 1 20); do
  A=$(readlink -f "$DEV" 2>/dev/null || true)
  B=$(readlink -f "$PART" 2>/dev/null || true)
  [[ -n $A && $A == "$B" ]] && break
  sleep 1
done
[[ $A == "$B" && -n $A ]] ||
  stop 'T7 root identity mismatch.'
if findmnt -rn -S "$DEV" >/dev/null; then
  stop 'T7 root already mounted; stop and inspect.'
fi

MNT=$(mktemp -d /mnt/guix-transfer.XXXXXX)
trap 'umount "$MNT" 2>/dev/null || true; rmdir "$MNT" 2>/dev/null || true' EXIT
mount -o nosuid,nodev,noexec "$DEV" "$MNT"
[[ $(findmnt -nro UUID --mountpoint "$MNT") == c694c1fc-a241-459a-a60d-1c829f1b9c30 ]] ||
  stop 'Unexpected filesystem at the mount.'
[[ ! -e $MNT/etc/$NAME && ! -L $MNT/etc/$NAME ]] ||
  stop "Destination /etc/$NAME already exists."

cp -a "$SRC" "$MNT/etc/$NAME"
chown -R root:root "$MNT/etc/$NAME"
chmod -R a+rX "$MNT/etc/$NAME"
diff -qr "$SRC" "$MNT/etc/$NAME"
sync
umount "$MNT"
rmdir "$MNT"
trap - EXIT
printf 'Transfer verified and unmounted: /etc/%s\n' "$NAME"
