# Remote Guix builder: plan

Status: plan only (T116, W118, 2026-11-09). Nothing was created, switched or bought.
Labels: **[M]** measured here (command or file cited), **[I]** inferred from code or docs, **[G]** guess to confirm.

## Goal

Stop the laptop from building. A Hetzner CAX (aarch64) server on the tailnet builds and serves everything that `make pull`, `make home-apply` and `make switch` need, at the same pinned channels, before the laptop asks. The laptop then only evaluates, downloads and activates.

## What the repo looks like today

- Laptop: 8 cores, 15 GiB RAM plus 8 GiB zram, 32 GB free on / [M: free -g, nproc, df]. So it is 16 GB-class, not 8 GB. The freezes (T108, T111) came mostly from agent processes. Local builds add to them, and earlyoom now guards against them (/home/engstrand/guix/modules/engstrand/system/asahi.scm line 106).
- Channels: guix 704ad8c, asahi b913276 and rde 6821d6f, pinned in /home/engstrand/guix/channels.scm [M]. The guix pin is held, because rde's node.scm breaks on newer guix (/home/engstrand/guix/desktop/bin/updates line 45) [M].
- Daily builds use the **pulled** guix, not time-machine. `make channels-current` refuses to run when the pulled guix differs from channels.scm (/home/engstrand/guix/Makefile line 74). Only `make repro-build` uses `time-machine` [M].
- System: desktop/system.scm and desktop/home.scm both come from `%familiar-config` (/home/engstrand/guix/modules/engstrand/config.scm), an rde config over `make-base-os` in /home/engstrand/guix/modules/engstrand/system/asahi.scm [M].
- Daemon (asahi.scm line 141 onwards): substitutes from bordeaux, then asahi, then ci; `--max-jobs=1 --cores=4 --gc-keep-outputs`; it authorizes the asahi keys and the nixpkgs import key [M].
- Tailscale: a binary package (/home/engstrand/guix/modules/engstrand/packages/tailscale.scm, aarch64 only) and a root Shepherd service `tailscaled` (/home/engstrand/guix/modules/engstrand/features/tailscale.scm line 11, not exported) [M]. Remote access uses ordinary OpenSSH over the tunnel, not Tailscale SSH (/home/engstrand/guix/modules/engstrand/features/remote-access.scm line 8) [M].
- T74's ordering work did not add a `tailscale-online` service. bbh-web, reader and system-design-prep instead listen on loopback, and `tailscale serve` maps them, so their start order does not matter (bbh-web.scm line 13, reader.scm line 11) [M]. This plan follows the same pattern: no daemon gets a Shepherd requirement on Tailscale.
- `updates apply` only edits files (channels.scm, nixpkgs lock, package hashes, ...). It then prints `make pull`, `make home-apply`, `make switch` and `make doom-check` for you to run (desktop/bin/updates line 425) [M].
- Remote: origin is https://github.com/doueng/guix.git. It is **public**, and main@origin dates from 2026-09-18, about 600 commits behind the local `master` [M: jj log]. The builder can't fetch from it. The laptop must push revisions over the tailnet.
- Secrets today: nothing is checked in. `local/` is gitignored (it doesn't exist right now), the nixpkgs import key lives at ~/.config/guix/nixpkgs-bridge/signing-key.sec with only its public half in the repo, and the Tailscale state lives in /var/lib/tailscale.state [M]. There is no secrets manager.

## (b) Asahi packages on a generic aarch64 server

| Item | Where | Builds on a CAX? |
|---|---|---|
| `asahi-linux-keyd` (asahi-linux 7.2.15 plus `CONFIG_INPUT_UINPUT=m`, CONFIG_RUST, rust and bindgen as native inputs) | /home/engstrand/guix/modules/engstrand/system/bootloader.scm line 19, asahi linux.scm line 494 | Yes [I]. It's an ordinary aarch64 derivation. Because it is customized, **no substitute server has it**, so today every kernel bump builds on the laptop. This is the builder's biggest win. |
| `asahi-linux-keyd-ane` (omarchy ANE out-of-tree module), `asahi-dtbs-ane` | bootloader.scm lines 26 and 28 | Yes [I], same reason. |
| `asahi-u-boot-os-prepare` (u-boot plus a local patch), `asahi-m1n1` | bootloader.scm line 34 | Yes [I]. Pure cross-free builds. |
| Firmware | asahi `asahi-firmware-service-type` (systems/base.scm line 87), `asahi-fwextract` | The package is a Python tool and builds anywhere [M: firmware.scm]. The firmware itself is extracted at boot from the ESP, which needs the Mac, but that isn't a build [I]. |
| mesa | Guix mesa 26.0.2; nixpkgs mesa 26.2.6 for /run/opengl-driver | Builds anywhere [I]. |
| Bootloader install (ESP write of m1n1, u-boot and grub), kexec, firmware extraction | `make switch` activation | **Needs the Mac.** These happen at activation, not at build time, so they stay on the laptop [I]. |

Page size: Asahi runs 16K pages and Hetzner 4K. Guix already pins jemalloc to 16K pages on aarch64 (gnu/packages/jemalloc.scm line 69) [M]. Bordeaux builds on 4K hosts too, so the builder is no worse than today's substitutes [I]. Neoverse N1 (CAX) vs M1 doesn't matter, because nothing uses `--tune` (T77/overlays answer: `--tune` picks armv8-a) [M there].

## Architecture

```
laptop (asahi-guix)                         guix-builder (Hetzner CAX31, Guix System)
 guix-daemon --max-jobs=0                    guix-daemon --max-jobs=2 --cores=4
  substitute-urls:                            substitute-urls: bordeaux, asahi, ci
   1 http://guix-builder.<tailnet>:8080       guix-publish :8080 (zstd, cache, bypass 100 MiB)
   2 bordeaux  3 asahi  4 ci                  sshd :22 (OpenSSH; users root, offload, prebuild)
  build-machines: guix-builder (offload)      mcron: 03:00 prebuild, 05:00 gc
  authorizes builder signing key              authorizes laptop signing key (offload imports)
                 \____ tailnet (WireGuard) ____/
Hetzner Cloud Firewall inbound: 41641/udp only (plus 22/tcp from your IP during bootstrap).
Builder nftables: 22 and 8080 accepted only on tailscale0.
```

## Sizing and cost

Prices are from memory of Hetzner's list (EUR/month, excl. VAT, hourly billing) [G]. Check https://www.hetzner.com/cloud/ before buying.

| Type | vCPU / RAM / disk | Price [G] | Fit |
|---|---|---|---|
| CAX21 | 4 / 8 GB / 80 GB | about 6.5 | Fine for the kernel (Rust objects, about 2 to 4 GB peak [G]). Too tight when bordeaux lags and rust or LLVM must build (8 GB with `--cores=4`), and 80 GB fills with two system generations, a speculative bump and the nixpkgs closures. |
| **CAX31** | 8 / 16 GB / 160 GB | about 12.5 | **Recommended.** It roughly halves kernel time. Rust bootstrap fits with 8 GB zram. The disk holds 3 generations plus speculative builds. |
| CAX41 | 16 / 32 GB / 320 GB | about 24.5 | Only if you also want it for other work. |

Add a primary IPv4 (about 0.5 [G]; `guix deploy` with Hetzner uses the public IPv4, hetzner.scm line 334 [M]). Traffic is included. Locations for CAX are fsn1, nbg1 and hel1 [G]. To grow, rescale CAX31 to CAX41 with the server off and keep the disk.

## Secrets

| Secret | Lives | Never in |
|---|---|---|
| Hetzner API token | `GUIX_HETZNER_API_TOKEN`, read from ~/.config/guix-builder/hcloud-token (0600) only by `make builder-create`. Use a project-scoped token in a project that holds only this server, and revoke it after bootstrap. Later deploys go over the tailnet with `managed-host-environment-type`, which needs no token. | repo, store, shell history |
| Tailscale auth key | One-time, pre-approved, tagged `tag:builder`, non-reusable, 1 h expiry. It's pasted once into `tailscale up --auth-key=...` over ssh during bootstrap. After that, the node state in /var/lib/tailscale.state on the builder is the credential. Tagged nodes don't expire. | repo, store, the deploy file |
| Tailscale ACL | Grants laptop (or `autogroup:member`) to `tag:builder:22,8080`, with nothing from the builder to the laptop. Set in the admin console. | n/a |
| Deploy ssh key (you to builder root) | Your existing ~/.ssh key. Its public half goes to Hetzner through `hetzner-configuration ssh-key`, and later to root's authorized keys in the builder OS. | n/a |
| Offload key (laptop daemon to builder `offload`) | /etc/guix/offload/id_ed25519, root 0600, generated on the laptop. The public half goes in the repo at modules/engstrand/builder/offload.pub. | store |
| Prebuild key (you to builder `prebuild`, git-shell only) | Your ~/.ssh key, restricted by the account's shell. | n/a |
| Guix signing keys | Each machine keeps its own /etc/guix/signing-key.sec. The publics go in the repo: modules/engstrand/builder/builder-signing.pub (the laptop trusts it) and laptop-signing.pub (the builder trusts it for offload imports). | the repo for .sec files |
| Builder host key | The public half goes in the repo (for `build-machine host-key` and known_hosts). | n/a |

Risk: once the laptop authorizes the builder's key, root on the builder can feed the laptop any store item. Keep the builder single-purpose and reachable only on the tailnet, with no other services and password login off.

## File changes (sketches against the real files)

### New: modules/engstrand/system/builder.scm (builder OS)

```scheme
(define-module (engstrand system builder)
  #:use-module (engstrand features tailscale)   ; needs %tailscale-service exported
  #:use-module (engstrand packages babashka)
  #:use-module (gnu) #:use-module (gnu machine hetzner)
  #:use-module (gnu services base) #:use-module (gnu services mcron)
  #:use-module (gnu services networking) #:use-module (gnu services ssh)
  #:use-module (gnu services linux) #:use-module (gnu packages ssh)
  #:use-module (gnu packages version-control)
  #:export (%builder-os))

(define %prebuild-job
  #~(job "0 3 * * *" "/var/lib/prebuild/bin/prebuild nightly" #:user "prebuild"))
(define %gc-job
  #~(job "0 5 * * *" "guix gc --free-space=40G"))

(define %builder-os
  (let ((base (make-hetzner-os "cax31")))     ; %hetzner-os-arm is deprecated (hetzner.scm line 199)
    (operating-system
      (inherit base)
      (host-name "guix-builder")
      (users (cons* (user-account (name "offload") (group "users") (home-directory "/home/offload"))
                    (user-account (name "prebuild") (group "users") (home-directory "/var/lib/prebuild")
                                  (shell (file-append git "/bin/git-shell")))
                    %base-user-accounts))
      (packages (cons* git babashka (operating-system-packages base)))
      (services
       (cons* %tailscale-service                 ; same package and service as the laptop
              (service openssh-service-type
                       (openssh-configuration
                        (openssh openssh-sans-x)
                        (permit-root-login 'prohibit-password)
                        (password-authentication? #f)
                        (authorized-keys
                         `(("root" ,(local-file "../builder/you.pub"))
                           ("offload" ,(local-file "../builder/offload.pub"))
                           ("prebuild" ,(local-file "../builder/you.pub"))))))
              (service nftables-service-type    ; 22 and 8080 only on tailscale0; 41641/udp open
                       (nftables-configuration (ruleset (local-file "../builder/nftables.conf"))))
              (service guix-publish-service-type
                       (guix-publish-configuration
                        (host "0.0.0.0") (port 8080)          ; nftables limits it to the tailnet
                        (compression '(("zstd" 9)))
                        (cache "/var/cache/guix/publish")
                        (cache-bypass-threshold (* 100 1024 1024))
                        (ttl (* 30 24 3600)) (negative-ttl 60)))
              (service zram-device-service-type
                       (zram-device-configuration (size "8G") (compression-algorithm 'zstd)))
              (simple-service 'builder-cron mcron-service-type (list %prebuild-job %gc-job))
              (modify-services (operating-system-user-services base)
                (delete openssh-service-type)
                (guix-service-type
                 config => (guix-configuration
                            (inherit config)
                            (substitute-urls (append '("https://bordeaux.guix.gnu.org")
                                                     %asahi-substitute-urls '("https://ci.guix.gnu.org")))
                            (authorized-keys (append %asahi-substitute-keys
                                                     (list (local-file "../builder/laptop-signing.pub"))
                                                     (guix-configuration-authorized-keys config)))
                            (extra-options '("--max-jobs=2" "--cores=4" "--gc-keep-outputs"))))))))))
```

The ssh `authorized-keys` override for root is needed because `make-hetzner-os` takes them from the Hetzner key only at first install [I].
Note that `base`'s file systems (/dev/sda1 ext4, /dev/sda15 vfat) come from hetzner.scm line 115 [M].

### New: desktop/builder.scm (the deploy file, like desktop/system.scm)

```scheme
(use-modules (engstrand system builder) (gnu machine) (gnu machine hetzner) (gnu machine ssh))
(define bootstrap? (getenv "BUILDER_BOOTSTRAP"))
(list (machine
       (operating-system %builder-os)
       (environment (if bootstrap? hetzner-environment-type managed-host-environment-type))
       (configuration
        (if bootstrap?
            (hetzner-configuration
             (server-type "cax31") (location "hel1")
             (ssh-key (string-append (getenv "HOME") "/.ssh/id_ed25519"))
             (build-locally? #f))                ; the server builds its own OS
            (machine-ssh-configuration
             (host-name "guix-builder.tailbf68ad.ts.net") (system "aarch64-linux")
             (user "root") (identity (string-append (getenv "HOME") "/.ssh/id_ed25519"))
             (build-locally? #f)
             (host-key "ssh-ed25519 AAAA... (from phase 2)"))))))
```

### Change: modules/engstrand/features/tailscale.scm

```diff
-  #:export (feature-familiar-tailscale))
+  #:export (feature-familiar-tailscale %tailscale-service))
```

### Change: modules/engstrand/system/asahi.scm, `guix-service-type` (phase 5)

```diff
                      (authorized-keys (append %asahi-substitute-keys
                                               (list (local-file "../nixpkgs/signing-key.pub" "nixpkgs-signing-key.pub"))
+                                              ;; The remote builder; see docs/remote-builder-plan.md.
+                                              (list (local-file "../builder/builder-signing.pub" "builder-signing-key.pub"))
                                               (guix-configuration-authorized-keys config)))
-                     (substitute-urls (append '("https://bordeaux.guix.gnu.org")
+                     (substitute-urls (append '("http://guix-builder.tailbf68ad.ts.net:8080"
+                                                "https://bordeaux.guix.gnu.org")
                                               %asahi-substitute-urls
                                               '("https://ci.guix.gnu.org")))
+                     (build-machines
+                      (list #~(build-machine
+                               (name "guix-builder.tailbf68ad.ts.net")
+                               (systems '("aarch64-linux"))
+                               (user "offload")
+                               (host-key #$%builder-host-key)   ; read from modules/engstrand/builder/host-key.pub
+                               (private-key "/etc/guix/offload/id_ed25519")
+                               (parallel-builds 2)
+                               (overload-threshold 0.8))))
-                     (extra-options '("--max-jobs=1" "--cores=4" "--gc-keep-outputs")))))))))
+                     ;; Build nothing here; offload or substitute.  make ... JOBS=1 overrides.
+                     (extra-options '("--max-jobs=0" "--cores=4" "--gc-keep-outputs")))))))))
```

Since the URL uses MagicDNS, it fails fast when tailscaled is down: the name doesn't resolve, and the daemon falls through to bordeaux. That's why guix-daemon needs no Shepherd requirement on tailscaled, which is T74's lesson [I]. If MagicDNS is flaky at boot, use the builder's 100.x address instead.

### Change: Makefile (new targets; each needs a `make help` line and a checks.edn rule)

```make
BUILDER ?= guix-builder.tailbf68ad.ts.net
JOBS ?=
JOBS_FLAG = $(if $(JOBS),--max-jobs=$(JOBS))   # JOBS=1 builds locally when the builder is down

builder-create:   # once: create the server from desktop/builder.scm (spends money)
	GUIX_HETZNER_API_TOKEN=$$(cat ~/.config/guix-builder/hcloud-token) BUILDER_BOOTSTRAP=1 \
	  "$(GUIX)" deploy -L "$(MODULES)" desktop/builder.scm
builder-deploy:   # reconfigure the builder over the tailnet
	"$(GUIX)" deploy -L "$(MODULES)" desktop/builder.scm
prebuild:         # push @ and build system, home and pull on the builder; wait
	desktop/bin/builder-prebuild push-and-build
builder-check:    # offload test, plus weather for this revision against the builder
	desktop/bin/builder-prebuild check
```

Add `$(JOBS_FLAG)` to the `guix pull`, `system build`, `system reconfigure`, `home build` and `home reconfigure` lines.

### New: desktop/bin/builder-prebuild (bb; runs on both sides)

- Laptop, `push-and-build`: `jj bookmark set prebuild -r @ --allow-backwards`, then `jj git push --remote builder -b prebuild` (remote `builder` = `prebuild@$BUILDER:guix.git`), then `ssh prebuild@$BUILDER prebuild <commit>`, streaming output. git-shell allows only the commands in ~prebuild/git-shell-commands/.
- Builder, `prebuild REV` and `nightly`. A lock file under /var/lib/prebuild/lock keeps runs from overlapping. Each run checks out REV into a worktree, then:

```sh
G="guix time-machine -C channels.scm --"
$G describe                                                   # builds what make pull needs [I]
$G repl -L modules -- desktop/build/nixpkgs/bridge.scm import modules/engstrand/nixpkgs/lock.sexp /var/lib/prebuild/nixpkgs-key.sec
$G system build -L modules desktop/system.scm --root=/var/lib/prebuild/roots/system-$REV
$G home   build -L modules desktop/home.scm   --root=/var/lib/prebuild/roots/home-$REV
guix weather --substitute-urls=http://localhost:8080 $(guix gc -R roots/system-$REV roots/home-$REV) >/dev/null  # bakes publish's cache
```

  It keeps the last 3 revisions' roots and drops older ones, then writes /var/lib/prebuild/status.edn ({:rev :ok? :log}), which `builder-check` reads.
- `nightly` builds the last pushed `prebuild` again, which is a no-op when nothing changed. It then builds a **speculative bump**: channels.scm rewritten to the branch heads with the holds from `updates` respected, and `nixpkgs-lock` at the latest nixpkgs-unstable (the `nix eval` runs here, not on the laptop). So when you run `updates apply` in the morning, the bump is usually already built. The prebuild script has to share the `channel-holds` table, so move it out of desktop/bin/updates line 45 into an edn file that both scripts read.

Why time-machine on the builder and `guix pull` on the laptop agree: both build the same channel-instance derivations for the same commits, and `make repro-build` already relies on this [I]. Phase 4 verifies it with `guix weather`.

### Change: desktop/bin/updates (point c)

```diff
-(def rebuild-steps ["make pull" "make home-apply" "make switch" "make doom-check"])
+(def rebuild-steps ["make prebuild" "make pull" "make home-apply" "make switch" "make doom-check"])
...
-   "channel" {... :then #{"make pull" "make home-apply" "make switch"}}
+   "channel" {... :then #{"make prebuild" "make pull" "make home-apply" "make switch"}}
   (likewise guix, nixpkgs, github)
```

Optionally, `apply` runs `make prebuild` itself when channel, guix, nixpkgs or github changed, so the printed steps start at `make pull`. Either way the builder has built the exact edited tree before the laptop pulls, which avoids channel skew. `updates refresh` (nixpkgs `versions`, a `nix eval`) can run as `ssh prebuild@builder prebuild versions` too.

## Bootstrap order

Phase 1 (repo only, no spend; this is what I ask you to approve)
1. Export `%tailscale-service`. Add modules/engstrand/system/builder.scm, desktop/builder.scm, modules/engstrand/builder/{nftables.conf,you.pub} and desktop/bin/builder-prebuild, plus the Makefile targets, help lines and checks.edn rules (lint only, plus `guix repl` loading the module; no builds).
2. `make lint`, then `guix system build -d -L modules -e '(@ (engstrand system builder) %builder-os)'`. It evaluates the builder OS and prints its derivation without building [I].

Phase 2 (you; spends about 12.5 EUR/month)
3. Create a Hetzner project and a token, and save it to ~/.config/guix-builder/hcloud-token (0600). Create the Cloud Firewall: 41641/udp and ICMP in, plus 22/tcp from your current IP.
4. `make builder-create`. `guix deploy` creates the CAX31 and installs Guix through rescue, and the server builds its own system (`build-locally? #f`).
5. `ssh root@<ipv4> tailscale up --auth-key=<one-time tagged key> --hostname=guix-builder`. Set the ACL. Then remove 22/tcp from the Cloud Firewall.
6. Record `ssh-keyscan guix-builder.tailbf68ad.ts.net` into modules/engstrand/builder/host-key.pub. Copy the builder's /etc/guix/signing-key.pub into builder-signing.pub, and the laptop's into laptop-signing.pub. Run `sudo bash -c 'umask 077; mkdir -p /etc/guix/offload; ssh-keygen -t ed25519 -N "" -f /etc/guix/offload/id_ed25519'` on the laptop and commit the .pub. Generate the builder's nixpkgs import key on the builder.
7. `make builder-deploy`, now over the tailnet (managed host), so the builder gets the users, keys, publish, nftables and mcron.

Phase 3 (test without touching the laptop daemon)
8. `curl http://guix-builder.tailbf68ad.ts.net:8080/nix-cache-info` from the laptop works. The same from a non-tailnet host times out.
9. `guix offload test /tmp/machines.scm` with the same `build-machine` written to a file. It needs `sudo` for the root key [I].

Phase 4 (prebuild and trial)
10. Add the git remote, then `make prebuild`. The first run builds the kernel and anything bordeaux lacks.
11. `sudo guix archive --authorize < modules/engstrand/builder/builder-signing.pub`, a one-off like `make nixpkgs-authorize`.
12. `guix system build -L modules desktop/system.scm --dry-run --substitute-urls='http://guix-builder.tailbf68ad.ts.net:8080 https://bordeaux.guix.gnu.org'` lists **no** "would be built" lines. Do the same with `guix home build`.
13. `guix system build --max-jobs=0 --substitute-urls=... -L modules desktop/system.scm`, a client-side max-jobs=0 with the daemon unchanged. This tests the risk under Failure modes: local-build derivations with max-jobs=0.

Phase 5 (switch the laptop; you run make switch)
14. Apply the asahi.scm diff, run `make switch`, then `sudo herd status guix-daemon` and `ps` to see `--max-jobs=0` and the builder URL.

Phase 6: the `updates` integration and the nightly speculative bump.
Phase 7: drop the nixpkgs bridge (decided 2026-11-09; see below). Until it lands, the prebuild keeps its `nixpkgs import` line.

## Verification

- `guix offload test` passes, and `guix offload status` shows the builder's load.
- `guix weather --substitute-urls=http://guix-builder...:8080` gives 100% for the system and home closures of the pushed revision.
- `make switch` and `make home-apply` after a channel bump print only "substitutes" (downloads), with no "would be built" lines and no building lines in `/var/log/guix-daemon.log`. The earlyoom log stays quiet.
- Builder down (`ssh root@builder herd stop guix-publish`): `make switch` still succeeds when bordeaux has everything, and fails fast with "unable to start any build" when it doesn't, rather than building.

## Rollback

- Fast: `make switch JOBS=1` (client `--max-jobs=1`) builds locally once without a config change. That client override needs checking [I: guix/scripts/build.scm line 228 sends `build-max-jobs` only when given, and the daemon is expected to honor it].
- Full: revert the asahi.scm diff (restore `--max-jobs=1`, drop `build-machines` and the URL; the key can stay), then `make switch`, or boot the previous system generation from grub.
- Builder: `ssh root@guix-builder guix system roll-back`. To remove it entirely, deploy once with `(delete? #t)` in `hetzner-configuration` (hetzner.scm line 263) [M] and revert the laptop first.

## Failure modes

| Failure | Effect | Mitigation |
|---|---|---|
| Builder down or tailscale down | Lookups to the builder fail (DNS fails at once without MagicDNS; with tailscale up and the builder off, each connect may wait for a timeout [G]). Substitutes then come from bordeaux, asahi and ci. Anything only the builder has, such as the custom kernel, fails with "unable to start any build". | `make switch JOBS=1` for a one-off local build. Keep bordeaux next in the list. Measure the timeout in phase 4 and use the 100.x IP if DNS fails slowly. |
| `--max-jobs=0` and `local-build?` derivations (profiles, unions, the trivial `nixpkgs-*` wrappers) | Daemon semantics: these prefer local builds, so with 0 slots they might fail instead of offloading [G]. | Phase 4 step 13 tests it before any config change. In practice they are substitutable from the builder after a prebuild. If it bites, fall back to the daemon at `--max-jobs=1` plus offload: the hook takes non-local builds first, and the local builds left over are only small ones. |
| Offload hangs (ssh stall, builder overloaded) | `make switch` hangs. | `overload-threshold 0.8`, sshd ClientAlive settings on the builder, daemon `--timeout=21600 --max-silent-time=3600`, and `sudo herd restart guix-daemon` (consider adding it to `%restartable-services` in agent-control.scm line 42). |
| publish 404 while baking | The laptop gets a 404 for a large item it asked for during the bake. The client caches a miss for publish's `negative-ttl`. | Prebuild bakes before you pull (the weather step). `cache-bypass-threshold` 100 MiB serves smaller items at once. `negative-ttl 60`. A miss only costs an offload, which finds the output already on the builder. |
| Channel skew (laptop pulls before the builder built that tree) | Local builds, or failures under max-jobs=0. | `make prebuild` comes first in the updates steps. Optionally, `make pull` checks `status.edn` for `@`'s commit and warns. |
| Disk full on the builder | Builds fail. | Nightly `guix gc --free-space=40G`, GC roots only for the last 3 revisions, and an alert in `builder-check`. |
| Builder compromise | It can push store items to the laptop. | Tailnet only, nftables, no passwords, single purpose, and the key can be revoked by removing one line in asahi.scm. |

Not covered: out-of-Guix builds (`make stow` with pi-sci, pierre-diff-ansi and bun, dusk with cargo, kanata-sim, doom sync). They still run on the laptop.

## (e) The nixpkgs bridge: move it out, or drop it?

**Decision (2026-11-09): drop it (Option B).** Option A stays below for the record.

What it is [M]: modules/engstrand/nixpkgs.scm (175 lines), modules/engstrand/nixpkgs/bridge.scm (431), lock.sexp (4946), packages.sexp (26 attrs), signing-key.pub, desktop/build/nixpkgs/bridge.scm (CLI), docs/nixpkgs-bridge/design.html, the Make targets nixpkgs-lock, -key, -authorize, -import and -verify, and the `nixpkgs` source in updates. `lock` and `versions` run `nix eval` through `guix shell nix` (bridge.scm line 156). `import` copies NARs from cache.nixos.org into /gnu/store, rewriting /nix/store and keeping each hash, and the gexp compiler refuses to evaluate when an item is missing (nixpkgs.scm line 74). Users: shell.scm (fish fzf zoxide ripgrep fd git neovim), development.scm (13 tools), herdr.scm (jujutsu), desktop.scm (ghostty, mesa, locales), dotfiles.scm (LOCALE_ARCHIVE) and hex.scm (Asahi Vulkan ICD from nixpkgs mesa).

### Option A: move it into its own repo, consumed as a channel

Following T67 (wf run /home/engstrand/.local/state/bbh/workflows/20261106-152119-628-investigation, recommendation "pinned Guix package + local checkout", with a channel judged overkill **for bbh**): the bridge differs, because it *is* Guix modules, so a channel fits.

1. New repo ~/projects/guix-nixpkgs (jj). `git filter-repo --path modules/engstrand/nixpkgs.scm --path modules/engstrand/nixpkgs/ --path desktop/build/nixpkgs/ --path docs/nixpkgs-bridge/` keeps the history. Rename the modules to `(nixpkgs-bridge ...)` and keep `(engstrand nixpkgs)` as a one-line re-export in guix for a transition. Add .guix-channel, a channel introduction (sign commits with your key) and a LICENSE.
2. Turn the CLI into a Guix extension, guix/extensions/nixpkgs.scm, so `guix nixpkgs lock|versions|import|verify|key` works from any pulled Guix that has the channel [I: Guix loads `(guix extensions ...)` from channels]. The Makefile targets become one-line wrappers.
3. Per-user data stays in guix: packages.sexp and lock.sexp go in modules/engstrand/nixpkgs-lock/ and are passed to `nixpkgs-package` through a parameter or `#:lock`. Bumping a nixpkgs revision then stays one atomic guix commit, and only bridge *code* changes need a channel bump. This avoids T67's main con, cross-repo commits, for the frequent case (lock bumps).
4. channels.scm gains a `nixpkgs-bridge` channel pinned by commit. updates' `channel` source bumps it like the others.
5. Builder role: the prebuild runs `guix time-machine -C channels.scm -- nixpkgs import` and `nixpkgs lock` (the nightly speculative bump). Then **change the gexp compiler (nixpkgs.scm line 71) from `valid-path?` to `ensure-path`/`build-things`**, so the laptop *substitutes* the imported items from the builder (they are signed by the builder's key on publish) instead of importing locally. The laptop no longer needs `make nixpkgs-import`, curl to cache.nixos.org, or its own nixpkgs key, and the builder is the only importer. When the builder is down, `make nixpkgs-import` on the laptop is the fallback, unchanged.
6. T77's aarch64 cache gaps (attrs uncached on cache.nixos.org; the lock keeps the earlier entry, bridge.scm line 195): the builder could later run `nix build` for an uncached attr and import the result. That's an open question, not in scope.

Cost: one more channel and its introduction, a pin bump per bridge-code change (bridge code changed rarely; most churn is lock bumps, which stay local [I]), and moving 4 Makefile targets.

### Option B: drop it

What each package becomes [M: `guix repl` find-packages-by-name at guix 704ad8c, versus lock.sexp]:

| nixpkgs attr (locked) | Guix (pinned) | Replacement |
|---|---|---|
| fish 4.9.3, fzf 0.74.5, zoxide 0.10.0, ripgrep 15.2.0, fd 10.5.1, git 2.56.0, neovim 0.12.6 | 4.7.1, 0.74.2, 0.9.9, 15.2.0, 10.5.0, 2.55.0, 0.12.1 | Guix; minor version lag only |
| jujutsu 0.46.0 | 0.41.0 | Guix is 5 minor versions behind. Check whether herdr, jjui or bbh use newer jj templates or revsets. Otherwise a custom `jujutsu` from the release binary (like tailscale.scm) or `--with-latest` built **on the builder** |
| clojure, curl, dasel, file, fnlfmt, gopls, hyperfine, nixfmt, shellcheck, shfmt, tree-sitter, typst, uv | all present (some a few versions older, e.g. hyperfine 1.20 vs 2.0, uv 0.10 vs 0.13) | Guix |
| python314 3.14.8 | python 3.12.12 | Guix python, or uv-managed python for 3.14 |
| ty 0.0.95 | **missing** | Binary package from GitHub releases (copy-build-system, like tailscale.scm), or `uv tool install ty` |
| ghostty 1.4.0 | **missing** | Custom package. Zig builds are heavy, so the builder builds it, or pin the upstream aarch64 tarball/AppImage if one exists [G] |
| mesa 26.2.6 for /run/opengl-driver | 26.0.2 | Only nixpkgs GUI programs (ghostty) and hex.scm read /run/opengl-driver. With ghostty from Guix, use Guix mesa (check that it has the asahi Vulkan driver) and point hex.scm's VK_DRIVER_FILES at it [I] |
| glibcLocalesUtf8 | n/a | Only needed for nix binaries, so drop it with them |

Dropping removes about 5.6k lines, 5 Makefile targets, a signing key, `nix eval` memory spikes and the cache.nixos.org dependency. You lose the versions that lead nixpkgs-unstable, most visibly jj (0.46 vs 0.41) and ty and ghostty, which need custom packages.

### Drop steps (phase 7)

1. Before switching, add custom packages for ty (a GitHub release binary, copy-build-system like tailscale.scm) and ghostty (built on the builder, or an upstream aarch64 binary if one exists [G]). Decide jj: Guix 0.41, or a release-binary `jujutsu` package. First check herdr, jjui and bbh for features newer than 0.41.
2. In shell.scm, development.scm, herdr.scm and desktop.scm, replace `(nixpkgs-package "x")` with `specification->package` or the custom packages. Drop `nixpkgs-system-services`, the LOCALE_ARCHIVE in dotfiles.scm, and the nixpkgs key in asahi.scm.
3. In hex.scm, point VK_DRIVER_FILES at Guix mesa's asahi ICD, after confirming Guix mesa ships it [I].
4. Delete modules/engstrand/nixpkgs.scm, modules/engstrand/nixpkgs/, desktop/build/nixpkgs/ and docs/nixpkgs-bridge/. Remove the five nixpkgs-* Make targets and the `nixpkgs-import` prerequisites of build, switch, home-build and home-apply. Remove the `nixpkgs` source from desktop/bin/updates and its test in desktop/build/updates/test.bb, and update checks.edn.
5. Verify: `make prebuild`, then `make home-apply` and `make switch` with nothing built locally. Check that ghostty renders, hex finds Vulkan, and fish, jj and nvim start. Remove ~/.config/guix/nixpkgs-bridge and ~/.cache/nixpkgs-bridge afterwards.

My earlier lean, kept for the record: **A** if you value the newest jj, ty and ghostty (the builder makes A cheaper, since it becomes the only importer). **B** if lagging versions are fine, because it's simpler and nothing on the laptop touches Nix. Your call.

## Open questions

1. Move the nixpkgs bridge out, or drop it?
2. CAX31 in hel1 (or fsn1 or nbg1)? Are about 13 EUR/month plus VAT acceptable?
3. Do you want the nightly speculative bump (it costs builder time and disk), or prebuilds only on `make prebuild`?
4. Should `updates apply` run `make prebuild` itself, or just list it first?
5. Should `guix-daemon` go into `%restartable-services` for the agent (offload hangs)?
6. Is the tailnet ACL edited by hand in the admin console, or kept as a file somewhere (outside this public repo)?
