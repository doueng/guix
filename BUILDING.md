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

For a modest speed/memory tradeoff, try this process-local setting:

```sh
env GC_FREE_SPACE_DIVISOR=1 make build
```

It lets Guile's collector use more memory before collecting. The observed
saving was about one second, with ~200 MiB more peak RSS. Optionally also set
`GC_INITIAL_HEAP_SIZE=268435456` (256 MiB); it saved another ~0.4 s in one run.
Defaults remain unchanged pending repeated measurements under the user's
normal workload. Do not reduce GC marker threads on this host.

## Cold builds are different

The running daemon and `modules/engstrand/asahi.scm` use `--max-jobs=1 --cores=4`.
These limit package builds, not the client's warm system evaluation.
`make -j8 build` does not increase Guix build parallelism: the Make target
contains one Guix command.

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

After reviewing those introductions, explicitly list the approved channels in
`${XDG_CONFIG_HOME:-$HOME/.config}/guix/trusted-channels.scm`, using the same
list format as this repository's `channels.scm`. If no trust file exists,
copying the reviewed file there is sufficient. If one exists, merge the
approved entries without dropping other trusted channels. This trusts the
channel signing identities, not just these particular commits; the build's
`-C channels.scm` still supplies the pins.

Tested with a temporary `XDG_CONFIG_HOME` under ignored `local/`: the warning
disappeared and pinned `describe` succeeded, without disabling authentication.
The real user/root trust configurations were **not changed**. A later sudo
invocation may need its own reviewed trust configuration.

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
compilation, reconfiguration, or hardware acceptance.
