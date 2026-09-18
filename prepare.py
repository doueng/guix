#!/usr/bin/env python3
"""Read-only disk checks and local configuration generation; never installs anything."""

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent
CURRENT_ESP = "5CDF-1DF4"
CURRENT_ESP_PARTUUID = "ea8adc5b-ec2d-4df1-913b-f0f05ff85367"
ESP_TYPE = "c12a7328-f81f-11d2-ba4b-00a0c93ec93b"
COLUMNS = "PATH,TYPE,SIZE,RO,TRAN,FSTYPE,LABEL,UUID,PARTUUID,PARTTYPE,MOUNTPOINTS,MODEL,SERIAL"


def run(*args):
    return subprocess.check_output(args, text=True)


def inventory():
    return json.loads(run("lsblk", "--json", "--tree", "--bytes", "--output", COLUMNS))["blockdevices"]


def flatten(nodes, parent=None):
    for node in nodes:
        yield node, parent
        yield from flatten(node.get("children", []), node)


def lookup(nodes, path):
    resolved = os.path.realpath(path)
    matches = [(n, p) for n, p in flatten(nodes) if n["path"] == resolved]
    if len(matches) != 1:
        raise ValueError(f"Not a unique block device: {path}")
    return matches[0]


def mounts(node):
    return [m for m in node.get("mountpoints", []) if m]


def external_disk(node):
    if node["type"] != "disk" or node.get("tran") != "usb":
        raise ValueError("Only a directly attached USB disk is supported; refusing internal/unknown transport")
    if node["path"].startswith("/dev/nvme") or node.get("ro"):
        raise ValueError("Refusing NVMe device path or read-only disk")


def check_blank_target(node):
    external_disk(node)
    if int(node["size"]) < 200 * 1024**3:
        raise ValueError("This two-partition plan requires at least 200 GiB; 500 GB+ recommended")
    for child, _ in flatten([node]):
        if mounts(child) or child.get("type") not in ("disk", "part"):
            raise ValueError(f"Device is mounted, swap, or has active holders: {child['path']}")
        if child.get("ro"):
            raise ValueError("Read-only child device")


def checked_pair(nodes, root_path, esp_path):
    root, root_disk = lookup(nodes, root_path)
    esp, esp_disk = lookup(nodes, esp_path)
    if root["type"] != "part" or root_disk is None:
        raise ValueError("Root must be a partition")
    external_disk(root_disk)
    if root.get("fstype") != "ext4" or root.get("ro") or root.get("label") != "guix-root":
        raise ValueError("Guix root must be writable ext4 labeled guix-root, NOT the bootstrap workspace")
    if esp["type"] != "part" or esp_disk is None or esp_disk.get("tran") != "nvme":
        raise ValueError("ESP must belong to a separately installed INTERNAL Asahi UEFI environment")
    if esp.get("fstype") != "vfat" or (esp.get("parttype") or "").lower() != ESP_TYPE or esp.get("ro"):
        raise ValueError("Expected a writable FAT EFI System Partition")
    for node in (root, esp):
        for field in ("uuid", "partuuid"):
            unique_identity(nodes, node, field)
        if any(m in ("/", "/boot", "/boot/efi", "/nix/store", "/home", "[SWAP]") for m in mounts(node)):
            raise ValueError("Refusing a live system filesystem")
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", root["uuid"]):
        raise ValueError("Invalid ext4 UUID")
    if not re.fullmatch(r"[0-9a-fA-F]{4}-[0-9a-fA-F]{4}", esp["uuid"]):
        raise ValueError("Invalid FAT UUID")
    if (esp["uuid"].upper() == CURRENT_ESP
            or esp["partuuid"].lower() == CURRENT_ESP_PARTUUID):
        raise ValueError("Refusing the known NixOS ESP")
    chosen = Path("/proc/device-tree/chosen/asahi,efi-system-partition")
    if chosen.exists():
        current = chosen.read_bytes().rstrip(b"\0").decode().lower()
        if current == (esp.get("partuuid") or "").lower():
            raise ValueError("Refusing the currently booted Asahi environment's ESP")
    return root, esp


def unique_identity(nodes, node, field):
    value = node.get(field)
    if not isinstance(value, str) or not value:
        raise ValueError(f"Missing {field}")
    if field == "partuuid" and not re.fullmatch(
            r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", value):
        raise ValueError("Invalid GPT PARTUUID")
    if sum((n.get(field) or "").lower() == value.lower() for n, _ in flatten(nodes)) != 1:
        raise ValueError(f"Duplicate {field}")


def disk_identity(disk):
    fields = ("type", "tran", "serial", "model", "size")
    if disk is None or disk.get("type") != "disk" or any(not disk.get(k) for k in fields):
        raise ValueError("Missing parent disk identity")
    return {k: disk[k] for k in fields}


def protected_esp(nodes):
    matches = [(n, parent) for n, parent in flatten(nodes)
               if (n.get("uuid") or "").upper() == CURRENT_ESP]
    if len(matches) != 1:
        raise ValueError("Missing or duplicate protected NixOS ESP")
    node, disk = matches[0]
    for field in ("uuid", "partuuid"):
        unique_identity(nodes, node, field)
    if (node["partuuid"].lower() != CURRENT_ESP_PARTUUID
            or node.get("type") != "part" or node.get("fstype") != "vfat"
            or (node.get("parttype") or "").lower() != ESP_TYPE
            or disk is None or disk.get("tran") != "nvme"
            or mounts(node) != ["/boot/efi"]):
        raise ValueError("Protected NixOS ESP identity or mount changed")
    return node, disk


def identity_record(nodes, root, esp):
    _, root_disk = lookup(nodes, root["path"])
    _, esp_disk = lookup(nodes, esp["path"])
    protected, protected_disk = protected_esp(nodes)
    return {"schema": 2, "root": root, "esp": esp, "protected_esp": protected,
            "root_disk": disk_identity(root_disk), "esp_disk": disk_identity(esp_disk),
            "protected_disk": disk_identity(protected_disk)}


def checked_saved_pair(nodes, saved):
    if not isinstance(saved, dict) or saved.get("schema") != 2:
        raise ValueError("Identity record needs regeneration and review (expected schema 2)")
    try:
        root, esp = checked_pair(nodes, "/dev/disk/by-uuid/" + saved["root"]["uuid"],
                                 "/dev/disk/by-uuid/" + saved["esp"]["uuid"])
        live = identity_record(nodes, root, esp)
        for role in ("root", "esp", "protected_esp"):
            for field in ("uuid", "partuuid", "fstype", "parttype"):
                before, now = saved[role].get(field), live[role].get(field)
                if not before or not now or before.lower() != now.lower():
                    raise ValueError(f"Saved {role} {field} no longer matches")
        for role in ("root_disk", "esp_disk", "protected_disk"):
            if saved[role] != live[role]:
                raise ValueError(f"Saved {role} identity no longer matches")
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError("Incomplete or malformed saved identity record") from error
    return root, esp, live["protected_esp"]


def plan(nodes, path):
    if not path.startswith("/dev/disk/by-id/"):
        raise ValueError("Use /dev/disk/by-id/... for the WHOLE SSD, not /dev/sdX")
    disk, _ = lookup(nodes, path)
    check_blank_target(disk)
    q = shlex.quote
    return f"""REVIEW ONLY — NOTHING HAS BEEN EXECUTED.
ALL DATA on {path} ({disk.get('model')}, serial {disk.get('serial')}, {disk['size']} bytes) WILL BE LOST.
Re-run this check immediately before executing commands. Confirm model, serial and size physically.
This plan does NOT create the required internal Asahi UEFI-only environment.
Do NOT format, repartition, or reuse the current internal ESP ({CURRENT_ESP}).

# Destructive commands to run yourself ONLY after backup and identity review:
SSD={q(path)}
sudo sfdisk --wipe always "$SSD" <<'PARTITIONS'
label: gpt
size=96GiB, type=L, name=guix-bootstrap
type=L, name=guix-root
PARTITIONS
sudo udevadm settle
sudo mkfs.ext4 -L guix-bootstrap "${{SSD}}-part1"
sudo mkfs.ext4 -O ^metadata_csum_seed -L guix-root "${{SSD}}-part2"

# Record actual UUIDs; never copy illustrative UUIDs from documentation:
lsblk -o PATH,SIZE,FSTYPE,LABEL,UUID,MOUNTPOINTS "$SSD"

The first partition is disposable build workspace; the second is the unencrypted Guix system.
Do not store secrets on this trial system. Neither partition is a backup of the other.
"""


def configure(nodes, root_path, esp_path, output):
    root, esp = checked_pair(nodes, root_path, esp_path)
    saved = identity_record(nodes, root, esp)
    output = Path(output).resolve()
    if output.exists():
        raise ValueError("Output already exists; choose a fresh local directory (never overwrite a configuration)")
    output.mkdir(parents=True, mode=0o700)
    shutil.copy2(HERE / "channels.scm", output / "channels.scm")
    shutil.copytree(HERE / "modules", output / "modules")
    config = f'''(use-modules (engstrand asahi) (guix channels))

(make-ssd-os
 #:root-uuid "{root['uuid']}"
 #:esp-uuid "{esp['uuid']}"
 #:channels (primitive-load
             (string-append (dirname (current-filename)) "/channels.scm")))
'''
    (output / "system.scm").write_text(config)
    (output / "devices.json").write_text(json.dumps(saved, indent=2) + "\n")
    return output


def mount_record(path):
    data = json.loads(run("findmnt", "--json", "--mountpoint", str(path),
                          "--output", "SOURCE,FSTYPE,UUID,TARGET,OPTIONS,FSROOT"))
    records = data.get("filesystems", [])
    if len(records) != 1:
        raise ValueError(f"Not an exact mountpoint: {path}")
    return records[0]


def verify_mount(record, expected, path, writable=True):
    if (record.get("target") != str(path) or record.get("fsroot") != "/"
            or os.path.realpath(record.get("source") or "") != expected["path"]):
        raise ValueError(f"Wrong source, filesystem root or target mounted at {path}")
    if (record.get("uuid") or "").lower() != expected["uuid"].lower():
        raise ValueError(f"Wrong UUID mounted at {path}")
    if (record.get("fstype") != expected["fstype"]
            or (writable and "rw" not in record.get("options", "").split(","))):
        raise ValueError(f"Wrong filesystem type or read-only mount at {path}")


def check_mounts(nodes, config_dir, target):
    target = Path(target)
    if not target.is_absolute() or target.resolve() != target or str(target) in ("/", "/boot", "/boot/efi"):
        raise ValueError("Use a dedicated absolute mountpoint, without symlinks or '..'")
    saved = json.loads((Path(config_dir) / "devices.json").read_text())
    root, esp, protected = checked_saved_pair(nodes, saved)
    verify_mount(mount_record("/boot/efi"), protected, "/boot/efi", writable=False)
    for node, path in ((root, target), (esp, target / "boot/efi")):
        verify_mount(mount_record(path), node, path)
        if mounts(node) != [str(path)]:
            raise ValueError(f"Device has unexpected/multiple mounts: {node['path']}")
    for name in ("vendorfw/firmware.cpio", "m1n1/boot.bin"):
        path = target / "boot/efi" / name
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"Missing {path}; complete firmware/UEFI preparation first")
    entries = run("cpio", "--list", "--quiet", "--file", str(target / "boot/efi/vendorfw/firmware.cpio"))
    if not any(line.removeprefix("./").startswith("vendorfw/") for line in entries.splitlines()):
        raise ValueError("Firmware CPIO does not have the channel's expected vendorfw/ layout")
    print("Saved UUIDs/PARTUUIDs, parent disks, mounts and firmware layout match.")
    print("This snapshot check is NOT installation approval, proof of ESP pairing, or a boot/build test.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("inspect")
    p = sub.add_parser("plan")
    p.add_argument("ssd")
    p = sub.add_parser("configure")
    p.add_argument("--root", required=True)
    p.add_argument("--esp", required=True)
    p.add_argument("--output", required=True)
    p = sub.add_parser("check-mounts")
    p.add_argument("--config-dir", required=True)
    p.add_argument("--target", default="/mnt/guix")
    args = parser.parse_args()
    try:
        nodes = inventory()
        if args.command == "inspect":
            print(json.dumps({"uname": list(os.uname()), "page_size": os.sysconf("SC_PAGE_SIZE"),
                              "devices": nodes, "known_nixos_esp_uuid": CURRENT_ESP}, indent=2))
        elif args.command == "plan":
            print(plan(nodes, args.ssd))
        elif args.command == "configure":
            print(configure(nodes, args.root, args.esp, args.output))
        else:
            check_mounts(nodes, args.config_dir, args.target)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"STOP: {error}\n")


if __name__ == "__main__":
    main()
