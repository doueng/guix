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

`make build` now sets `GC_FREE_SPACE_DIVISOR=1` for the pinned Guix/Guile
build process. This lets Guile's collector use more memory before collecting;
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

### What the current workflow repeats

`switch` runs `build` and then `apply`. The receipt in `local/` records a
successful build and rejects changed sources, but `apply` still runs
`guix system reconfigure` on the Scheme configuration, not the recorded store
output. Consequently it repeats system/Home evaluation and derivation lowering;
existing store outputs avoid recompilation, not that client-side work.

This was checked against the installed pinned Guix source (`7e74121`):
`guix/scripts/system.scm`, `perform-action`, lowers the system for both actions.
Reconfigure additionally checks channel ancestry and storage/initrd safety,
builds the boot configuration, activates the system, installs the bootloader,
upgrades Shepherd services, and attempts kexec loading. The CLI has no option to
reconfigure directly from a prebuilt system output while doing all those steps.

The checked-in `switch.log` is **not a successful switch timing**: it stops
before sudo at a stray shell backslash. The current Makefile already contains
the correction. The warm-build measurements above do not measure root's channel
cache, activation, bootloader work, or the complete switch.

### Options, in priority order

1. **Avoid system switching when it is unnecessary.** Existing live-linked
   config contents need no Guix invocation. Use the guarded Home workflow for
   Home-only package/service or link-list changes. This avoids system and ESP
   work entirely; it is not appropriate for OS package/service changes.
2. **Extend the existing GC tuning to apply.** Currently only `build` sets
   `GC_FREE_SPACE_DIVISOR`. The equivalent apply-side change would be
   `sudo env GC_FREE_SPACE_DIVISOR="${GC_FREE_SPACE_DIVISOR:-1}" ...`, placing
   it *after* sudo so it is not lost to environment filtering. This is the
   smallest candidate optimization, with the same memory tradeoff as build.
   Its reconfigure speedup has not been measured; the Makefile is unchanged by
   this investigation.
3. **Reuse a reviewed build through `make apply`.** If `make build` already
   succeeded and was reviewed, use `make apply`, not `make switch`, which
   unconditionally builds again. All existing receipt and disk guards remain.
   A future opt-in receipt-aware switch could automate this, but must reject
   stale/missing outputs and account for inputs outside the current fingerprint
   (for example an external `CONFIG` or environment-dependent configuration).
4. **For a larger redesign, evaluate only once.** A pinned Scheme driver could
   build, pause for review, and then reconfigure using retained OS objects and
   lowering caches. Potential savings are on the scale of one warm evaluation,
   not a demonstrated end-to-end result. This crosses the unprivileged/root
   boundary and relies on Guix internals; it must retain source/output identity,
   channel downgrade checks, disk guards, confirmation, generation management,
   bootloader installation and service upgrades. It is not a safe one-line
   replacement for the existing workflow.

Do not replace reconfigure with the output's activation script or
`switch-generation`: neither is equivalent to a full new-system reconfigure.
Do not disable grafts, authentication or safety checks to improve timings.
`--no-bootloader` changes boot semantics and still lowers the boot configuration;
it is not a transparent optimization. `--no-kexec` is a separate opt-in candidate
only if fast kexec reboot is unwanted and timing shows meaningful overhead.

### Measurement still needed

On the next explicitly approved real switch, record separate build and apply
elapsed times, separating human review/sudo waiting from execution. Timestamp
reconfigure output to distinguish time before `activating system...`, activation,
bootloader completion, service upgrades and kexec loading. Compare apply with
GC divisor 1 versus its default on unchanged inputs; do not perform extra
activations merely to benchmark.

Root's time-machine cache is separate from the user's, so the 0.064 s lookup
above does not establish root's lookup cost. If apply fetches/authenticates
channels repeatedly, investigate that cache first. Resolving the authenticated
pinned profile as the user and invoking its absolute store `bin/guix` via sudo
could avoid a cold root time-machine lookup, but does not remove reconfigure's
ancestry check or evaluation. Do not add a custom cache for an already warm
lookup. No sudo command, activation or bootloader write was performed for this
investigation.

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
and without disabling authentication. A later sudo invocation may use a
separate trust configuration and may need its own reviewed trust entry.

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

## Validation and local evidence

- Ten full warm builds succeeded with identical output paths.
- `guile -L modules -s tests/test-desktop-direct.scm`: passed.
- `make eval-desktop`: passed the service/Home and preserved-system checks.
- Raw timings, profiler output and logs: ignored `local/build-investigation/`.

These results cover cached builds only, not first-build downloads, package
compilation, reconfiguration, or hardware acceptance. The current Ghostty job
limit change has been checked against Zig 0.15's build-runner option parser;
it has not yet been measured in a full Ghostty compilation.
