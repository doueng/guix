# VENT

Feedback log. Repeated/systemic workflow friction that should become future automation, docs, or workflow fixes.

## 26-09-23 12:41 — confusing_docs

During the Guix setup review, .agents/skills/guix/references.md linked offline manuals as guix-manual.md and guix-cookbook.md beside itself, but they actually live under references/, as SKILL.md correctly states. The failed lookup required locating the files manually. Fix references.md links to references/guix-manual.md and references/guix-cookbook.md, and add a lightweight local Markdown-link check to prevent future review backtracking.
## 26-09-24 13:53 — tool_error

Two jj history operations opened the configured interactive Neovim/builtin editor and stalled or timed out in the non-TTY shell: an initial split without -m and a squash with overlapping file edits. Workarounds were passing -m and keeping the overlapping test cleanup as a separate change. It would help if agent tooling documented/selected a noninteractive jj diff editor or warned clearly before commands needing a TTY.
## 26-09-24 20:58 — tool_error

Librarian-managed checkout guard rejected two read-only `git -C ... tag --sort=-version:refname` inspections (one in a compound ls/rg/git command), despite the skill permitting read-only inspection. I worked around it by reading the checkout's Readme.md, Cargo.toml and GitHub release metadata instead. Permit read-only git metadata queries in managed checkouts, or document their unsupported status, to avoid repeating this workaround.
## 26-09-24 23:21 — inconsistent_cli_output

Herdr plugin subcommands expose JSON inconsistently: `plugin list` defaults to human output and needs `--json`, while `plugin action list` defaults to JSON and rejects `--json`. I repeatedly retried list/parse commands after jq failures. Consistent JSON defaults or a shared `--json` flag across plugin subcommands would make scripted audits reliable.
