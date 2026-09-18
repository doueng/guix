# Guix on the Samsung T7

Standalone configuration repository at `~/guix`, extracted from `~/nixos` revision `07cf158e`. Desktop staging assets are owned here under `desktop/shared/`; staging no longer reads the NixOS checkout. Ignored `local/` contains private installation snapshots and diagnostics, not portable source. NixOS bootstrap and AWS infrastructure remain in `~/nixos`.

See [REVIEW.md](REVIEW.md) for the extraction review and outstanding acceptance gates. The existing guarded transfer helper is now `transfer-desktop.sh`; older progress entries call it `rofl.sh`.

**Before running commands, review [INSTALLATION-PLAN.md](INSTALLATION-PLAN.md).** It details internal-disk and NixOS changes, backup requirements, and separate approval checkpoints. It takes precedence on operation order: verify bootstrap/build feasibility before committing to internal partition changes. This runbook is a command reference, not authorization to execute every step.

**Current state: [PROGRESS.md](PROGRESS.md).** Native Guix now boots with the UAS fix, and the user has set both passwords. Do not rerun formatting or initialization. Hardware acceptance remains incomplete. An opt-in [familiar desktop layer](desktop/README.md) is prepared separately; it must be built and reviewed before activation.

Prepared 2026-09-14 for this M1 MacBook Air. The inventory and validation notes below describe that initial preparation unless stated otherwise; use the checkpoint above for completed steps. This is not a hardware-tested image.

## Before buying / before starting

- Prefer a **500 GB or larger USB 3 SSD**, or NVMe SSD in a USB-compatible enclosure. A Thunderbolt-only enclosure is not this boot plan. Plug it directly into the laptop; avoid hubs/docks for first boot. Have power and a known-working network connection; a supported USB Ethernet adapter is useful if Wi-Fi fails.
- Apple firmware does **not** directly boot an arbitrary Linux SSD. The safe plan requires a **separate internal Asahi UEFI-only environment**, created through the Asahi installer from macOS, plus the SSD. The Apple boot picker selects that internal environment, not the external disk.
- Keep macOS, Apple recovery, and the existing NixOS environment. An external SSD does not eliminate this internal boot requirement. If macOS cannot provide space for a new UEFI environment, **stop**: do not repurpose existing APFS/EFI partitions or shrink Linux blindly.
- This first trial uses **unencrypted ext4**. Do not migrate secrets or your real home directory yet. Encryption, Hyprland/Noctalia, keyd, Pi/Herdr and full development-tool parity are separate follow-ups. The initial desktop is Sway/SDDM with Foot, Neovim, Git, and the channel's existing desktop packages (including a browser).
- The AWS builder work in `~/nixos/dendritic/features/guix-aws-builder/` is not deployed. This plan uses official/Asahi substitutes and a local sandboxed daemon; no cloud account or charges are required.

## Current machine and hard safety boundaries

Read-only inspection on 2026-09-14:

| Item | Observed value |
| --- | --- |
| Hardware / kernel | M1 MacBook Air, aarch64, Linux 7.1.10, 16 KiB pages |
| Internal disk | `/dev/nvme0n1`, Apple SSD, 233.8 GiB |
| NixOS root | Btrfs UUID `72bd8b6f-0e7f-46f2-a181-6560211eabe7`, partition 7 |
| Current ESP | FAT UUID **`5CDF-1DF4`**, partition 5, mounted `/boot/efi` |
| Other partitions | APFS 1–4 and 8; ext4 `BOOT` partition 6; ownership must not be guessed |
| Internal free space | About 34 GiB available; better than the earlier migration report, still not a build workspace |
| Privileged access | `sudo -n` requires a password; EFI contents and backup are **not yet verified** |
| Guix | Not installed/running system-wide; Nix's Guix executable is available in the local Nix store |

**Never give the current ESP to `guix system init`.** The channel's `m1n1-u-boot-grub-bootloader` writes both `m1n1/boot.bin` and removable-path GRUB into its target ESP. This would replace part of the current NixOS boot chain. The tools reject the known ESP and the currently booted Asahi ESP; they cannot establish the ownership of every old APFS container. Human review in macOS is mandatory.

Target layout:

```text
Internal Apple disk
  existing NixOS root and boot environment: retained, not reformatted
  macOS allocation: may require an installer-managed resize; approval required
  Apple recovery structures: retain existing ones; identify ownership first
  NEW Asahi UEFI-only stub + paired internal ESP: Guix m1n1/DTBs/U-Boot/GRUB + vendorfw
External USB SSD (all existing contents erased only after explicit review)
  partition 1: 96 GiB ext4 guix-bootstrap — temporary NixOS-hosted Guix store/state/builds
  partition 2: remaining space ext4 guix-root — native Guix /, /gnu/store, /home, /boot
```

The new bootloader reads `/boot/grub` and `/gnu/store` from the SSD. Keep the SSD attached before starting that boot environment. Do not put a generic ARM installer on it and expect Apple startup options to discover it.

## 1. Backup and create the independent boot environment

Back up your important data to **another device/location**, not another partition of the new SSD. Verify a restore and that you can reach macOS and startup options (shut down, hold the power button). Record the NixOS entry's name. Do not change the default startup disk yet.

Commands below are **manual instructions**, not actions already performed. Use Bash for the command blocks. From the repository root:

```sh
KIT="$PWD"
mkdir -p "$KIT/local"
python3 "$KIT/prepare.py" inspect > "$KIT/local/before.json"
```

With your own sudo authentication, save the current partition table and EFI contents to your private backup location. Substitute its real mounted path; first check that this backup location is not on the internal disk or target SSD:

```sh
BACKUP=/path/to/independent-private-backup/asahi-before-guix
umask 077
mkdir -p "$BACKUP"
sudo sfdisk --dump /dev/nvme0n1 > "$BACKUP/internal-partitions.sfdisk"
sudo tar -C /boot/efi -cpf "$BACKUP/nixos-esp.tar" .
cp "$KIT/local/before.json" "$BACKUP/"
sudo tar -tf "$BACKUP/nixos-esp.tar"
sha256sum "$BACKUP/nixos-esp.tar" > "$BACKUP/SHA256SUMS"
```

The ESP archive is not a complete Apple boot-policy/APFS backup. Never restore the partition dump wholesale as a routine recovery step. Firmware and machine-local backups stay private and out of public builders/caches.

Boot macOS and follow the [official Asahi installation instructions](https://asahilinux.org/docs/), selecting a **new UEFI-only environment** and a distinct name such as `Guix SSD`. Let the installer determine its required internal space and finish its recovery/boot-policy setup. Do not select an existing Linux environment to overwrite. Return to NixOS, rerun `prepare.py inspect`, and identify the **new paired ESP** by comparing layouts and the installer output. Do not infer it from a partition number in these notes.

The channel documents installing from Fedora Asahi Remix; this NixOS bootstrap is source-reviewed but **not yet installation-tested**. If firmware extraction or boot setup cannot be verified here, use the documented Fedora bootstrap/recovery route rather than forcing the procedure.

## 2. Review and format only the new SSD

```sh
ls -l /dev/disk/by-id/usb-*
python3 "$KIT/prepare.py" plan /dev/disk/by-id/usb-YOUR_WHOLE_SSD
```

`plan` prints commands; it executes **none** of them. It refuses non-USB/internal/too-small/read-only disks, mounted filesystems, active swap and visible holders. Check the physical SSD's model, serial and capacity, back up any factory/existing contents you need, then run the printed commands yourself. Partition paths use the same stable ID with `-part1` and `-part2`. Re-run the plan immediately before partitioning; checks do not prevent hotplug races. Do not use a hard-coded `/dev/sda` from elsewhere.

Record the two new ext4 UUIDs and the independently verified new internal ESP's FAT UUID. No command here formats that internal ESP; the Asahi installer owns its creation.

## 3. Prepare and deliberately enable the bootstrap daemon

This uses [the opt-in `guix-bootstrap` feature](../nixos/dendritic/features/guix-bootstrap/README.md). It is now selected and active on this host; skip initial setup when resuming the existing workspace. The commands below document first-time setup.

Before mounting anything over `/gnu` or `/var/guix`, verify neither contains an existing Guix installation. If it does, stop and plan a migration; do not hide its state. Substitute the actual partition-1 UUID:

```sh
BOOTSTRAP_UUID=ACTUAL_PARTITION_1_UUID
sudo mkdir -p /var/lib/guix-bootstrap
sudo mount "/dev/disk/by-uuid/$BOOTSTRAP_UUID" /var/lib/guix-bootstrap
findmnt --mountpoint /var/lib/guix-bootstrap
sudo install -d -m 0755 /var/lib/guix-bootstrap/gnu/store /var/lib/guix-bootstrap/var-guix
sudo install -d -m 1777 /var/lib/guix-bootstrap/tmp
```

Select the module and UUID in `~/nixos/dendritic/features/host-asahi-nix/feature.bb` as described in its README, then regenerate/evaluate/build NixOS. Review the entire NixOS change, including additional Asahi substitute-key trust and inherited profile/discovery integration. **You must separately authorize and perform NixOS activation**; it has not been run during preparation. A normal NixOS switch/boot deployment can update its existing ESP. The plan prefers a reviewed temporary test activation, subject to checking the actual hooks; this still changes the running host and is not a dry run. Do not use the generic Guix binary installer alongside NixOS's service ownership.

After activation, check:

```sh
findmnt --mountpoint /gnu
findmnt --mountpoint /var/guix
systemctl status guix-daemon.service guix-daemon.socket
systemctl show guix-daemon.service -p Environment -p RequiresMountsFor
guix build hello
```

Do not continue if mounts are missing or the small substitute/build test fails. The scratch partition is deliberately separate from native root: `guix system init` copies the built closure into a new store/database, avoiding two daemons sharing the same mutable store. Watch `df -h /var/lib/guix-bootstrap /` throughout. The 96 GiB workspace is a planning allowance, not a guaranteed kernel/desktop cold-build bound; stop for more capacity or a validated remote builder if needed.

## 4. Verify firmware, mount the destination, generate its configuration

Set actual UUIDs (all different); `ESP_UUID` must belong to the **new internal UEFI-only environment**, never `5CDF-1DF4`:

```sh
ROOT_UUID=ACTUAL_PARTITION_2_UUID
ESP_UUID=ACTUAL_NEW_INTERNAL_ESP_UUID
sudo mkdir -p /mnt/guix
sudo mount "/dev/disk/by-uuid/$ROOT_UUID" /mnt/guix
sudo mkdir -p /mnt/guix/boot/efi
sudo mount "/dev/disk/by-uuid/$ESP_UUID" /mnt/guix/boot/efi
findmnt -R /mnt/guix
```

The channel activates firmware from the **ESP selected by m1n1's device tree**, not simply from any directory named `/boot/efi`. This is another reason to use a separate paired boot environment. The new ESP needs `vendorfw/firmware.cpio` with `vendorfw/` entries, and the Asahi installer should already have supplied `m1n1/boot.bin`.

```sh
sudo ls -lh /boot/efi/vendorfw/firmware.cpio /mnt/guix/boot/efi/vendorfw/firmware.cpio
```

If the new ESP lacks `firmware.cpio` but the current one has a valid archive from **this same laptop**, copy only that firmware directory to the new ESP (do not replace its `asahi/`, `m1n1/` or EFI loader with NixOS's versions):

```sh
sudo mkdir -p /mnt/guix/boot/efi/vendorfw
sudo cp -a /boot/efi/vendorfw/. /mnt/guix/boot/efi/vendorfw/
```

If neither ESP has the archive, **stop here**. Follow the channel's [firmware extraction instructions](https://codeberg.org/asahi-guix/channel/src/commit/0a58b24a8448d75ec5570d28ebac2c88ebfbc540/README.org), using this machine's `asahi/all_firmware.tar.gz` and `asahi-fwextract` in a supported Asahi environment. Do not run an unreviewed firmware updater against the current NixOS ESP, and do not turn the repository's extracted firmware directory into a guessed CPIO layout. The available archives have not been inspected because sudo needs you.

```sh
python3 "$KIT/prepare.py" configure \
  --root "/dev/disk/by-uuid/$ROOT_UUID" \
  --esp "/dev/disk/by-uuid/$ESP_UUID" \
  --output "$KIT/local/install"
CFG="$KIT/local/install"
sudo python3 "$KIT/prepare.py" check-mounts --config-dir "$CFG" --target /mnt/guix
```

This creates a self-contained `system.scm`, pinned `channels.scm`, module tree and schema-2 identity snapshot; it will not overwrite an existing directory. Review `devices.json` (target/protected UUIDs and PARTUUIDs, parent disk identities) against the approved destinations, plus the UUIDs in `system.scm`. `check-mounts` rejects changed identities and old snapshots; it does not grant installation approval. If the helper reports that the target is the currently booted ESP, reboot through the original NixOS environment rather than bypassing it: NixOS can be running through the new environment while mounting its old ESP at `/boot/efi`. If interrupted, reuse the existing configuration or choose a new output directory—do not reformat to resume. Back up the **new** ESP as well before installing Guix's bootloader.

## 5. Authenticate, build, inspect, then install

Use the same pinned channels for every command. The first time-machine invocation authenticates the channels and may download/build Guix itself. Never disable authentication to work around a failure. Substitute signatures and channel Git signatures are separate trust mechanisms.

```sh
guix time-machine -C "$CFG/channels.scm" -- describe -f channels
guix time-machine -C "$CFG/channels.scm" -- \
  system build --system=aarch64-linux --dry-run -L "$CFG/modules" "$CFG/system.scm"
guix time-machine -C "$CFG/channels.scm" -- \
  system build --system=aarch64-linux -L "$CFG/modules" "$CFG/system.scm"
```

Stop if the build fails or needs more workspace. A successful Scheme evaluation is not this build. Do not use `--no-grafts` merely to match the experimental AWS protocol; this local installation uses normal Guix defaults.

Keep the complete configuration on the target before initialization. Recheck mounts immediately before the privileged command; **`system init` writes the new internal ESP as well as the external root**:

```sh
sudo mkdir -p /mnt/guix/etc/guix-ssd
sudo cp -a "$CFG"/. /mnt/guix/etc/guix-ssd/
sudo python3 "$KIT/prepare.py" check-mounts --config-dir "$CFG" --target /mnt/guix
GUIX=$(command -v guix)
sudo "$GUIX" time-machine -C "$CFG/channels.scm" -- \
  system init --system=aarch64-linux \
  -L /mnt/guix/etc/guix-ssd/modules /mnt/guix/etc/guix-ssd/system.scm /mnt/guix
```

Guix populates the target `/etc` and writes `/etc/shadow` on **first boot** from the activation script compiled into the system closure, not during `guix system init`. A `chroot … passwd` before reboot therefore cannot work: `/etc/passwd` does not exist yet, and the first boot would overwrite `/etc/shadow` with the compiled value anyway.

The module gives `root` Guix's documented empty initial password and leaves `engstrand` locked. Boot the `guix` environment, log in as `root` with an empty password, then set passwords interactively:

```sh
passwd root
passwd engstrand
passwd -S root
passwd -S engstrand
```

Both status lines must show a usable password, not locked (`L`) or empty (`NP`). This is not a Guix build input; keep hashes and `/etc/shadow` out of this checkout. If you prefer no empty-password window, set a pre-computed hash for `root` in the module and re-run `guix system init` before booting. No SSH service is enabled in the trial.

After successful installation and first-boot password setup, copy the public configuration and private backup to an independent location. Flush and unmount the target:

```sh
sync
sudo umount /mnt/guix/boot/efi
sudo umount /mnt/guix
```

Do not unplug the SSD while the bootstrap daemon uses partition 1. A normal shutdown unmounts it; leave the SSD attached for Guix boot.

## 6. First boot and acceptance

Shut down, hold the power button for startup options, choose the **new `Guix SSD` environment**, leave the SSD connected, select Guix in GRUB. Leave the known-good NixOS default unchanged until acceptance.

1. Log in as `engstrand`; choose Sway at SDDM. Default Sway uses Super+Enter for Foot. Use a text VT if the graphical session fails. This is US layout without the current keyd remaps; timezone is Europe/Amsterdam.
2. Connect with NetworkManager (`nmtui` / `nmcli --ask device wifi connect SSID`); avoid putting Wi-Fi secrets in history/configuration sources.
3. Check `uname -a`, `getconf PAGESIZE` (16384), `findmnt /`, `findmnt /boot/efi`; ensure root is the USB SSD and ESP is the new internal one.
4. Run `guix time-machine -C /etc/guix-ssd/channels.scm -- asahi doctor --verbose`. Verify accelerated rendering rather than llvmpipe with `glxinfo -B` where available; check Wayland/native browser behavior as well.
5. **Before speaker playback**, verify `sudo herd status speakersafetyd`, `sudo herd status rtkit-daemon`, and user `herd status pipewire`, `herd status wireplumber`. The system includes the channel's speaker protection and Asahi-specific PipeWire Home services for **engstrand**, not its default guest. If any protection or routing is missing, leave speakers unused; never test with direct ALSA/speaker-test bypasses. Start with headphones.
6. Test keyboard/touchpad, brightness, Wi-Fi, Bluetooth, battery/charging, microphone/webcam, reboot, and suspend/resume with the SSD attached. A USB-root system must survive resume without disconnect/I/O errors; a successful first boot is insufficient.
7. Test Sway lock (`swaylock`), browser, portals and the specific external devices you use before carrying any sensitive data. Automatic locking and full desktop parity are not established by this kit.
8. Shut down and prove **NixOS boots with the SSD disconnected**. Then reconnect and boot Guix again. Do not retire recovery generations or the bootstrap partition until this succeeds.

Keep channels pinned when reconfiguring natively:

```sh
sudo guix time-machine -C /etc/guix-ssd/channels.scm -- \
  system reconfigure -L /etc/guix-ssd/modules /etc/guix-ssd/system.scm
```

Guix generations roll back system closures, not every m1n1/DTB/ESP update. On failed Guix boot, choose the existing NixOS environment from Apple startup options, mount only the Guix root/new ESP, inspect logs and its backups. Do not repair it by overwriting the NixOS ESP. Use the Asahi recovery documentation if the new environment's boot chain needs restoring.

## Validation and remaining gates

Offline checks from the repository root:

```sh
make test   # or: python3 -m unittest discover -s tests -v
make eval   # or the guix repl invocation below
```

```sh
python3 -m unittest discover -s tests -v
ASAHI="$HOME/.cache/checkouts/codeberg.org/asahi-guix/channel"
guix repl -L "$ASAHI/modules" -L modules tests/evaluate.scm channels.scm
```

Completed during preparation: all 12 Python safety tests; live read-only inventory with all eight internal partitions correctly nested; generated `system.scm` evaluation; the Scheme service-graph test; `make fmt`, `make bb-test`, `make bb-check`, and `make eval`. A separate `extendModules` evaluation of the opt-in bootstrap feature also passed all NixOS assertions and verified standard store/state paths, SSD temporary storage, substitute URLs, and daemon mount dependencies. That initial disabled-host observation is historical; consult PROGRESS.md for subsequent activation and installation.

Direct `guix git authenticate` was also attempted on both librarian checkouts. Both stopped with `Git error: object not found` because these caches use `partialclonefilter=blob:none`; Guix's libgit2 path could not read missing historical objects. This is **not** a successful authentication, nor evidence of a bad signature. Use Guix's normal fresh authenticated time-machine checkout (step 5), or a complete source checkout if doing independent verification; do not pass the partial source caches as an authentication workaround.

The Scheme test uses Nix's available Guix runtime plus the cached Asahi source at the pinned commit. It checks records/service composition, locked accounts, no guest/SSH, channel pins and audio ownership **without a daemon**. It is not a complete pinned time-machine build or a signature-authentication test. The source cache is a validation input, not required by the self-contained install configuration.

Historical preparation gates (subsequently progressed; see PROGRESS.md): independent backups; new UEFI environment and pairing; firmware inspection; actual partition UUIDs; explicit NixOS bootstrap activation; channel authentication/time-machine realization; complete system build/init; password initialization; actual USB-root boot and all hardware/recovery tests. If these do not pass, stop at that gate instead of treating this document as proof of compatibility.

Sources: [Asahi Guix pinned source](https://codeberg.org/asahi-guix/channel/src/commit/0a58b24a8448d75ec5570d28ebac2c88ebfbc540), especially `systems/{base,desktop,sway}.scm`, `initrd.scm`, `build/firmware.scm`, `build/bootloader/m1n1.scm`; [Asahi U-Boot boot flow](https://asahilinux.org/docs/sw/u-boot/); [partitioning safety](https://asahilinux.org/docs/sw/partitioning-cheatsheet/); [Guix manual](https://guix.gnu.org/manual/devel/en/html_node/System-Installation.html). See also the earlier [migration assessment](../nixos/dendritic/features/host-asahi-nix/GUIX-MIGRATION.md).
