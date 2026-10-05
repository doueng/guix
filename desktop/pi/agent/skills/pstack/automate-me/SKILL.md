---
name: automate-me
description: "Turn recurring working preferences into a personal mode skill, or update an existing one from evidence and explicit user input."
disable-model-invocation: true
---

# Automate me

Turn recurring working conventions into one concise skill. Ground preferences in available conversation evidence and confirm uncertain or preference-sensitive choices with the user.

## Flow

### 1. Find existing skills and evidence

Look for matching skills under project and personal Pi skill directories, especially `.pi/skills/`, `.agents/skills/`, and `~/.pi/agent/skills/`. Update a matching skill by default. Start fresh only when asked.

Use only session transcripts that are available in the current environment and scoped to the active workspace. Do not search unrelated session stores. If no transcript access exists, say so and rely on the user's explicit statements.

Look for repeated signals about response preferences, autonomy, verification, code and prose discipline, process, and skill use. Treat a single instance as weak evidence. Do not infer a standing preference from a one-off request.

### 2. Confirm intent

Ask concise questions about preference gaps that the evidence cannot settle. Offer concrete alternatives where useful. Do not ask about choices already stated clearly by the user.

### 3. Draft

Use the local `authoring-a-skill` playbook and the `unslop` skill. Preserve an existing skill's useful structure when updating it. Put project skills under `.pi/skills/` or an existing `.agents/skills/` directory and personal skills under `~/.pi/agent/skills/`. When a discovered skill is a symlink, edit its repository source rather than replacing the link.

Use frontmatter with a clear name and description. Keep the skill operational, concise, and scoped to recurring preferences. Reference related skills instead of duplicating their content.

### 4. Review and refine

Show the draft and incorporate the user's feedback. Remove unsupported assumptions and generic advice. Verify the path and frontmatter against a real skill in the target directory.

## Guardrails

- Require repeated evidence before encoding a preference as a standing rule.
- Do not invent a workflow, tool, or path that is unavailable in the active Pi environment.
- A task-specific skill is not a mode skill. Use the relevant task workflow instead.
- A narrow repeatable procedure can be a regular skill without mining broader preferences.
