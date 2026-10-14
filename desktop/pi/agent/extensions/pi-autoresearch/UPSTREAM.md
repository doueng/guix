# pi-autoresearch Pi extension

Adapted from the npm package [`pi-autoresearch`](https://www.npmjs.com/package/pi-autoresearch) **1.4.0** ([davebcn87/pi-autoresearch](https://github.com/davebcn87/pi-autoresearch), MIT; see `LICENSE`). The original import did not record its source version. 1.4.0 is the closest published release: `compaction.ts`, `hooks.ts` and `jsonl.ts` match it byte for byte.

The package also provides the skills in `../../skills/autoresearch-create/`, `../../skills/autoresearch-finalize/` and `../../skills/autoresearch-hooks/`. The hooks skill and `finalize.sh` are unchanged.

Local deviations:

- Imports use the `@earendil-works/*` Pi packages and `typebox` instead of `@mariozechner/*` and `@sinclair/typebox`.
- `index.ts` detects Jujutsu as well as Git, hashes the experiment's changed files when a run starts, and refuses to log a result if the benchmark or a later edit changed them. It accepts only `bash autoresearch.sh` or `./autoresearch.sh` as the autoresearch command.
- `shortcuts.ts` opens the fullscreen dashboard with `ctrl+shift+d` rather than `ctrl+shift+f`.
- `autoresearch-create/SKILL.md` calls the tools through SCI codemode and starts work with `jj new` in Jujutsu repositories. `autoresearch-finalize/SKILL.md` stops in Jujutsu repositories, because `finalize.sh` supports Git only.

To update: compare a newer package release against 1.4.0, apply the upstream changes while keeping the deviations above, then update the version here.
