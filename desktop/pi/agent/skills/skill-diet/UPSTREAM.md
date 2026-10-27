# skill-diet for bbh and Pi

Upstream is https://github.com/nicobailon/skill-diet, MIT. The baseline is 0.1.0 at `5f41df1220eb5759c413390206f1b9e6c63be754`. `SKILL.md`, `references/`, `scripts/` and `LICENSE` are copied unchanged, except one sentence in `references/adapter.md` that points to the bbh adapter. The Claude Code plugin files, `package.json`, `tools/` and the README were left out because nothing here loads them.

Stow links this folder into `~/.pi/agent/skills/`, where bbh's `init.clj` and Pi both find it.

## The bbh adapter

`adapters/bbh` runs one eval session through `bbh acp`. It starts from the user's `init.clj` and appends an override that limits `skill-roots` to the skill under test, drops every AGENTS.md, and turns poteto-mode off. Conversations go under the run's out-dir. `EVAL_MODEL` sets `BBH_MODEL`. Each `clojure` tool call is recorded as one call whose `command` is the code, because reads and shell commands appear inside it. bbh's input tokens include cache reads and writes, so the adapter subtracts them.

## Updating

1. Refresh the checkout with the librarian skill, and record the new commit and version here.
2. Copy the four upstream paths again. Then put the bbh sentence back into `references/adapter.md`.
3. Run `make skill-diet-check`, then a live calibration: put the adapter in an eval folder that holds `sk/before/<skill>/` and run `node scripts/eval.mjs calibrate <dir>`.
