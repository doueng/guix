# Herdr plugins

Both plugins are Babashka ports of MIT-licensed upstream Herdr plugins. They keep the upstream plugin ids and `manifest.toml` descriptors, so Herdr treats them as the same plugins. Each directory carries its upstream `LICENSE`, which Guix's standard `install-license-files` phase installs under `share/doc/`.

- `sesh/` ports [fullerzz/herdr-plugin-sesh](https://github.com/fullerzz/herdr-plugin-sesh), written in Go. It replaced a vendored build of release v0.7.0.
- `tiny-fingers/` ports [hotchpotch/herdr-tiny-fingers](https://github.com/hotchpotch/herdr-tiny-fingers), written in Rust.

`modules/engstrand/packages/herdr.scm` builds both with `babashka-herdr-plugin`, which runs each `tests.bb` before installing.
