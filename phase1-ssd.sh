#!/usr/bin/env bash
set -euo pipefail

SSD="${SSD:-/dev/disk/by-id/usb-Samsung_PSSD_T7_S7MLNL0L447791L-0:0}"
EXPECT_SERIAL="${EXPECT_SERIAL:-S7MLNL0L447791L}"
EXPECT_MODEL="${EXPECT_MODEL:-PSSD T7}"
KIT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL="$KIT/local"
MOUNT=/var/lib/guix-bootstrap

die() { echo "STOP: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run with sudo; partitioning, formatting and mounting require root"
[[ -n "${SUDO_USER:-}" ]] || die "run via sudo from your normal user account"
[[ -b "$SSD" || -L "$SSD" ]] || die "not a block device or symlink: $SSD"
command -v sfdisk >/dev/null || die "sfdisk not found"

python3 - "$SSD" "$EXPECT_SERIAL" "$EXPECT_MODEL" <<'PY'
import json, os, subprocess, sys
dev, expected_serial, expected_model = sys.argv[1:4]
data = json.loads(subprocess.check_output(
    ["lsblk", "--json", "--bytes",
     "-o", "PATH,TYPE,TRAN,SIZE,RO,MODEL,SERIAL,MOUNTPOINTS", dev]))
def flat(nodes):
    for node in nodes:
        yield node
        yield from flat(node.get("children", []))
nodes = list(flat(data["blockdevices"]))
matches = [n for n in nodes if n["path"] == os.path.realpath(dev)]
assert len(matches) == 1, "device is not unique"
disk = matches[0]
assert disk["type"] == "disk", "refusing: not a whole disk"
assert (disk.get("tran") or "").lower() == "usb", "refusing: not USB transport"
assert not disk.get("ro"), "refusing: read-only"
assert int(disk["size"]) >= 200 * 1024**3, "refusing: smaller than 200 GiB"
assert disk.get("serial") == expected_serial, f"serial mismatch: {disk.get('serial')!r}"
assert (disk.get("model") or "").strip() == expected_model, f"model mismatch: {disk.get('model')!r}"
for node in nodes:
    mounted = [m for m in node.get("mountpoints", []) if m]
    assert not mounted, f"refusing: mounted {node['path']} {mounted}"
    assert node["type"] in ("disk", "part"), f"refusing: active holder {node['path']} ({node['type']})"
print(f"approved {disk['path']} model={disk.get('model')!r} serial={disk.get('serial')!r} size={disk['size']} bytes")
PY

if [[ -n "$(ls -A /gnu 2>/dev/null)" || -n "$(ls -A /var/guix 2>/dev/null)" ]]; then
  die "existing /gnu or /var/guix content detected; refusing to hide it with bind mounts"
fi

python3 "$KIT/prepare.py" plan "$SSD"

echo
read -r -p "Type the SSD serial ($EXPECT_SERIAL) to erase it completely: " answer
[[ "$answer" == "$EXPECT_SERIAL" ]] || die "serial confirmation did not match"

python3 - "$SSD" <<'PYCHECK'
import json, subprocess, sys
dev = sys.argv[1]
data = json.loads(subprocess.check_output(
    ["lsblk", "--json", "--bytes", "-o", "PATH,LABEL,PARTLABEL", dev]))
def flat(nodes):
    for node in nodes:
        yield node
        yield from flat(node.get("children", []))
for node in flat(data["blockdevices"]):
    label = (node.get("label") or "").strip()
    partlabel = (node.get("partlabel") or "").strip()
    if label.startswith("guix-") or partlabel.startswith("guix-"):
        sys.exit(f"STOP: {node['path']} already looks prepared (label={label!r} partlabel={partlabel!r}); inspect before continuing")
print("no existing guix target partitions detected")
PYCHECK

echo "Partitioning $SSD ..."
sfdisk --wipe always "$SSD" <<'PARTITIONS'
label: gpt
size=96GiB, type=L, name=guix-bootstrap
type=L, name=guix-root
PARTITIONS
udevadm settle

mkfs.ext4 -F -L guix-bootstrap "${SSD}-part1" >/dev/null
mkfs.ext4 -F -O ^metadata_csum_seed -L guix-root "${SSD}-part2" >/dev/null

BOOTSTRAP_UUID="$(blkid -s UUID -o value "${SSD}-part1")"
ROOT_UUID="$(blkid -s UUID -o value "${SSD}-part2")"
[[ -n "$BOOTSTRAP_UUID" && -n "$ROOT_UUID" ]] || die "could not read new filesystem UUIDs"

mkdir -p "$MOUNT"
mount "/dev/disk/by-uuid/$BOOTSTRAP_UUID" "$MOUNT"
install -d -m 0755 "$MOUNT/gnu/store" "$MOUNT/var-guix"
install -d -m 1777 "$MOUNT/tmp"

install -d -m 0755 "$LOCAL"
cat > "$LOCAL/phase1.env" <<EOF
SSD=$SSD
BOOTSTRAP_UUID=$BOOTSTRAP_UUID
ROOT_UUID=$ROOT_UUID
EOF
chown "${SUDO_USER}:users" "$LOCAL/phase1.env" 2>/dev/null || true

echo
echo "Phase 1 complete."
lsblk -o PATH,SIZE,FSTYPE,LABEL,UUID "$SSD"
echo
echo "BOOTSTRAP_UUID=$BOOTSTRAP_UUID"
echo "ROOT_UUID=$ROOT_UUID"
echo "Wrote $LOCAL/phase1.env"
echo "Next: activate the NixOS guix-bootstrap module (Phase 2)."
