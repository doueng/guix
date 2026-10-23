# pi-autoresearch Pi extension

Adapted from the npm package [`pi-autoresearch`](https://www.npmjs.com/package/pi-autoresearch) **1.4.0** ([davebcn87/pi-autoresearch](https://github.com/davebcn87/pi-autoresearch), MIT; see `LICENSE`). The original import did not record its source version. 1.4.0 is the closest published release: `compaction.ts`, `hooks.ts` and `jsonl.ts` match it byte for byte.

The package also provides the `autoresearch-create`, `autoresearch-finalize` and `autoresearch-hooks` skills, which are not vendored here.

Local deviations:

- Imports use the `@earendil-works/*` Pi packages and `typebox` instead of `@mariozechner/*` and `@sinclair/typebox`.
- `index.ts` detects Jujutsu as well as Git, hashes the experiment's changed files when a run starts, and refuses to log a result if the benchmark or a later edit changed them. It accepts only `bash autoresearch.sh` or `./autoresearch.sh` as the autoresearch command.
- `shortcuts.ts` opens the fullscreen dashboard with `ctrl+shift+d` rather than `ctrl+shift+f`.

To update: compare a newer package release against 1.4.0, apply the upstream changes while keeping the deviations above, then update the version here.
