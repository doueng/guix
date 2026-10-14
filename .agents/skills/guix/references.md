# Guix documentation references

## Official references

- [GNU Guix System manual (development version)](https://guix.gnu.org/manual/devel/en/guix.html) — package management, channels, system configuration, services, boot, and command reference.
- [GNU Guix cookbook](https://guix.gnu.org/cookbook/en/guix-cookbook.html) — practical recipes, including system administration, development environments, and packaging.

The development manual can get ahead of this repository's pinned Guix/Asahi channel. For command or Scheme API details, prefer the installed manual below, which matches the pulled Guix.

## Installed documentation

`make pull` installs the manual and cookbook for the pinned Guix commit as Info files in `~/.config/guix/current/share/info/` (`guix.info*.gz` and `guix-cookbook.info*.gz`). They are complete and version-matched, so prefer them to the web pages.

Find the nodes that mention a term, then read one node:

```sh
I=~/.config/guix/current/share/info
zcat $I/guix.info-*.gz | awk -v pat='zram-device-service-type' \
  '/^File: guix.info,  Node: /{n=$0; sub(/.*Node: /,"",n); sub(/,.*/,"",n)} index($0,pat){print n}' | sort -u
info -f $I/guix.info.gz -n 'Linux Services' -o -
info -f $I/guix-cookbook.info.gz -n 'Top' -o -
```

`zcat $I/guix.info-*.gz | rg -n PATTERN` searches the full text. Asahi channel services are not in the manual; read their sources under `~/.config/guix/current/share/guile/site/3.0/asahi/`.
