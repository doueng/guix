set -l paths /run/privileged/bin "$HOME/.guix-home/profile/bin" "$HOME/.guix-profile/bin" /run/current-system/profile/bin /run/current-system/profile/sbin "$HOME/.local/bin"
for path in $paths[-1..1]
    if test -d "$path"
        fish_add_path --global --prepend --path "$path"
    end
end

set -gx EMACS emacs
if status is-interactive; and command -q direnv
    direnv hook fish | source
end
