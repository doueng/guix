# Doom Emacs bootstrap

The Doom configuration is declarative here, but Doom's framework and package
checkout remain mutable under `~/.config/emacs`.

Bootstrap once on the native Guix system:

```sh
DOOM="$HOME/.config/emacs"
test ! -e "$DOOM"
git clone --depth 1 https://github.com/doomemacs/doomemacs "$DOOM"
"$DOOM/bin/doom" install --no-env --no-fonts
"$DOOM/bin/doom" sync
```

The Guix Home link `~/.config/doom` owns `init.el`, `packages.el`, `config.el`,
Clojure integration and keybindings. Do not replace that link with the Doom
framework directory.
