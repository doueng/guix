# Guix documentation references

## Official references

- [GNU Guix System manual (development version)](https://guix.gnu.org/manual/devel/en/guix.html) — package management, channels, system configuration, services, boot, and command reference.
- [GNU Guix cookbook](https://guix.gnu.org/cookbook/en/guix-cookbook.html) — practical recipes, including system administration, development environments, and packaging.

The development manual can get ahead of this repository's pinned Guix/Asahi channel. For command or Scheme API details, first check `guix --version` and prefer documentation matching the installed version/channel.

## Local/offline documentation

The converted Markdown copies are stored beside this file:

- [`guix-manual.md`](guix-manual.md) — complete reference manual, development version.
- [`guix-cookbook.md`](guix-cookbook.md) — complete cookbook.

These are generated snapshots, not hand-maintained extracts. The manual snapshot is several megabytes and may lag upstream; use its source URL above when the latest behavior matters. This repository pins its own Guix/Asahi channel, so always check the installed version and configuration context before relying on development-version documentation.

If installed documentation is more appropriate, try `info guix` or `info '(guix)Top'`. Inside Info, use `s` to search for a term, `m` to select a menu item, and `u` to go up a node. To locate installed Info files without changing the system:

```sh
find /gnu/store -path '*/share/info/guix*.info*' -print 2>/dev/null
```

The Markdown files were converted from the official HTML with Pandoc (GFM output, native HTML div/span handling) and have source URLs and conversion dates at the top. To refresh, download those URLs and convert the main `top-level-extent` content with Pandoc; exclude site navigation before conversion so it does not swamp the document.
