# VENT

Feedback log. Repeated/systemic workflow friction that should become future automation, docs, or workflow fixes.

## 26-09-26 11:50 — herdr_overlay_targeting

Herdr's plugin.pane.open schema exposes target_pane_id for all placements, but overlay placement repeatedly returned invalid_params even with an explicitly focused target pane. I repeatedly tried targeting background panes then worked around it by temporarily focusing a test pane and omitting target_pane_id. Document that overlays only use the UI-active pane, or reject/describe this constraint in the schema and CLI help.
