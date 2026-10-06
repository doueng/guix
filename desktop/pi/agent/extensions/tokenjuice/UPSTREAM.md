# Tokenjuice Pi extension

Vendored from [vincentkoc/tokenjuice](https://github.com/vincentkoc/tokenjuice), release **v0.8.5**, commit `001d5975184ea33f49441ed8724714472e4fca26` (MIT).

`src/core/`, `src/rules/`, `src/types.ts`, `src/hosts/pi/extension/`, and `src/hosts/shared/tool-result.ts` are copied from upstream `src/`. Local deviations:

- `src/core/bounded-jsonl.ts` uses `try/await/catch` in its three directory-close handlers instead of `.close().catch()`, because Pi's bundled runtime may return `undefined` from `Dir.close()` after async iteration. This preserves upstream behavior on Node while preventing compaction errors in Pi.
- `src/hosts/pi/extension/runtime.ts` skips nested tool results identified by `parentToolCallId` (declared in `pi-types.ts`). Codemode scripts need the original bash output and structured result for parsing and filtering. Direct model-facing bash results still receive upstream compaction; nested calls do not consume `/tj raw-next`.

`src/extension.ts` registers upstream's Pi runtime with the upstream default command name `tj`. The package manifest exposes that entry point to Pi.

To update: refresh the upstream checkout, replace those source paths from a specific release, update the version and commit above and in `package.json`, then run `stow --dir=desktop/pi --target="$HOME/.pi/agent" --no-folding --restow agent` to refresh the installed links. Check Pi startup and `/tj status` afterwards.

Upstream v0.8.5 no longer stores raw output for every compacted result or offers the locally added `/tj last-raw` command. Pi's own `fullOutputPath`, when available, is still reported in the compaction notice.
