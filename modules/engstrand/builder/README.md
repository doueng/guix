Public keys for the remote builder, recorded in phase 2 of
docs/remote-builder-plan.md. Only public halves belong here; this repo is public.

- deploy.pub: ssh keys for root and the prebuild account: the unattended deploy key
  (~/.config/guix-builder/deploy_ed25519, no passphrase) and yours (~/.ssh/guix-builder.pub)
- offload.pub: the laptop daemon's offload key (/etc/guix/offload/id_ed25519.pub)
- second-brain.pub: the laptop's key for pushing saves to the second brain
  (~/.config/guix-builder/second-brain_ed25519, no passphrase); sshd runs only the
  receiver for it (modules/engstrand/system/second-brain.scm)
- host-key.pub: the builder's ssh host key (ssh-keyscan)
- builder-signing.pub: the builder's /etc/guix/signing-key.pub, for the laptop
- laptop-signing.pub: the laptop's /etc/guix/signing-key.pub, for offload imports

modules/engstrand/system/builder.scm skips any file that is missing. The laptop's
asahi.scm trusts builder-signing.pub once it exists, and its activation creates
the offload key, so the next make switch needs no manual guix archive or ssh-keygen.
