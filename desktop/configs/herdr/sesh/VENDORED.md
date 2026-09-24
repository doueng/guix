# Vendored Go dependencies

`vendor.tar.gz` is the output of `go mod vendor` for `herdr-plugin-sesh` v0.7.0.
It is unpacked by the Guix package build so the plugin builds without network access.
`manifest.toml` is the package's Herdr plugin descriptor, installed at the
package root. The live Sesh settings are in `../sesh.toml`.

Source: https://github.com/fullerzz/herdr-plugin-sesh/releases/tag/v0.7.0
