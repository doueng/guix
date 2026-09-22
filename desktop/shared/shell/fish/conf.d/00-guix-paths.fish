set -l paths /run/privileged/bin "$HOME/.guix-home/profile/bin" "$HOME/.guix-profile/bin" /run/current-system/profile/bin /run/current-system/profile/sbin "$HOME/.config/emacs/bin" "$HOME/.local/bin"
for path in $paths[-1..1]
    if test -d "$path"
        fish_add_path --global --prepend --path "$path"
    end
end

# Keep the editable Doom wrapper ahead of the profile's raw Emacs binary.
# Remove any duplicate inherited entry so this remains true across sessions.
if test -d "$HOME/.local/bin"
    set -gx PATH "$HOME/.local/bin" (string match -v -- "$HOME/.local/bin" $PATH)
end

set -gx EMACS emacs
set -gx DOOMDIR "$HOME/.config/doom"
if status is-interactive; and command -q direnv
    direnv hook fish | source
end
