# Build speed and warnings

## Measured warm builds

Investigation on the native M1/16 GiB Guix host, with the committed channel
pins and all build outputs already present:

| Invocation | Wall time | Peak client RSS |
| --- | ---: | ---: |
| `make build` | 11.46–12.67 s | ~577 MiB |
| `GC_MARKERS=1 make build` | 16.60–16.85 s | ~576 MiB |
| `GC_MARKERS=4 make build` | 12.17 s | ~578 MiB |
| `GC_INITIAL_HEAP_SIZE=268435456 make build` | 11.32 s | ~598 MiB |
| `GC_FREE_SPACE_DIVISOR=1 make build` | 10.55 s | ~776 MiB |
| Both heap settings above | 10.18 s | ~780 MiB |

The default and single-marker cases were repeated; other settings are single
exploratory measurements, not statistically established speedups. Timings use
Python's monotonic clock around `make build`; RSS comes from `wait4`, excludes
the daemon, and is converted from KiB to MiB. All ten successful build runs
returned the identical system store path. No activation was performed.

The pinned time-machine profile lookup alone took 0.064 s; lookup plus
`describe -f channels` took 0.114 s. A custom time-machine cache is therefore
not worthwhile for this warm workload. Guix already caches authenticated
channel instances.

A Guile `statprof` run showed most sampled execution in system/Home derivation
lowering, including graft traversal. It reported 13.79 s in GC out of 22.34 s
of profiler time; these instrumented numbers are not wall-time measurements.
String package lookup/module discovery was only a few percent of samples.

`make build` and `make switch` set `GC_FREE_SPACE_DIVISOR=1` for the pinned
Guix/Guile process (switch passes it explicitly after sudo). This lets Guile's
collector use more memory before collecting;
measurements found about a one-second warm-build saving with ~200 MiB more peak
RSS. The setting is process-local and can be overridden for comparison, e.g.:

```sh
GC_FREE_SPACE_DIVISOR=3 make build
```

A paired check after the Makefile change returned the identical system output;
`make build` took 10.50 and 12.02 s with the new default, versus 12.34 and
12.26 s with divisor 3. These are few noisy samples, consistent with (but not
proof of) the earlier measured improvement. `GC_INITIAL_HEAP_SIZE=268435456`
(256 MiB) saved another ~0.4 s in one run, so it remains opt-in. Do not reduce
GC marker threads on this host.

## Reducing `make switch` time

`make switch` now runs one pinned `guix system reconfigure`: Guix builds the
system before activating it. The old build-then-apply workflow evaluated the
system/Home twice even when all outputs were cached. Removing that preliminary
build avoids a full extra client evaluation without a custom Guix driver.

There are no build receipts, source fingerprints, reuse flags or extra shell
confirmation. Invoking `make switch` explicitly requests activation and ESP
writes; sudo may still prompt for a password. `make apply` is an alias.
`make build` remains an optional, non-activating preview, not a prerequisite.
Old receipts under `local/` are ignored.

Only the native Guix/root/ESP identity checks remain in the wrapper, to protect
the independent NixOS boot environment. Guix's own channel authentication,
downgrade checks, storage/initrd checks, generation management, bootloader
installation and service upgrades remain unchanged. This ordering was checked
against pinned Guix `7e74121`, `guix/scripts/system.scm`, `perform-action`.

The GC setting is passed explicitly after sudo and can be overridden with
`GC_FREE_SPACE_DIVISOR=3 make switch`. Offline tests use fake Guix/sudo/hardware
commands to verify a single reconfigure, environment forwarding, disk checks,
and failure propagation without activating anything.

End-to-end savings have not been measured: the timings above cover warm builds,
not activation or bootloader work. On the next approved real switch, separate
sudo waiting from execution and timestamp the reconfigure phases. Switch now
resolves the authenticated pinned profile as the invoking user, canonicalizes
its store path, and runs that exact Guix through sudo, avoiding a separate root
time-machine cache/trust lookup. Do not disable authentication, grafts, safety
checks or bootloader updates merely to improve timings.

For existing live-linked config edits, no switch is needed. For Home-only
package/service changes, use `make home-build` / `make home-apply`.

## Cold builds are different

The running daemon and `modules/engstrand/asahi.scm` use `--max-jobs=1 --cores=4`.
These limit package builds, not the client's warm system evaluation. The custom
Ghostty Zig build now reads Guix's `NIX_BUILD_CORES` through
`parallel-job-count` and passes Zig's `-jN` option, so `--cores` also limits
its internal build jobs. `make -j8 build` does not increase Guix build
parallelism: the Make target contains one Guix command.

When `make dry-run` shows actual source builds, consider a per-invocation
parallelism override rather than changing the daemon. For example, from this
checkout:

```sh
GUIX_BASE=/etc/guix-ssd guix time-machine -C channels.scm -- \
  system build --max-jobs=2 --cores=4 -L modules desktop/system.scm
```

This concurrency setting was **not benchmarked**. Two simultaneous large
Rust/Zig/C++ builds may exhaust memory; the host has no swap. Prefer available
signed substitutes and inspect the dry-run first. Keep normal grafts and
channel authentication enabled.

Existing live-linked Fish, Neovim, Doom, Pi and desktop config contents do not
need a system rebuild. Package/service changes and changes to the generated
link list do. Avoiding an unnecessary build saves more than evaluator tuning.

## Substitute order

`make build`, `make dry-run` and `make switch` pass the cache order explicitly:
Bordeaux, Asahi, then CI. This is a per-invocation override, not a daemon change;
Home commands and other Guix invocations still use their existing defaults.
All three caches remain available, and signing-key authorization is unchanged.
Override the list with `SUBSTITUTE_URLS='URL1 URL2 ...'` if needed.

Pinned Guix's `(guix substitutes)` `lookup-narinfos/diverse` queries caches in
order, passing paths without an authorized hit to the next cache. Bordeaux
first avoids asking Asahi about general packages that Bordeaux can supply.
Asahi-only packages instead incur a Bordeaux miss first. Cached narinfo
responses, server latency and download throughput affect the actual benefit;
no speedup has been measured. A local system/boot-config derivation usually has
no substitute anywhere, so reordering cannot eliminate those misses/builds.

## Warning sources and remedies

### `channel 'asahi' is not trusted`

The host Guix's channel-trust policy warns because Asahi is not among its
trusted channel introductions. This does **not** mean Git signature
authentication was disabled or failed. `channels.scm` supplies authenticated
introductions and exact commits for both channels.

After reviewing those introductions, the approved channels are listed in the
current user's `${XDG_CONFIG_HOME:-$HOME/.config}/guix/trusted-channels.scm`.
This trusts the channel signing identities, not just these particular commits;
the build's `-C channels.scm` still supplies the pins. The current user's pinned
`guix time-machine ... describe -f channels` was verified without the warning
and without disabling authentication. `make switch` now resolves this profile
before sudo and invokes its canonical `bin/guix` directly, so root no longer
repeats time-machine with a different trust configuration. Root's trust files
are not modified. Users without the reviewed trust entry still get the warning;
we do not suppress it or disable channel authentication.

### `libcamera-minimal imported from both … networking … photo`

This is inside the pinned upstream `(gnu packages linux)` module, not an
ambiguous import in our modules. `(gnu packages networking)` exports a
deprecated alias of `(gnu packages photo)`'s `libcamera-minimal`, and Linux
imports both modules.

The proper upstream fix is to exclude that alias from Linux's networking
import (or otherwise select the photo binding). Adopt it through a separately
reviewed Guix pin update or channel patch. Do not mutate `/gnu/store`, globally
change Guile duplicate-binding handling, or filter stderr: those approaches
hide unrelated diagnostics. The warning is retained until that upstream fix
is adopted.

### Kexec `delete` binding warning and `Device or resource busy`

The reported final warnings occur during Guix's optional kexec preparation,
after normal activation and bootloader installation have succeeded. Switch now
passes `--no-kexec` by default, avoiding that generated program and its load
attempt. This is a deliberate feature opt-out, not a kernel fix or global
warning filter. Normal reboot still uses the updated ESP. Do not use
`reboot --kexec` expecting the new system to have been loaded; this option does
not unload an older kexec image either.

To retain Guix's kexec preparation, run `make switch RECONFIGURE_FLAGS=`.
The `delete` binding warning may still occur in other upstream generated
programs; no global duplicate-binding policy was changed.

## Validation and local evidence

- Ten full warm builds succeeded with identical output paths.
- `guile -L modules -s tests/test-desktop-direct.scm`: passed.
- `make eval-desktop`: passed the service/Home and preserved-system checks.
- Raw timings, profiler output and logs: ignored `local/build-investigation/`.

These results cover cached builds only, not first-build downloads, package
compilation, reconfiguration, or hardware acceptance. The current Ghostty job
limit change has been checked against Zig 0.15's build-runner option parser;
it has not yet been measured in a full Ghostty compilation.
