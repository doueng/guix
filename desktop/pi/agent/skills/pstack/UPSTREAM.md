# pstack for Pi

This bundle adapts the MIT-licensed pstack engineering workflows for Pi. Skill files and supporting references stay together so Pi can discover nested `SKILL.md` files and resolve relative references.

Use `/skill:poteto-mode` as the entry point. Pi has no built-in subagent API. When local delegation is available and appropriate, use the installed Herdr workflow; otherwise work directly and state that parallel review was unavailable. Use Pi's active session and configuration paths rather than assuming another agent host's layout. Every skill in this bundle is intended to run within Pi, and platform-specific behavior that has no Pi equivalent is omitted.
