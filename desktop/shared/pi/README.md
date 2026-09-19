# Shared Pi configuration

The Guix desktop stages these resources into `~/.pi`:

- `agent/prompts/` and `agent/skills/` provide the shared prompts and workflows.
- `agent/extensions/` provides the local jj guard, usage, autoresearch, vent,
  prompt-editor and tokenjuice extensions.
- `agent/keybindings.json` supplies the shared Pi keybindings.

`settings.json` remains managed separately by the Guix staging script so its
Guix Bash path is portable. Pi authentication stays local in
`~/.pi/agent/auth.json` and is never staged.

The extensions use Pi's bundled runtime dependencies. npm packages such as
Plannotator and FFF are intentionally not installed by the system; add them
with `pi install …` when needed.
