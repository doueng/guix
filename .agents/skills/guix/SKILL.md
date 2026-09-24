---
name: guix
description: Help with GNU Guix package management, channels, system configuration, services, Scheme, and troubleshooting.
---

# GNU Guix

Use the [GNU Guix System manual](https://guix.gnu.org/manual/devel/en/guix.html) and [cookbook](https://guix.gnu.org/cookbook/en/guix-cookbook.html) as primary references. For offline documentation, see [`references.md`](references.md) and the local manual and cookbook snapshots in `references/`.

## Guidance

- Check `guix --version` and the configured channels before relying on version-sensitive commands, APIs, or documentation. The development manual may differ from the Guix and Asahi channel versions used here.
- This repository's Guix Scheme modules are in `modules/`. Evaluate them with Guix and the pinned Asahi channel available; do not assume a generic Guix environment has the required packages or channel definitions.
- Prefer read-only or dry-run commands when inspecting configurations. Do not run system-changing commands such as `guix system reconfigure` or `guix system init` unless explicitly requested.
- When troubleshooting, inspect the relevant configuration and command output, then consult the matching manual or cookbook section before proposing changes.
