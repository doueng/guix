# Guix SSD installation checkpoint

## Doom Emacs and Neovim host fixes — native smoke-tested

Ported the missing NixOS editor bootstrap details. Neovim now has a tracked
Lua Fennel bootstrap, bootstraps lazy.nvim under `~/.local/share/nvim/lazy`,
and falls back to the upstream FFF plugin when the Nix-only plugin path is
absent. Doom uses the existing terminal Emacs package through an editable
launcher that selects `~/.config/emacs`; the current Doom checkout was
bootstrapped and synced on the native host. Both editors pass headless startup
smoke tests, and the 30-test Python suite passes. The source changes still
need the reviewed desktop reconfigure to install the generated Home links on
future generations.

## Ghostty packaged and launcher-enabled — activation pending

Added the native `ghostty` 1.3.1 package with its vendored Zig dependencies, GTK4 layer-shell input, runtime data wrapper and Guix build fixes. Added it to the familiar desktop Home profile and the custom launcher alongside Kitty. The pinned time-machine build succeeds on aarch64; the desktop service-graph evaluation and all 30 Python tests pass. Native desktop reconfigure remains pending.

## Desktop parity continuation — staged, not activated

Migrated the remaining portable Pi resources from the NixOS setup into the
Guix-owned desktop snapshot: prompts, skills, keybindings, and local
extensions (jj-guard, usage, autoresearch, vent, prompt-editor, tokenjuice).
Added the native jjui configuration and the available development tools from
`home-base` (Python helpers, Go/Lua/Fennel tooling, formatters, shell tools,
Pandoc/Typst and related CLI utilities). The staging script keeps Pi
credentials out of the snapshot and retains Guix's system Bash path. Home
entries now use out-of-store symlink chains back to this checkout for Fish,
Neovim, Doom, Pi and desktop configs, so edits do not require a Guix rebuild.
A fresh snapshot was built successfully; the latest pinned desktop system
build produced `/gnu/store/0y1yxkpwfgv3yiz6bpcc1g0bd4iqwxkb-system`.
Noctalia 5.1.0 is included as the opt-in Hyprland shell/launcher, and
Babashka 1.13.223 is included as `bb`. All user-facing desktop/Herdr scripts
are staged and linked generically rather than by a fixed name. It is still not
activated; native reconfigure remains a reviewed step.

## Pre-publish scrub and GitHub CLI

Scrubbed the shared jj email to the GitHub noreply address and made `ssh_openclaw` argument-only (the real host belongs in private ssh config); both folded into the initial commit before any push, so the pushed history never contained them. Added `github-cli` (provides `gh`) to the Guix home profile so the native system can manage the GitHub remote; it resolves in the pinned-era package set (Guix names the package `github-cli`, not `gh`). Verified: 30 Python tests and both Scheme checks directly from the checkout. The package lands on the system at the next reviewed reconfigure.

## Makefile for clone-and-apply

Added a root `Makefile` wrapping the direct desktop apply flow for a fresh clone on the running Guix system: `make build`/`make dry-run` (pinned time-machine system build), `make apply` (the native-destination guards and reviewed reconfigure from desktop/README.md), plus `make test`, `make eval`, and `make eval-desktop`. No formatting, mounting, `guix system init`, or ESP-write automation was added; `apply` still requires an explicit successful build and sudo. `make -n` expansion and all Python tests verified; README.md and desktop/README.md point to it.

## Standalone repository extraction and source review

The kit now lives at `/home/engstrand/guix` in its own jj repository. All ignored `local/` artifacts moved intact. Desktop inputs formerly read from NixOS features are owned here under `desktop/shared/`. NixOS bootstrap/AWS infrastructure and the original migration assessment remain in `/home/engstrand/nixos`; references were updated.

Review findings: [REVIEW.md](REVIEW.md). Fixed fail-fast activation instructions and clarified that Sway recovery uses the previous generation. No Scheme configuration, channels, existing snapshots or installed system were changed. The patched U-Boot build and native acceptance remain pending as recorded below.

Validation: 30 Python tests pass both in the new checkout and an isolated copy outside either repository; base Scheme and freshly staged desktop service-graph checks pass; both shell scripts pass `bash -n`. NixOS `make bb-check` and `make eval` pass after removal. This does not constitute a pinned system build or hardware test.

## Patched U-Boot folded into the desktop snapshot — transfer and native build pending

Authored `modules/engstrand/bootloader.scm`: `asahi-u-boot-os-prepare` (channel `asahi-u-boot` with a one-line `DM_FLAG_OS_PREPARE` patch from the upstream proposal, file `modules/engstrand/u-boot-xhci-dwc3-os-prepare.patch`) and `m1n1-u-boot-grub-bootloader-os-prepare` (`efi-bootloader-chain` reusing the channel installer, which is not exported and was copied). `make-ssd-os` now overrides the bootloader field, inheriting the channel configuration (targets/keyboard), so both the recovery base and the desktop layer carry the fix. A reconfigure composes a new `m1n1/boot.bin` on the new ESP via `update-m1n1`, which preserves the previous image as `boot.bin.old`.

New snapshot staged and archived: `local/familiar-desktop-os-prepare` (+ `.tar.gz`), 86 files, hash-manifested. Validation passed: 30 Python tests; `tests/evaluate.scm` (now asserting the patched bootloader and patch content) and `tests/evaluate-desktop.scm` (desktop preserves the patched bootloader) via the Nix-packaged Guix and pinned Asahi source; `make fmt`, `make bb-test`, `make bb-check`, `make eval`. The U-Boot source build itself is untested — it happens in native Guix during the desktop build. The patch was generated from the exact `asahi-v2025.10-2` source the channel pins.

Next: transfer `local/familiar-desktop-os-prepare` to T7 root `/etc/guix-desktop-os-prepare` via the guarded block (rofl.sh), boot Guix, `guix time-machine -C /etc/guix-desktop-os-prepare/channels.scm -- system build -L /etc/guix-desktop-os-prepare/modules /etc/guix-desktop-os-prepare/system.scm` (this builds the patched U-Boot from source), then the reviewed reconfigure with the native-destination guards from desktop/README.md. The earlier `/etc/guix-desktop-staged` transfer is superseded; do not use it. Speaker and hardware gates unchanged.

## Root cause identified externally: U-Boot hands a running xHCI to Linux

A September 11, 2026 upstream U-Boot patch (`[PATCH] usb: xhci-dwc3: Add DM_FLAG_OS_PREPARE flag`, Romain Beauxis) matches this machine's symptoms exactly, including a Samsung T7 on Apple Silicon booted through EFI/GRUB. Verified against source: neither `bootm` nor `ExitBootServices` stops USB controllers unless their driver sets `DM_FLAG_OS_PREPARE` or `DM_FLAG_ACTIVE_DMA`; `xhci-dwc3` sets neither, so U-Boot leaves the Type-C xHCI running at handoff, and Linux never enumerates devices on it until replug. The Asahi `apple_m1_defconfig` enables `CONFIG_USE_PREBOOT` + `CONFIG_USB_KEYBOARD`, whose default preboot is `usb start`, so the T7 is enumerated by U-Boot on every boot and the controller is then handed over running. The upstream issue is AsahiLinux/linux#554 (M1/M2 Air, Linux 7.0/7.1). Evidence: `local/usb-driver-review/uboot-patch.html`, `issue554.json`; U-Boot source reviewed in the librarian checkout (no `DM_FLAG_OS_PREPARE` in `xhci-dwc3.c`). This supersedes the Linux-side reset theories; Guix/UAS/mounting layers were never reached. A one-boot interactive `usb stop` at the U-Boot prompt before the normal boot command is the next confirmation step, and a patched U-Boot (one-line driver flag) is the durable fix candidate for both chains; both ESPs' m1n1/U-Boot would need a reviewed update.

## Clean-boot port status captured; boot tracing is the next diagnostic

The next clean NixOS boot again has a Type-C partner in host/source mode, two xHCI root hubs and no external USB device. Both xHCI `portsc` files report `0x0a0002a0 Speed=0 Link=RxDetect PP WCE WOE`: no current-connect status or enabled link; PP is the controller's logical port-power flag, not a voltage measurement. No reset-induced PHY warning recurred. This localizes the observed failure before storage enumeration but does not identify the original cause. User supplied the full read-only log `/var/tmp/t7-readonly.flyQcm.log`; unprivileged kernel evidence is also in ignored `local/t7-clean-reboot-kernel.log`.

Source at `asahi-7.1.10-1` identifies the prior warning as `WARN_ON_ONCE(atcphy->pipehandler_up)` during a mode change, supporting a teardown-ordering problem in the diagnostic reset rather than proof of the boot failure. Available trace events include `tps6598x:*` and xHCI port-status events. `rofl.sh` is now diagnostics-only, also reads the existing trace buffer, and makes its private log owned by the invoking sudo user. No tracing boot parameters or persistent configuration have been applied.

## USB-C reinitialization failed with an Apple PHY warning — reset script disabled

The parent `dwc3-apple` rebind did not recover the T7 and left host buses absent. Source review of `asahi-7.1.10-1` explains that the Apple driver waits for a Type-C connection/role notification to initialize the host; rebinding it alone does not replay that state.

After another reboot with the T7 attached, the user ran the guarded `tps6598x` unbind/rebind for `0-0038`. Unbinding triggered `WARNING: drivers/phy/apple/atc.c:2328 at atcphy_mux_set`, through `tps6598x_disconnect` / `tipd_remove`, followed by `Pipehandler lock not acked` and `Failed to lock pipehandler` on `383000000.phy`. Host buses were recreated after rebinding, but the T7 still did not enumerate. These are test-induced errors, not proof of the original boot-failure cause. `rofl.sh` now exits before any reset. Do not automate or repeat these resets; return to a clean boot before further runtime diagnosis. Kernel evidence is in ignored `local/t7-typec-reinit-kernel.log`; the user's original log is `/var/tmp/t7-typec-reinit.wgbIuU.log`. Next work should inspect the PHY/Type-C teardown ordering and boot initialization with source/tracing rather than escalating resets. Port/cable/hub are treated as known-good per the user.

## Attached-at-boot USB failure reproduced; xHCI rebind did not help

After a user-reported reboot with the T7 left connected, NixOS 7.1.10 has no T7 USB or block device. Type-C port0 reports a partner, host data role and source power role; the `382280000.usb` controller has only its USB root hubs. Both T7 filesystems are absent and the bootstrap units inactive. The user ran a guarded unbind/rebind of `xhci-hcd.0.auto`: logs confirm successful controller recreation, but still no external USB enumeration. This rules out that particular restart as a workaround, not every controller/link/power cause. The earlier Guix UAS fix addresses a different, post-enumeration failure. Initial kernel evidence is saved in ignored `local/t7-missing-boot-kernel.log`. No persistent USB workaround has been installed. Keep the drive attached for further diagnosis; do not reset USB while native Guix uses it as root.

Before this reboot, the user successfully copied the verified familiar-desktop snapshot to the T7 at `/etc/guix-desktop-staged` and unmounted it. Desktop activation remains pending.

## Familiar desktop layer BUILT — activation pending

The user chose cache-only packages after the Ghostty packaging attempt stalled. The full desktop system now builds successfully in the bootstrap store (`/gnu/store/ir3wwf3n…-system`): 2.3 MB of substitutes plus two tiny local builds (Pi/Herdr binary copies). Ghostty and Noctalia are confirmed unavailable (not in Guix; a from-source Ghostty attempt was abandoned after covering 36 vendored deps + sandbox integration work — the draft package stays in `modules/engstrand/packages.scm`). Terminal is Kitty; browser is Chromium via Flatpak (the `flatpak` package is fully substitutable; the browser installs from Flathub after first boot); launcher falls back from Noctalia to Wofi. All Python tests (30), both Scheme checks, the Hyprland parser and the repo pipeline pass. Desktop details: [desktop/README.md](desktop/README.md).

Next: transfer the snapshot to the running Guix system and reconfigure there (guarded block in desktop/README.md), then test. Do not rerun initialization or formatting.

## Earlier desktop preparation — superseded by the entry above

At the user's request, added an opt-in `make-familiar-os` layer, direct Scheme desktop sources, feature-owned desktop assets and tests. Details and native build/reconfigure instructions are in [desktop/README.md](desktop/README.md). The base `make-ssd-os` remains unchanged by this desktop work. The layer inherits the working kernel/UAS, storage, bootloader, account declarations, pinned channels and exact Asahi Home audio/D-Bus services. Sway/Foot are dropped; SDDM starts Hyprland.

Ported from NixOS: Hyprland controls/layout, the existing keyd layers with Shepherd/uinput integration, Kitty/Fish, Waybar/Wofi, shared Fish/Git/JJ settings with Guix-safe PATH and no Watchman, basic developer tools, and plugin-free Neovim using the shared core Fennel options/keymaps. New binary-release Guix packages in `modules/engstrand/packages.scm`: **Pi 0.85.1** (loader patched to Guix glibc, `PI_SKIP_VERSION_CHECK` wrapper) and **Herdr 0.9.0** (static), both from the official aarch64 release artifacts with recorded hashes; Herdr's config and tab helpers are staged, and Pi's `settings.json` uses the Guix system Bash. **Chromium** (`ungoogled-chromium`, native Guix package — Google Chrome is not packaged for Guix) behind the ported Flatpak-free `chrome-unified` script, and the **custom launcher** ported to Python: Noctalia dmenu when present, Wofi fallback, entries for terminal/Chromium/Herdr/Pi. Hyprlock has PAM support and a ten-minute idle lock, still requiring native lock/unlock testing.

**Ghostty and Noctalia are blocked, not skipped:** neither is packaged for Guix. Ghostty needs a Zig 0.14 toolchain package plus vendored-dependency packaging; Noctalia needs Quickshell first. Both require the Guix daemon for iterative builds and are separate follow-up projects. Until then Kitty is the terminal, Waybar the bar, and the launcher falls back from Noctalia to Wofi. No speaker-unmute controls were added.

Prepared self-contained snapshot `local/familiar-desktop` and archive `local/familiar-desktop.tar.gz`; neither is installed. All Python tests pass (staging safety, portable shell/JJ, Herdr/Pi configuration, launcher scripts, Fish syntax/PATH, isolated Neovim startup). Base and desktop Scheme/service-graph checks and the staged entry-point evaluation pass with the available Nix-packaged Guix runtime and pinned Asahi source. NixOS Hyprland 0.56.2 parses the config; Guix's available package is 0.55.4 and needs its own native parser/session test. `make fmt`, `make bb-test`, `make bb-check` and `make eval` pass.

Full authenticated pinned system build, target transfer, activation and native desktop testing are pending: this session is still on NixOS without the T7/bootstrap store available. No system activation, mount, ESP write or password change was performed. Next: reconnect the T7, transfer the snapshot through a reviewed destination, build it in native Guix (this also builds the new Pi/Herdr/Chromium packages), then review reconfiguration against the running Guix root/new ESP. Do not rerun initialization or formatting. Keep the old system/configuration until the new desktop works.

## Continuation — native acceptance needs the Guix session

Read-only inspection on this continuation finds NixOS running the `fkj2yqb4…` closure through the protected boot-origin PARTUUID `ea8adc5b-ec2d-4df1-913b-f0f05ff85367`, with old ESP `5CDF-1DF4` mounted at `/boot/efi`. The new Guix ESP remains present with its saved UUID/PARTUUID. No T7 block device is currently visible and no Guix target/bootstrap mount is present. This does not establish whether the T7 is physically disconnected or failed to enumerate, so SSD-disconnected recovery acceptance still needs user confirmation. Noninteractive sudo is unavailable. No activation, mount, installation or bootloader write was performed.

The user confirms both Guix passwords have already been set; password usability has not been independently tested here. Next: collect native Guix root/ESP mounts, kernel/page size and service status. Do not rerun `rofl.sh` or restore the NixOS bootstrap merely to perform native acceptance. Keep internal speakers unused until the complete safety stack is verified, and leave startup-default changes and data migration for after acceptance.

## First boot succeeded after the `uas` fix

The user reports the Guix environment now boots with the `uas` initrd, completing the installation step. This confirms the root cause: the channel initrd omitted `uas` while the kernel sets `CONFIG_USB_UAS=m`, so no driver bound the T7's UAS interface. Fixes are in the working copy: initrd-modules now prepend `uas` in the authored module and `local/install`; `tests/evaluate.scm` asserts it; PROGRESS records the investigation.

Validation after the change: the Scheme evaluation prints `PASS: channel pins, OS records, users, service graph, audio ownership, boot target.` (including the `uas` assertion); the Python suite is 21/21; `make fmt`, `make bb-test`, `make bb-check` and `make eval` pass. The verified system initrd `q6mvwbfvh2q2vv0zw4dkxvv16r60lqky-raw-initrd` contains `uas.ko`.

Still outstanding: confirm root/engstrand passwords are set and usable; accelerated graphics, networking, input/brightness; suspend/resume with the USB root; booting NixOS with the T7 disconnected; and the speaker-protection gate. No internal speakers until `speakersafetyd`, the machine's profile and the PipeWire/WirePlumber route are verified. Do not change the default startup OS or migrate data before acceptance. The channel's stale `uas` comment should be reported upstream independently.

## `uas` fix installed; first boot after fix pending

`guix system init` re-ran successfully with the fix: system `/gnu/store/j9a2jwz1zx3iqfjx735h40c2ryh17ffz-system`, generated GRUB `vjvlc25sqyh1b0q7h31w9hrhkfqdvydw-grub.cfg`, m1n1 reinstalled to the new ESP. Both privileged identity/mount/firmware checks passed. Read-only inspection confirms the installed closure's initrd `q6mvwbfvh2q2vv0zw4dkxvv16r60lqky-raw-initrd` contains `uas.ko`, and its GRUB entry references `j9a2jwz1…`. Its activation script matches the previously verified password setup (root empty, engstrand locked). The system has not been booted. Unmount both target filesystems, then cold-boot the `guix` environment; watch whether the T7 now binds UAS and whether it enumerates at cold boot. If enumeration still fails, treat that as a separate USB issue. No configuration or boot file changed after this init.

### `uas` fix built and verified

Read-only inspection of the installed target confirmed `uas.ko.zst` exists in the real kernel module outputs (`asahi-linux-7.0.13-1` and the kernel profile), while the installed initrd's module directory lacked it. The `0pcx` bootstrap closure was re-activated (its Home Manager unit fails on an unrelated Herdr protocol mismatch; Guix units are active and mounts correct). `guix system build` with the fix produced `/gnu/store/4i7qmimfgyf2z310wnsan5vg4nl3h3zh-system`; its initrd `q6mvwbfvh2q2vv0zw4dkxvv16r60lqky-raw-initrd` contains `uas.ko` in `09vq94z4…-linux-modules`. The target is unmounted and no re-init has run. Next: re-run the approved `guix system init` into T7 root `c694c1fc-a241-459a-a60d-1c829f1b9c30` and new ESP `77C4-10EC`, then boot. If cold-boot USB enumeration still fails, that is a separate problem from the driver gap.

## Root cause found: initrd omits `uas` despite `CONFIG_USB_UAS=m`

A user-supplied external debug session confirmed the failure is between USB enumeration and block-device creation: the T7 appears under `/sys/bus/usb/devices` (`2-1`, interface `2-1:1.0`, mass-storage class `08`, protocol `62` = UAS), but no driver binds, `/sys/bus/usb/drivers/uas` does not exist, `/sys/class/scsi_host` and `/sys/class/scsi_device` are empty, and no `sda` appears. Guix then never finds root UUID `c694c1fc-a241-459a-a60d-1c829f1b9c30`.

Cause: the pinned channel's kernel config sets `CONFIG_USB_UAS=m`, so `uas.ko` is built, but `modules/asahi/guix/initrd.scm` omits `uas` with a stale comment claiming it was removed. A built-in `usb-storage` defers UAS-capable devices when UAS support is compiled in, and the initrd runs no udev to autoload `uas`, so nothing binds the interface. The earlier one-boot `usb-storage.quirks=04e8:4001:u` attempt was inconclusive (application never verified) and does not replace this fix.

Fix applied to the authored module and the generated `local/install` copy: `(initrd-modules (cons* "uas" asahi-initrd-modules))` with `(asahi guix initrd)` imported. `tests/evaluate.scm` now asserts `uas` is in `operating-system-initrd-modules`. Not yet built, re-initialized or booted. `uas.ko` must exist in the kernel's module output for the build to succeed; if it does not, the build fails loudly at `find-module-file`.

## Initrd reconnect followed by `,q` did not boot Guix

The user tried unplugging/reconnecting the T7 in the Guile initrd shell, then entered `,q`; the machine returned to NixOS. No post-reconnect partition inventory or USB messages were supplied, and whether the target was unmounted was not independently verified. This does not establish whether reconnecting restored detection: quitting the debugger does not restart the already-failed partition lookup. Before any further reconnect test, verify no T7 filesystem is mounted, then inspect `/sys/class/block` or `/proc/partitions` after reconnecting, without quitting the debugger. Exact error/UUID and runtime command line are still needed to distinguish root lookup from ESP lookup.

### Shutdown method confirmed

The user confirms both attempts used full shutdowns, not restarts. Do not explain the failure as a warm-reboot-only problem. Boot-time USB enumeration or firmware/driver initialization remains a possibility; neither the exact missing partition nor the Guix runtime device state has been captured. Next diagnostic should collect that evidence rather than repeat installation or add more speculative boot parameters.

### Offline dependency and USB audit completed

Further read-only audit of `local/boot-debug.K3b19d` found 35 distinct initrd kernel modules, all with vermagic `7.0.13-asahi SMP preempt mod_unload aarch64`. Every declared hard dependency is present. All 70 `modules.name` targets exist in the archive, and every compressed `.ko.zst` decompresses byte-for-byte to its uncompressed `.ko` counterpart. The loader recursively loads declared dependencies. There are no declared soft dependencies in this set. These checks do not prove successful probing or match against an independently inspected installed kernel Image.

The Guix modules' device aliases match the currently visible hardware for both TPS6598x controllers, both Apple ATC PHYs, both Apple DWC3 controllers, and Apple NVMe. The absence of NixOS 7.1's separate `tps6598x_core` module in the Guix 7.0 initrd is not by itself a missing dependency: Guix's `tps6598x` metadata depends only on `typec`, which is included.

The current NixOS boot again registers xHCI root hubs but no external USB device until the user's reconnect; then USB enumeration and UAS/SCSI discovery succeed. No explicit USB probe failure/timeout explains the pre-reconnect absence in the inspected log. This localizes the observed NixOS problem before storage binding and reinforces that UAS alone cannot explain that observation. Current Type-C roles after reconnect show the SSD-connected port as host/source. The failed Guix boot's runtime USB/partition/mount state remains unavailable. No driver reloads, live activation, ESP writes or filesystem repair were performed.

### UAS-fallback test did not boot

The user reports the same partition-not-found failure after the proposed one-boot test, followed by entering `,q` in Guile and returning to NixOS. Exact error text/UUID and the failed boot's actual kernel command line have not been captured, so application of the temporary flags remains unverified. Current read-only inspection shows a new NixOS boot with kernel 7.1.10, the `fkj2yqb4…` closure, and protected old ESP boot origin `ea8adc5b…`; this is not Guix successfully continuing into its native root. The T7 was replugged again and both saved UUIDs/PARTUUIDs match; no T7 filesystems are mounted.

Do not repeat installation or claim the UAS omission alone explains this failure. Next diagnostic needs a photo of the exact partition error plus `/proc/cmdline`, `/proc/partitions`, and `/proc/mounts` read from inside the failing Guile initrd shell, before quitting it. Distinguish absent external root from absent internal ESP and USB enumeration from storage-driver binding. No further configuration, ESP or activation changes have been made.

### Installed initrd inspected — UAS absent

The user copied the installed initrd using a read-only `ro,noload` root mount into ignored `local/boot-debug.K3b19d/initrd.cpio.gz`, then unmounted it. The agent inspected its CPIO listing and extracted init/Scheme sources without executing them. The archive contains the Apple USB/PHY/Type-C modules (`dwc3-apple`, `phy-apple-atc`, `tps6598x`, and dependencies), but no `uas.ko`; the explicit load list also omits UAS. The init encodes the correct root and ESP UUIDs. Its partition resolver retries for roughly 21 seconds and supports `rootdelay` before root lookup. Module inclusion does not establish successful runtime probe/USB enumeration.

Live T7 USB identity is `04e8:4001`, serial `S7MLNL0L447791L`. Proposed reversible test: edit only the Guix GRUB menu entry for one boot, remove `quiet`, append `usb-storage.quirks=04e8:4001:u rootdelay=30`, and boot with Ctrl+X/F10. This requests the built-in USB-storage fallback instead of the absent UAS module and adds enumeration time. It does not persistently edit GRUB or any ESP and is not a proven fix for the separate reconnect-only enumeration observation. No test boot or permanent fix has yet been performed. On failure, capture the exact error/UUID and preceding USB messages before making further changes. Do not unplug once root may be mounted.

### USB reconnect restores T7 detection

After the user unplugged/reconnected the unmounted T7, NixOS detected serial `S7MLNL0L447791L` and both expected filesystem UUIDs again. Kernel logs show xHCI removal at 14:35:19, controller registration at 14:35:48, and SuperSpeed USB/UAS/SCSI disk discovery at 14:35:53. The user reports detection requires reconnecting after restart. This is evidence of a USB enumeration/handoff problem, not proof of a particular Guix initrd defect.

The cached Asahi source is at the exact pinned commit `0a58b24…`. Its initrd list includes the Apple USB/PHY/Type-C drivers but omits `uas` (a comment claims it is unavailable); the earlier realized kernel inspection recorded UAS as a module, and the T7 uses UAS on NixOS. Inspect the actual installed initrd before deciding whether this omission matters; do not assume adding UAS fixes pre-enumeration failure. Bootstrap remains inactive and no filesystem is mounted. The next requested operation is a read-only, no-journal-replay root mount to copy the installed initrd into ignored local diagnostics, followed by unmounting. No installation/configuration change has been made.

### First boot failed in Guile rescue shell

The user reports reaching a Guile rescue shell after selecting Guix, with a partition-not-found error; the T7 remained attached. Exact error text/UUID is not yet available. Reaching that shell indicates the kernel/initrd loaded, not a successful native-root boot. Password initialization and hardware acceptance remain incomplete.

Back in NixOS, read-only inspection confirms the protected boot origin (`ea8adc5b-ec2d-4df1-913b-f0f05ff85367`) but the older `fkj2yqb4…` closure without the active Guix bootstrap. No T7 block device or USB device is enumerated: sysfs shows only xHCI root hubs, no Samsung device or USB disk identifiers. No target/bootstrap filesystems are mounted. This current NixOS observation does not prove the cause of the earlier Guix failure. Next, disconnect/reconnect the unmounted T7 in NixOS and recheck USB/storage detection before restoring bootstrap access or inspecting the installed initrd. No activation, filesystem repair, formatting or bootloader change was performed.

### Installed profile and boot artifacts verified before first boot

The user's privileged verification confirms `/var/guix/profiles/system` → `system-1-link` → `/gnu/store/g28q2g7iwb5nsrzf04zxd2xk9zv5iqcf-system` on the target. Its activation link is the expected `xbpfpfh8…-activate.scm`; copied activation, account initialization and generated GRUB store files compare byte-for-byte with the reviewed bootstrap outputs. The new ESP contains a 164 KiB ARM64 EFI application at `EFI/BOOT/BOOTAA64.EFI`, a 103 MiB `m1n1/boot.bin`, and a 31 MiB firmware archive. These are artifact checks, not a boot or hardware test.

Next, compare the installed regular `/mnt/guix/boot/grub/grub.cfg` itself with the reviewed generated file, flush and unmount the target ESP/root, then shut down normally with the T7 attached. Select the new `guix` Apple startup environment. At first boot use a text console to log in as root with an empty password and immediately set root/engstrand passwords. Do not try a pre-boot chroot password change. First boot, password setup, speaker protection, hardware acceptance and SSD-disconnected NixOS recovery remain untested.

### Password-fix re-init succeeded

The user ran the approved guarded re-init block. Both privileged identity/mount/firmware checks passed. Guix initialized `/gnu/store/g28q2g7iwb5nsrzf04zxd2xk9zv5iqcf-system`, generated `/gnu/store/4abrvfiwm910wzqbalp3rx3vdppsxl6y-grub.cfg`, installed m1n1 to `/mnt/guix/boot/efi`, and reported successful bootloader installation. The system has not yet been booted.

Read-only inspection of the bootstrap store confirms this closure's activation script references `/gnu/store/dclwihnr7jiazwf18p86bfbq4vn36g2k-activate-service.scm`, with root's empty initial password and engstrand locked. Its generated GRUB entry uses the approved T7 root UUID and the new system closure. Live mounts remain a single root/ESP pair with no `/dev` bind tree. Target profile links, copied activation data, and ESP boot files still require privileged post-init verification; `sudo -n` is unavailable. Do not rerun init merely because this verification needs the user's terminal.

### Earlier re-init approval

The user explicitly approved re-initializing T7 root `/dev/sda2` (UUID `c694c1fc-a241-459a-a60d-1c829f1b9c30`, PARTUUID `0f2eb3d1-bd42-440d-aa93-a63ec8f79033`) and the new internal ESP `/dev/nvme0n1p4` (UUID `77C4-10EC`, PARTUUID `5408cbd2-dc6c-49c6-bee9-c51c5f3a29fc`), including m1n1/GRUB reinstallation and the empty initial root password. Protected NixOS ESP `/dev/nvme0n1p7` remains excluded. Both Guix units are active and the corrected build is present. `sudo -n` still requires a password; no re-init has been executed by the agent. The user must run the guarded command block in their terminal and return its result.

### Mount cleanup completed

The user removed the upper ESP/root mounts and leftover `/dev` bind tree, then supplied a successful privileged `check-mounts` result, including firmware-layout verification. Subsequent read-only inspection confirms exactly one mount each for T7 root `/dev/sda2` at `/mnt/guix`, new ESP `/dev/nvme0n1p4` at `/mnt/guix/boot/efi`, and protected ESP `/dev/nvme0n1p7` at `/boot/efi`. Saved/live identities still match and boot origin remains the protected NixOS environment. Re-init with the password fix has not run; the destinations were subsequently approved above.

### Earlier duplicate-mount blocker

Read-only inspection confirms the original NixOS boot origin (`ea8adc5b-ec2d-4df1-913b-f0f05ff85367`), the `0pcx033g…` bootstrap closure, and both active Guix units. Saved schema-2 UUIDs/PARTUUIDs and parent disk identities match live devices; authored channels/module match `local/install`. All 21 Python tests pass.

Both `/mnt/guix` and `/mnt/guix/boot/efi` are mounted twice, with an old slave `/dev` bind tree beneath the lower root mount. `check-mounts` correctly stops at `Not an exact mountpoint: /mnt/guix`. Do not mount again or bypass the check. Remove the upper ESP mount, then the upper root mount, then the exposed `/mnt/guix/dev` bind tree; retain the original root/ESP mounts and rerun the privileged check. Stop on an unmount failure; do not force or lazy-unmount.

`sudo -n` requires a password, so cleanup and firmware verification require the user's terminal. No mounts, installation targets, boot files or activation state were changed in this continuation. Re-init with the password fix remains pending.

## Password initialization is a first-boot step (blocking)

`guix system init` does not populate the target `/etc`; it only creates the directory. `/etc/passwd`, `/etc/shadow` and `/etc/group` are written on **first boot** by the system's activation script (`activate-users+groups`), from account data compiled into the system closure. Confirmed by reading the built closure's `activate` link and the account `activate-service.scm`:

- `engstrand` password `"*"`
- `root` password `"*"`

`"*"` is a locked hash. The runbook's pre-reboot `chroot … passwd` cannot work: `/etc/passwd` does not exist before boot, and the first boot would overwrite `/etc/shadow` with the compiled locked value anyway. Guix's documented default is an **empty** root password (`%root-account` uses `(password "")`), with user passwords set afterwards by running `passwd` as root.

A plain `passwd` in the chroot also failed earlier with `Cannot determine your user name`, which is the same missing-`/etc/passwd` symptom. `/mnt/guix/etc` currently contains only our hand-copied `guix-ssd` directory.

**Fix applied:** the root account's password changed from `"*"` to `""` in `modules/engstrand/asahi.scm`, the generated `local/install` copy, and the `tests/evaluate.scm` expectation (root empty, engstrand locked). Pinned evaluation passes. The system was rebuilt as `/gnu/store/a753m03m2bn6mrjxyaiz7mjw5n5yz0bg-system` (log `local/system-rebuild.log`); its account activation now carries root `""` and engstrand `"*"`, verified directly.

**Still required:** remount `/mnt/guix` and `/mnt/guix/boot/efi`, re-copy the config to `/mnt/guix/etc/guix-ssd`, and re-run `guix system init` to the same approved destinations (this also re-installs m1n1/GRUB). Then boot the `guix` environment, log in as root with an empty password, and run `passwd root` / `passwd engstrand` immediately. The boot artifacts were otherwise already correct (new ESP `EFI/BOOT/BOOTAA64.EFI`, `grub.cfg`, `m1n1/boot.bin` with `.old` preserved). Re-init has not yet been run.

## Native system initialized — not yet booted

The user approved the exact destinations and ran `guix system init`. Root's pinned time-machine authenticated both channels (`guix` 133,815 commits, `asahi` 844 commits) without `--disable-authentication`, finished on `/gnu/store/yh1gy8wannzraf0wkpxn8g0m8qqspsip-system`, installed m1n1 to `/mnt/guix/boot/efi`, and reported `bootloader successfully installed on /boot/efi`.

Wrote, per the approved report, the T7 root `/dev/sda2` and the new ESP `/dev/nvme0n1p4` only. The protected NixOS ESP `/dev/nvme0n1p7` (`5CDF-1DF4`) was not an init destination. Post-init boot-artifact verification and password initialization are pending; the system has not been rebooted.

## Final system built — installation not yet run

Booted back into the original NixOS environment with the T7 attached. Verified boot origin: `/proc/device-tree/chosen/asahi,efi-system-partition` is now the protected old PARTUUID `ea8adc5b-ec2d-4df1-913b-f0f05ff85367`, and `/run/current-system` is `0pcx033g…`, so the Guix units came up and `guix build hello` works again.

Generated the identity record and configuration at `local/install` for the actual new Guix ESP. Two fixes were required:

- The generated `system.scm` lacked `(guix channels)`, so the `channel` binding used by the copied `channels.scm` was unbound. Added `(guix channels)` to `prepare.py` and regenerated the local config; the corresponding generated output wording is covered by a test assertion.
- The regenerated local `install/system.scm` was hand-edited the same way because `configure` refuses to overwrite an existing directory. A future fresh install will pick this up from `prepare.py`.

`guix system build` for the final closure completed with no source builds required (all outputs were substitutes): `/gnu/store/z3fy1pjs0g200jsfcr5wil5c6iwi531c-system`. Dry-run is `local/system-dry-run.txt`; full log is `local/system-build.txt`.

Confirmed identities this session: new Guix ESP `77C4-10EC` (PARTUUID `5408cbd2-dc6c-49c6-bee9-c51c5f3a29fc`) on `/dev/nvme0n1p4`; new APFS stub on `/dev/nvme0n1p3`; protected NixOS ESP `5CDF-1DF4` now `/dev/nvme0n1p7`; Guix root `c694c1fc-a241-459a-a60d-1c829f1b9c30`.

Still pending: inspect and back up the new ESP (sudo), verify firmware layout, review the identity record, then explicit approval for `guix system init`, password initialization, and hardware acceptance. **No installation, firmware copy or ESP write has been performed.**

## After macOS UEFI installation — boot-origin safety stop

The bootstrap has since been restored: the live system is the `0pcx033g…` closure, both Guix units are active, workspace mounts resolve to the expected T7 partition, and `guix build hello` succeeds. No activation was executed by the agent in this continuation.

**Blocking finding:** `/proc/device-tree/chosen/asahi,efi-system-partition` is `5408cbd2-dc6c-49c6-bee9-c51c5f3a29fc`, the new Guix ESP. NixOS is therefore running through the new environment's boot chain even though `/boot/efi` mounts the protected old ESP `5CDF-1DF4`. The kernel command line points to the original NixOS generation. Do not infer the booted environment from the running OS or `/boot/efi` alone.

`prepare.py configure` refused the new ESP as the currently booted environment. No `local/install` directory was created, no final system build was started, and no ESP/root installation writes were made. Next, shut down normally and select the original NixOS/Fedora environment (not `guix`) in Apple startup options. Before retrying, verify that the device-tree ESP PARTUUID is the protected old value `ea8adc5b-ec2d-4df1-913b-f0f05ff85367`. Bootstrap services may need the previously reviewed temporary activation again after reboot; inspect rather than assume persistence.

Safety helper changes: schema-2 snapshots now record both target UUIDs/PARTUUIDs, protected ESP identity and parent disk model/serial/size/transport; mount checks compare live identities and exact source/target/filesystem-root values. Device renumbering is allowed, changed/missing/duplicate identities are rejected. Twenty-one Python tests cover these checks, including the observed boot-origin mismatch. This does not establish human installation approval or replace new-ESP firmware inspection and backup.

### Earlier post-reboot observations

The user reports completing the UEFI-only installation and recovery steps for an environment named `guix`. Linux now shows a new APFS stub (UUID `a6b590a8-34fa-4b45-986c-870ba40c4c37`, PARTUUID `96b406e5-913c-40aa-9244-ffd3a0c5e6a4`) and ESP labeled `EFI - GUIX` (UUID `77C4-10EC`, PARTUUID `5408cbd2-dc6c-49c6-bee9-c51c5f3a29fc`). Pairing, firmware contents and an initial new-ESP backup still require verification before Guix writes anything. Old partition UUIDs remain present; numbering shifted. The protected NixOS ESP is now `/dev/nvme0n1p7`, not partition 5.

After reboot, `/run/current-system` and the system profile point to `/nix/store/fkj2yqb4y1zjv0lavs01kms9s00sbrb7-nixos-system-asahi-nix-26.11.20260831.34ab990`, which has no Guix units. Thus `systemctl start guix-daemon.socket` reports **unit not found**, rather than a missing-SSD mount error. The T7 has since been reconnected and both filesystem UUIDs match, but neither partition is mounted. Do not format.

The previously working bootstrap closure `/nix/store/0pcx033gw2jw2dyk99jxszjy46n2l6xa-nixos-system-asahi-nix-26.11.20260831.34ab990` remains available. A temporary `test` activation would restore its live configuration without installing a boot generation. It also reapplies Home Manager changes (including Herdr 0.8.2 → 0.9.0), Flatpak service/timer changes and Guix's Avahi/profile integration, not just the daemon. No reactivation has been performed by the agent. Review/approval is required before restoring that whole configuration; alternatively build a narrower bootstrap-only configuration.

## 2026-09-15 — bootstrap resumed, component builds successful

Read this before resuming the [installation plan](INSTALLATION-PLAN.md). The original preparation documents contain historical observations from before formatting/activation. **Do not rerun `phase1-ssd.sh` or the root-level `rofl.sh`: the SSD is already formatted.**

### Confirmed live state

- Samsung PSSD T7, serial `S7MLNL0L447791L`, stable ID `/dev/disk/by-id/usb-Samsung_PSSD_T7_S7MLNL0L447791L-0:0`, 931.5 GiB.
- Bootstrap: 96 GiB ext4, UUID `29257cb6-83ea-432d-9491-f7c1a16d642b`, PARTUUID `b16dc694-3161-4781-9b2a-8069e9300836`.
- Native root: 835.5 GiB ext4, UUID `c694c1fc-a241-459a-a60d-1c829f1b9c30`, PARTUUID `0f2eb3d1-bd42-440d-aa93-a63ec8f79033`; not mounted or initialized in this session.
- The NixOS host imports `guix-bootstrap`, and its live service configuration matches the saved bootstrap UUID. The journal confirms it ran on September 14; it was stopped and its mounts released on September 15 at 07:53. The earlier activation method was not established here.
- After reconnecting the SSD, the user ran `sudo systemctl start guix-daemon.socket`. `/var/lib/guix-bootstrap`, `/gnu`, `/var/guix`, and the read-only host `/gnu/store` mount all resolve to partition 1. The daemon has its writable store view; `guix build hello` succeeds. Both socket and service are now active.
- Workspace after component builds: about 9.3 GiB used, 80 GiB available. Internal root remains about 37 GiB available. Guix build storage is on the SSD; user checkout caches and these small logs remain on the internal filesystem.

### Authenticated runtime and realized components

`guix time-machine -C channels.scm -- describe -f channels` succeeds with the exact committed Guix and Asahi pins. Guix's authentication cache records both pinned commits as previously authenticated. Authentication was not disabled. The warnings that these channels are “not trusted” concern the newer local trusted-channel list, separate from signature authentication; no trusted-channel file was changed to suppress them.

The following commands completed successfully through that pinned time-machine, with normal grafts, `--no-offload --cores=4 --max-jobs=1`:

| Component | `guix build -e` expression / result |
| --- | --- |
| Kernel | `(@ (asahi guix packages linux) asahi-linux)` → `/gnu/store/ifl1v7zj2siw1189whwhx1cimdfqw7bp-asahi-linux-7.0.13-1` |
| Desktop packages | `(begin (use-modules (gnu system) (gnu packages version-control) (gnu packages vim) (asahi guix systems sway)) (cons* git neovim (operating-system-packages asahi-sway-os)))` → 82 output paths verified present |
| Bootloader package | `(begin (use-modules (gnu bootloader) (gnu bootloader m1n1)) (bootloader-package m1n1-u-boot-grub-bootloader))` → `/gnu/store/izv0hvqkbvn9w6jdqdy0n1chj9vrdpzf-efi-bootloader-profile` |

Substitutes were available for the major components. “Realized” means the store outputs were downloaded/built successfully, not that everything was compiled locally. The kernel's `.config` confirms `CONFIG_ARM64_16K_PAGES=y`, built-in SCSI disk, xHCI, USB-storage and ext4 support; UAS is a module. This is not a USB-root boot test or verification of the final initrd.

The bootloader output is only a store profile: **no bootloader installer was executed and no ESP was written**. Desktop package realization does not build all system/Home service closures.

Validation passed:

- All 12 Python safety tests.
- `tests/evaluate.scm` using the actual pinned time-machine: channel pins, OS records, locked accounts, service graph, audio ownership and boot target.

Ignored `local/` artifacts include `resumed-inventory.json`, `time-machine-channels.scm`, `*-build.log`, `*-outputs.txt`, `pinned-evaluation.log`, and kernel/bootloader GC-root symlinks. The desktop multi-package `-r` invocation reuses root names, so its symlinks do not protect every desktop output; the output list is an inventory, not a GC root. No garbage collection was run.

### Next gates — not completed

1. The user subsequently confirmed independent backups of important macOS/NixOS data and the complete existing ESP. This is user-reported confirmation, not an agent-performed restore test. macOS access was demonstrated by the supplied inventory; recovery access remains untested here.
2. The supplied macOS inventory confirms 11.7 GB of unallocated space after `disk0s2` (Macintosh HD). The existing `disk0s3` and `disk0s4` stubs are named Asahi Alarm Minimal and Fedora Linux Minimal respectively; neither is approved for reuse or deletion. The user approved a new UEFI-only environment named Guix SSD in the free gap, approximately 3 GB, preserving all existing partitions and without resizing macOS, subject to reviewing the installer's actual allocation before it writes. The installer temporarily changing the startup default was disclosed. Creation has not yet been confirmed.
3. Record the new environment's pairing, ESP UUID and PARTUUID, back up its initial contents, and verify firmware. The protected NixOS ESP remains UUID `5CDF-1DF4`, PARTUUID `ea8adc5b-ec2d-4df1-913b-f0f05ff85367`.
4. Saved-versus-live identity checks are now implemented and unit-tested as described above. Live destination-mount validation and human review of the schema-2 snapshot are still pending; old snapshots require regeneration.
5. Generate the final configuration using the actual new ESP identity; build the complete OS/initrd/service closure. Then request approval for the exact `guix system init` destinations. Password initialization and hardware/recovery acceptance still follow.

No internal partition changes, boot-policy/default changes, Guix system initialization, firmware copies, or reboot were performed in this session.

### Pausing and resuming

Leave the T7 attached while the daemon/mounts are active. Before unplugging, finish builds and use a normal shutdown, or deliberately stop the socket/service and release all workspace mounts as described in the bootstrap README. Do not hot-unplug it.

On resumption, compare the live serial, UUIDs and PARTUUIDs to this record before mounting. If the module is already active and the expected SSD is present, `sudo systemctl start guix-daemon.socket` mounts its dependencies; a client request starts the daemon. Verify the mount sources and run `guix build hello` before larger work. Do not reformat or reactivate NixOS just to resume.
