Public keys for the remote builder, filled in during phase 2 of
docs/remote-builder-plan.md. Only public halves belong here; this repo is public.

- deploy.pub: your ssh key for root and the prebuild account (~/.ssh/guix-builder.pub)
- offload.pub: the laptop daemon's offload key (/etc/guix/offload/id_ed25519.pub)
- host-key.pub: the builder's ssh host key (ssh-keyscan)
- builder-signing.pub: the builder's /etc/guix/signing-key.pub, for the laptop
- laptop-signing.pub: the laptop's /etc/guix/signing-key.pub, for offload imports
- nixpkgs-builder.pub: the key the builder signs nixpkgs imports with (until phase 7)

modules/engstrand/system/builder.scm skips any file that is missing. The laptop's
asahi.scm trusts builder-signing.pub once it exists, and its activation creates
the offload key, so the next make switch needs no manual guix archive or ssh-keygen.
