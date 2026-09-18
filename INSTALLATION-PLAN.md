# Plan: Guix System on the Samsung T7, retaining NixOS and macOS

**Status: bootstrap phase completed; native installation pending.** See [PROGRESS.md](PROGRESS.md) for the live checkpoint. The T7 is formatted, the NixOS bootstrap is active, and pinned kernel/desktop/bootloader packages have been realized. Do not repeat formatting. Internal-environment creation and final installation still require their explicit approvals.

The proposal and preparation observations below are retained as the original scope/approval record; completed-step status is maintained in the checkpoint rather than inferred from these historical descriptions.

This document describes the intended changes, not an installation already performed. It controls scope and approval checkpoints; [README.md](README.md) provides the lower-level installation commands. Do not execute the runbook end-to-end without completing these checkpoints.

## 1. Goal and current authorization

Install an independent Guix System on the external Samsung T7 and boot it on this M1 MacBook Air. Keep NixOS and macOS available as recovery/daily-use systems while evaluating Guix.

Already authorized:

- Completely erase the Samsung T7 for this installation.

Not yet authorized:

- Resizing macOS or otherwise modifying the internal partition layout.
- Creating an Asahi boot environment or changing Apple boot policy.
- Activating the Guix bootstrap service on NixOS.
- Installing Guix's bootloader into an internal EFI partition.
- Changing the default startup OS.

Reported backups: `/boot/efi/vendorfw/firmware.cpio` and `/boot/efi/asahi/all_firmware.tar.gz`. Their backup destination and recoverability still need confirmation.

During preparation, repository files and validation artifacts were created, sources were fetched, and read-only checks were run. **No disk was formatted, no Guix daemon was activated, and no installed bootloader was changed by those steps.** The T7 has not yet been erased.

## 2. Why this is not an SSD-only installation

The prepared boot architecture is:

```text
Apple startup options
  → NEW internal Asahi boot environment
  → m1n1 + device trees + U-Boot
  → GRUB on that environment's internal EFI partition
  → Guix kernel/initrd and root filesystem on the T7
```

Apple's native startup tooling does not directly boot an arbitrary Linux USB installation. Asahi supplies the intermediate boot environment.

**A separate internal environment is the chosen isolation strategy, not a claim that every possible external Linux boot arrangement needs a second Asahi installation.** Reusing the existing U-Boot to launch external media is another design to investigate, but is not the prepared or validated route here. It would need separate review of boot selection, kernel/device-tree compatibility, firmware discovery and future bootloader updates.

The advantage of the proposed separate environment is that Guix can own its m1n1/GRUB stack without replacing the stack used to boot NixOS. The disadvantage is that it requires internal disk space and Apple boot-policy setup.

The T7 will not be a self-contained, universally bootable drive: this laptop's internal boot environment remains a dependency.

## 3. Disk layout and exact intended changes

### Existing internal Apple SSD

Last inspected layout, for identification only—not instructions to manipulate these partition numbers:

| Existing area | Observed identity | Intended treatment |
| --- | --- | --- |
| Apple APFS partitions | `nvme0n1p1`–`p4`, `p8` | Establish ownership from macOS before any operation. Never infer that an old-looking container is disposable. |
| Current Linux ESP | `nvme0n1p5`, FAT UUID `5CDF-1DF4`, mounted `/boot/efi` | Retain for NixOS. Never select as the Guix bootloader target. |
| Existing Linux boot partition | `nvme0n1p6`, ext4 label `BOOT` | Retain; do not assume unused. |
| NixOS root | `nvme0n1p7`, Btrfs UUID `72bd8b6f-0e7f-46f2-a181-6560211eabe7` | Do not format, shrink, move or replace with Guix. Ordinary NixOS configuration/service changes are described below. |

The internal disk is about 233.8 GiB. The roughly 34 GiB reported free inside NixOS's Btrfs filesystem is **not unallocated partition space** that can simply be assigned to a new Asahi environment.

The Asahi installer, run from macOS, would create a new UEFI-only environment consisting of its Apple-side boot/recovery structures and paired EFI System Partition (ESP). It also establishes the Apple boot-policy authorization needed to start it.

**Verified free-space finding (2026-09-14, read-only Linux inspection):** approximately **11.65 GB is genuinely unallocated** GPT space between the macOS container and the existing APFS stubs. A UEFI-only Asahi environment is about **2.5 GiB stub + 500 MB ESP (~3 GiB)**, so the prepared route most likely does **not** require resizing macOS. The installer represents free space as its own entry and can insert partitions into such a gap. Confirm the same free region through the installer's own macOS view before proceeding.

If there is insufficient genuinely unallocated space, the fallback is a supported macOS/APFS resize through the installer. That changes the macOS container's allocation and the disk's partition table; it is not intended to erase macOS or its files. The inspected Asahi installer queries Apple's preferred minimum resize size, applies an additional free-space allowance, and delegates the resize to `diskutil apfs resizeContainer`. Snapshots and pending updates can constrain the available allocation. These safeguards materially reduce risk compared with manually editing partitions, but are not a guarantee against filesystem bugs or abnormal interruption. A resize refusal is a stop-and-diagnose event, not permission to force it with Linux partition tools. Verified independent backups remain required because the consequence of actual data loss is high, not because data loss is the expected outcome.

**No exact resize amount or partition targets have been approved.** First collect macOS's layout and the installer's proposed allocation, then review them. The installer—not an invented partition recipe—must determine the required sizes. If no safe allocation is available, stop. Do not substitute a Linux-root shrink, recovery deletion or reuse of an unidentified APFS container.

### Samsung T7

Detected device:

- Model: Samsung PSSD T7, nominal 1 TB, approximately 931.5 GiB.
- Stable whole-device identifier: `/dev/disk/by-id/usb-Samsung_PSSD_T7_S7MLNL0L447791L-0:0`.
- Currently one exFAT partition labeled `T7`.

The current T7 partition table/filesystem will be replaced with:

| Partition | Size | Filesystem | Purpose |
| --- | --- | --- | --- |
| `guix-bootstrap` | 96 GiB | ext4 | Temporary Guix store, daemon state and build workspace while running NixOS. |
| `guix-root` | Remaining space, roughly 835 GiB before overhead | ext4 | The installed Guix system, including `/gnu/store`, `/home`, `/var` and most of `/boot`. |

The prepared layout does **not** put an ESP on the T7: the new internal environment's ESP contains the EFI loader. GRUB reads the rest of the Guix boot/system files from the SSD.

The 96 GiB workspace is a starting allocation, not a proven bound for a completely uncached desktop/kernel build. Check space before large builds; stop or revise the plan if it is insufficient. Do not silently spill builds onto the nearly full internal disk.

Both partitions are **unencrypted** in the current proposal. This is suitable for a hardware trial without sensitive data, not an implicit decision about the final laptop's data security. If encryption is required from day one, revise the storage/boot plan before formatting or migrating data. Do not assume the enclosure provides effective encryption by default.

## 4. What changes in the running NixOS installation

NixOS would temporarily act as the installer/build host. Guix would not replace Nix, the running kernel, desktop, systemd or Home Manager.

The opt-in [`guix-bootstrap` module](../nixos/dendritic/features/guix-bootstrap/README.md) is authored but currently unselected. Selecting it requires editing the owning `~/nixos/dendritic/features/host-asahi-nix/feature.bb` to import the module and supply the T7 bootstrap partition's actual UUID.

After a reviewed NixOS build and explicitly approved activation, it would add:

- The Guix executable and the `guix-daemon` systemd service/socket.
- Dedicated Guix build users and their group.
- Declaratively managed Guix substitute authorization in `/etc/guix/acl`.
- Mount declarations and the following storage mapping:

| Logical location on NixOS | Actual backing storage |
| --- | --- |
| `/var/lib/guix-bootstrap` | T7 partition 1 |
| `/gnu`, including `/gnu/store` | Bind mount of partition 1's `gnu` directory |
| `/var/guix` | Bind mount of partition 1's `var-guix` directory |
| Guix daemon `TMPDIR` | Partition 1's `tmp` directory |

The standard logical `/gnu/store` path is retained so Guix substitutes work. The bootstrap and native stores/databases are separate; two daemons must not share a live mutable store/database.

The daemon is limited to one build at a time and four cores. Publishing and automatic Guix garbage collection remain disabled. The module retains official Guix substitutes and adds the Asahi substitute server/key, an explicit additional executable-code trust decision. No AWS worker or public publisher will be started.

The upstream NixOS Guix module also integrates profile paths/locale environment and can enable Avahi discovery defaults. These are host changes, not just a standalone executable on the SSD. Review the complete evaluated configuration, including these inherited effects, before activation. We will not run Guix Home against the existing NixOS home directory.

**Not all writes move to the T7.** NixOS still writes its configuration generations, Nix store objects, logs, mountpoint directories and potentially user-side Guix checkout/cache data to the internal filesystem. Existing NixOS `/home` is not migrated or replaced.

If `/gnu` or `/var/guix` already holds another Guix installation, stop rather than hiding it under bind mounts.

### NixOS activation and the existing ESP

A normal NixOS `switch`/`boot` deployment can update the current ESP with NixOS boot files. Therefore, saying that enabling the bootstrap module guarantees zero writes to the existing ESP would be incorrect.

Prefer a reviewed **temporary test activation**, after checking the actual activation hooks, to avoid installing a new NixOS default boot generation merely for the trial. Test activation is still a live change to services, accounts, mounts and `/etc`; it is not a dry run. Its exact effects must be reviewed before execution. A persistent deployment that updates NixOS boot files needs separate approval.

In either case, **Guix's bootloader installer must never target the existing NixOS ESP**.

The external mounts use `nofail`, while the daemon/socket require their backing mounts and the daemon has mountpoint prechecks. This is intended to let NixOS boot without the SSD and prevent accidental internal-disk builds. Only evaluation has been done; the SSD-disconnected recovery boot remains an acceptance test, not a guarantee already established.

## 5. Installation sequence and approval checkpoints

### A. Complete inventory and backups — read-only first

1. Reconfirm the T7's stable ID, model, serial, capacity and mount/holder status immediately before erasure.
2. Inspect the complete current ESP and verify the two reported firmware backups are readable.
3. Save the whole current ESP, the internal partition layout, and an inventory of the running boot/system configuration.
4. Confirm independent backups of important NixOS/macOS data and working access to macOS/startup options.
5. Obtain macOS's `diskutil list` and `diskutil apfs list` output to identify container ownership and available allocation. These inventory commands do not resize anything.

**Gate:** backups and disk identities understood. No assumption that firmware files alone are a full laptop backup.

### B. Prepare the T7 and bootstrap tooling

Erase/repartition only the confirmed T7 using the reviewed plan. Record the new UUIDs. Mount partition 1, create its workspace directories, and build/evaluate the proposed NixOS bootstrap configuration without activating it.

Review the complete NixOS diff, not just the new module: unrelated pending configuration changes must not be activated accidentally.

**Gate:** request approval for the exact NixOS activation before running it. T7 erase authorization does not authorize host activation.

After approval, activate the bootstrap configuration, confirm its mounts, and test a small Guix package. Authenticate the pinned channels using a complete Guix-managed checkout; never disable authentication.

Where possible, prebuild the pinned Asahi kernel/bootloader/desktop dependencies before changing the internal partition layout. The final operating-system build still requires the actual new ESP UUID. This ordering defers internal-disk risk until basic build feasibility is demonstrated.

**Gate:** authenticated channels, working daemon, available substitutes/build capacity. On failure, fix or stop before creating an internal environment.

### C. Create the separate internal boot environment

Present the macOS/Asahi installer's proposed space allocation and request approval for that specific internal-disk operation. Then use the supported Asahi installer flow from macOS to create a new UEFI-only environment, named distinctly, such as `Guix SSD`.

Complete its Apple recovery/boot-policy steps. Retain the existing NixOS and macOS boot choices. Do not intentionally change the startup default; inspect it after setup and discuss any change required by the installer.

Return to NixOS and compare the before/after layout. Identify the new paired ESP by installer output and identity—not by guessing the next partition number. Back up its initial contents too.

The selected template is **"UEFI environment only (m1n1 + U-Boot + ESP)"** (`uefi-only-<date>-asahi-<ver>-1.zip`). It creates only a 2.5 GiB APFS stub plus a 500 MB ESP and leaves remaining free space free. It requires a supported system-firmware base; this machine currently reports `asahi,os-fw-version = 13.5`, which is in the package's supported list. If macOS firmware differs at execution time, reconcile that before installing.

Expected installer behavior to review rather than discover late:

- It adds partitions using Apple's `diskutil addPartition` in the chosen free area; macOS device identifiers (e.g. `disk0sN`) are dynamic, so record **GPT PARTUUID and filesystem UUID**, not identifier numbers.
- It extracts the UEFI-only package's ESP tree (m1n1 stage 2 + device trees + U-Boot as `m1n1/boot.bin`), copies this laptop's collected vendor firmware to `vendorfw/`, and writes `asahi/` installer data.
- It installs m1n1 stage 1 into the new stub and requires completing step 2 by powering on into the **new environment's own recoveryOS** (One True RecoveryOS) to pair and enable boot for that environment only.
- It calls `bless --setBoot` for the new environment, i.e. it **sets the new environment as the default startup volume**. Plan to restore the NixOS or macOS default afterward. Do not mistake this for an optional tweak.

**Gate:** the new environment is identified, existing systems remain available, and only its paired ESP is approved as the Guix boot target.

### D. Prepare firmware and build the final system

Mount T7 partition 2 at `/mnt/guix`, and the new internal ESP at `/mnt/guix/boot/efi`. Verify the UUIDs, filesystem types, mount locations and expected firmware layout.

Use firmware extracted for this same laptop. If necessary, copy the existing `vendorfw` contents to the new ESP; do not copy NixOS's m1n1/EFI bootloader over the new environment or run an updater against the old ESP.

Guix's Asahi firmware activation discovers the ESP paired with the booted environment through the device tree. It is not sufficient to place an archive in an arbitrary directory called `/boot/efi`.

Generate the final self-contained configuration with actual root/ESP UUIDs. Authenticate and build it with the pinned Guix and Asahi channel commits. Save the source configuration on the target and in backup storage.

**Gate:** final system builds successfully and target mounts/firmware pass inspection. Scheme evaluation alone is not enough.

### E. Initialize Guix — explicit write boundary

Immediately before `guix system init`, show the protected NixOS ESP and both approved destination filesystems, then request approval to install into them. The report must include:

| Role | Required identity and state |
| --- | --- |
| NixOS ESP — DO NOT WRITE | Current resolved device path, recorded GPT PARTUUID, FAT UUID `5CDF-1DF4`, and current mount source. |
| New Guix ESP — APPROVED TARGET | Current resolved device path, independently approved GPT PARTUUID and FAT UUID, internal parent disk, and exact mount `/mnt/guix/boot/efi`. |
| T7 Guix root — APPROVED TARGET | T7 serial/stable ID, root partition's approved GPT PARTUUID and ext4 UUID, and exact mount `/mnt/guix`; not the bootstrap partition. |

Filesystem UUID identifies the filesystem; PARTUUID identifies the GPT partition. Neither a label nor a potentially renumbered `/dev/nvme0n1pX` alone establishes ownership or approval. The new ESP's pairing with the new Apple environment must first be established from the installer/macOS inventory.

Abort on missing, duplicate, changed or conflicting identities, unexpected mount sources, any overlap with the protected ESP, or changes since approval. Recheck directly before execution without unplugging/remounting devices in between. Checks reduce targeting error; they are not an atomic guarantee against races or arbitrary privileged commands.

**Implementation gate:** the helper now records schema-2 identity snapshots and compares target/protected filesystem UUIDs, GPT PARTUUIDs, parent disk model/serial/size/transport, and exact mount sources/targets/filesystem roots. Missing, duplicate or changed identities are rejected; device renumbering is allowed. The currently booted ESP remains forbidden even if `/boot/efi` mounts a different ESP. Unit tests cover these comparisons; live destination-mount validation is still pending. Review the saved `devices.json` against independently established pairing and the exact destinations before approving installation. A generated snapshot is not itself approval, and these checks do not prove Apple environment pairing.

That command will write:

- **T7 partition 2:** the native store/system closure, generation/profile links, configuration, account/service state and GRUB configuration under `/boot`.
- **New internal ESP:** Guix's m1n1 stage-2 bundle, including device trees/U-Boot, and its GRUB EFI executable.

It must not target the old NixOS ESP. The prepared channel's bootloader is an m1n1/U-Boot/GRUB chain, not merely an extra menu entry in the existing NixOS systemd-boot installation.

Guix writes `/etc/shadow` on **first boot**, not during init, so passwords cannot be set from a pre-boot chroot. The module gives `root` Guix's documented empty initial password and leaves `engstrand` locked: boot the `guix` environment, log in as `root` with the empty password, then run `passwd root` and `passwd engstrand` and verify with `passwd -S`. No password or hash is stored in the repository; if an empty-password window is unacceptable, set a pre-computed root hash in the module and re-run `guix system init` before booting. Then flush and unmount the destination.

**Gate:** successful initialization, usable accounts, retained configuration and backups. Do not reboot into an incomplete installation just to see what happens.

### F. Boot and acceptance

Select `Guix SSD` from Apple startup options with the T7 attached. Initially keep the known-good default unchanged.

Acceptance requires:

- Root mounted from the T7, new internal ESP mounted at `/boot/efi`, expected Asahi kernel and 16 KiB pages.
- Keyboard, touchpad, display/brightness, accelerated graphics, networking and browser usable.
- **No internal-speaker playback until verified:** the exact M1 MacBook Air model's safety profile, running `speakersafetyd`, Asahi DSP configuration and protected PipeWire/WirePlumber route must all be confirmed. A configured service alone is not proof. Initially use a verified headphone output with internal speakers disabled/muted and guard against automatic fallback when headphones disconnect. Never expose/force-enable a raw speaker device or bypass protection with direct ALSA tests.
- Reboot and repeated suspend/resume without USB-root disconnects or filesystem errors.
- Battery/charging and the Bluetooth, camera, microphone and external devices actually needed.
- Locking and other security-critical desktop behavior tested before introducing sensitive data.
- A successful NixOS boot with the T7 disconnected, followed by another successful Guix boot with it reconnected; macOS remains bootable too.

**Gate:** only after sustained acceptance discuss changing the default startup OS or migrating daily work.

## 6. What the initial Guix system includes—and does not

The prepared system is a hardware-validation desktop, not a complete port of the current NixOS setup:

- Hostname `asahi-guix`; user `engstrand`; separate home on the T7.
- Sway with SDDM, Foot, Git, Neovim and the channel's existing desktop package set, including a browser.
- US keyboard layout and `Europe/Amsterdam` timezone.
- NetworkManager/iwd, the channel's Asahi firmware/graphics integration, RTKit, speaker protection and Asahi-specific user audio services.
- Guix/Shepherd system management, not NixOS/systemd.
- No default guest login and no enabled SSH server.

Not part of the first installation: porting Hyprland/Noctalia, keyd mappings, Pi/Herdr, every development tool, Tailscale, secrets management, automatic locking parity, full Guix Home migration or the experimental AWS builder. These need individual implementation and tests. The existing home directory will not be shared between NixOS and Guix.

## 7. Backup, recovery and removal

Before internal partition changes, retain on an independent device/location:

- A current backup of important macOS data, preferably a verified Time Machine backup.
- Important NixOS data, including untracked dotfiles, SSH/GPG keys and other material absent from this repository.
- The **entire current ESP**, not only the two firmware archives.
- Internal partition-table and macOS/APFS inventory records.
- The configuration/channel pins and, once created, a backup of the new ESP before Guix replaces its boot files.

Current verified identities to record: macOS container `nvme0n1p2` (UUID `9ce79161-20e7-46cc-afcf-0e39cb381824`), Apple System Recovery `nvme0n1p8` (`a9546e1d-a253-4387-b2f3-924a8bac1620`), and the currently booted environment's ESP `nvme0n1p5` — FAT UUID `5CDF-1DF4`, PARTUUID `ea8adc5b-ec2d-4df1-913b-f0f05ff85367`, matching `/proc/device-tree/chosen/asahi,efi-system-partition`. The two APFS stubs `nvme0n1p3`/`nvme0n1p4` must be resolved from macOS; at least one should pair with `p5`, and the other may be an orphan.

Another partition on the T7 is not an independent backup. Firmware/ESP archives do not reconstruct all Apple boot-policy/APFS state. A saved partition table is reference material, not something to restore wholesale over a changed disk.

If Guix fails, select the original NixOS environment. Inspect or repair the Guix SSD/new ESP from there. Guix generations can roll back system closures, but do not promise rollback of every m1n1, EFI or partition operation.

After the trial, the NixOS bootstrap module can be removed through a reviewed configuration change and activation. Stop the daemon/socket and release all workspace mounts first; do not hot-unplug a mounted build/root disk.

The 96 GiB workspace can remain available for recovery/builds or be repurposed later. Folding it into the root partition is **not automatic** and may involve another risky partition operation. Do not do that as incidental cleanup.

Removing the internal Guix boot environment also needs its own Asahi/macOS-aware plan. Do not delete arbitrary APFS/EFI partitions from Linux. Keeping the environment while unused is preferable to speculative cleanup.

## 8. Validation status and uncertainties

Completed: 12 Python safety tests, read-only device inventory, generated configuration evaluation, Scheme service-graph checks, repository generation/freshness tests and NixOS evaluation, including the optional bootstrap module.

Not completed: successful channel authentication, pinned time-machine realization, full Guix system build, live bootstrap activation, firmware-layout inspection, internal environment creation, installation, USB-root boot, suspend/resume or hardware acceptance.

Direct authentication against the librarian source caches failed because their partial clones lack historical Git objects expected by Guix/libgit2. This was not a successful signature check. Normal Guix-managed complete checkouts must authenticate before installation.

The Asahi Guix channel labels itself experimental and documents Fedora Asahi Remix as its bootstrap host. This NixOS-based route is source-reviewed, not a hardware-tested supported installer. If it fails, stop and revise the plan rather than replacing boot files or disabling safeguards until something boots.

## 9. End state at a glance (if every gate passes)

**Internal Apple SSD**

- `nvme0n1p1` iBoot System Container, macOS container, existing stubs/ESP/NixOS partitions, and `nvme0n1p8` Apple System Recovery: retained and untouched, except that macOS is resized only if the confirmed free space is insufficient.
- NEW ~2.5 GiB APFS stub named e.g. `Guix SSD`: m1n1 stage 1, its own recoveryOS, and reduced-security pairing scoped to that environment.
- NEW 500 MB ESP paired to that stub: `m1n1/boot.bin` (m1n1 stage 2 + device trees + U-Boot), `vendorfw/` firmware, `asahi/` data; later the GRUB EFI executable and core image written by Guix's bootloader installer.
- Apple default startup volume temporarily set to the new environment by the installer, then restored to NixOS or macOS.

**Samsung T7**

- Partition 1, ~96 GiB ext4 `guix-bootstrap`: bootstrap `/gnu/store`, `/var-guix` and build `tmp`, used only while NixOS runs. Not part of the Guix boot chain.
- Partition 2, remaining ext4 `guix-root`: the Guix System root — native `/gnu/store`, `/var/guix`, `/etc`, `/home/engstrand`, GRUB configuration under `/boot`, and system generations/profiles.

**Boot chain**

```text
Apple startup options → "Guix SSD" (new stub, own recoveryOS)
  → m1n1 stage 1
  → stage 2 + DTs + U-Boot in the new internal ESP
  → \EFI\BOOT\BOOTAA64.EFI (GRUB)
  → /boot/grub/grub.cfg on T7 partition 2
  → Asahi kernel + initrd from /gnu/store on T7 → root = guix-root
```

NixOS keeps its own independent stub/ESP/U-Boot/GRUB chain and kernel on the internal root. The T7 must remain attached to boot Guix; it is not independently bootable on another machine.

**Running system**

- Guix System with the Shepherd init, not NixOS/systemd.
- User `engstrand` with a separate home on the SSD; Guix writes `/etc/shadow` on first boot, so `root` starts with Guix's documented empty password and `engstrand` starts locked. Log in as `root` at first boot and set both passwords with `passwd`; a pre-boot chroot `passwd` cannot work.
- Sway/SDDM desktop with Foot, Git, Neovim and the channel's browser; US layout; `Europe/Amsterdam` timezone.
- NetworkManager + iwd, Asahi firmware service reading the paired ESP's `vendorfw/`, upstream Asahi/Mesa graphics, RTKit, speaker protection, and Asahi PipeWire/WirePlumber Home services for `engstrand`.
- Guix daemon with the pinned Guix + Asahi channels; official and Asahi substitutes; no publishing or automatic GC. No SSH server and no default guest login.
- If the bootstrap module is active, NixOS still boots standalone when the SSD is absent; the SSDs mounts `nofail` while daemon/socket require them.

**Deliberately absent initially:** Hyprland/Noctalia, keyd mappings, Pi/Herdr, Tailscale, secrets management, the AWS builder, a shared home directory, and full development-tool/desktop parity. The trial is a hardware-validation system, not a daily-driver replacement.

## 10. Risk register: likelihood is not consequence

These are qualitative working assessments for the guarded procedure above, **not measured failure rates** for this laptop, SSD or Guix channel. Successful operation of the official installer elsewhere cannot quantify this machine's risk, and source review does not establish hardware reliability.

| Risk | Relative likelihood assessment | Consequence if it happens | Primary control |
| --- | --- | --- | --- |
| Actual data loss during supported macOS/APFS resize | Low working assessment; refusal/failure to resize is a distinct outcome and is not itself data loss | Very high if irreplaceable data is lost | Independent verified backups, official installer/Apple resize tooling, no forced resize or recovery deletion |
| Guix writes the existing NixOS ESP by mistake | Low with a correctly implemented, tested identity gate; controllable, not impossible | High: loss of the known-good boot path until repaired | Separate paired ESP; positive filesystem UUID/PARTUUID/mount checks immediately before writing |
| New Guix environment does not boot | Plausible; a more likely troubleshooting outcome than damage to NixOS | Usually low–medium while the independent NixOS/macOS boot paths remain intact | Retain old boot environments/default, debug from NixOS, back up the new ESP |
| Guix/Asahi build or hardware integration fails | One of the most likely sources of trouble; exact frequency unknown | Usually time, missing functionality or an unusable trial; exceptions below | Authenticate/build before internal changes where possible; hardware acceptance before migration |
| USB-root disconnect or suspend/resume failure | Unestablished for this T7/kernel combination | Hangs, lost writes or corruption on the Guix filesystem | Direct connection, repeated resume/load tests, no sensitive data or sole copies during trial |
| Speaker damage from incorrect/bypassed protection | Low only conditional on a working supported protection stack; not yet demonstrated here | High: physical hardware damage | Hard no-playback gate until model profile, daemon and protected audio route are verified |
| Apple boot-policy/recovery trouble | Possible; not quantified or eliminated by separate ESPs | Can require recoveryOS intervention rather than a Guix generation rollback | Preserve Apple recovery/ISC, retain known-good OS choices, avoid speculative repeated reinstalls |

The expected recoverable failure case is: **Guix fails → choose NixOS from startup options → inspect the Guix root/new ESP.** A broken new ESP does not inherently overwrite the old one. This isolation does not cover global partition-table mistakes, damage to Apple recovery, or unrelated boot-policy problems.

Speaker safety is an important physical-hardware exception to the otherwise mostly functional integration risks. Modern Asahi includes kernel-side interlocks as well as `speakersafetyd`; do not bypass them. USB-root faults are a separate data-integrity risk and should not be dismissed as merely a nonworking desktop feature.

Sources checked for this refinement: [Asahi installer resize implementation](https://github.com/AsahiLinux/asahi-installer/blob/main/src/main.py), [its diskutil wrapper](https://github.com/AsahiLinux/asahi-installer/blob/main/src/diskutil.py), [Asahi partitioning guidance and per-OS ESP isolation](https://asahilinux.org/docs/sw/partitioning-cheatsheet/), and [Asahi audio safety/integration guidance](https://asahilinux.org/docs/sw/audio-userspace/). These explain mechanisms and safeguards, not statistical failure rates. Their current contents are not a replacement for the actual installer proposal and pinned package review at execution time.

**Decision requested:** approve or revise the storage layout and the separate-internal-environment approach. Approval of this design is not blanket permission to execute all privileged steps; the internal resize proposal, NixOS activation and final bootloader destination each get a concrete review checkpoint.
