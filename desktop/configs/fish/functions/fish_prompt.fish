if ! set -q lucid_cwd_color
    set -g lucid_cwd_color brmagenta
end

function fish_prompt
    set -l prompt_cell_marker '\u00a0'

    # Breathing room above the prompt inside Herdr panes.
    if set -q HERDR_ENV
        echo
    end

    if ! set -q my_emoji
        set -g my_emoji " 🐟"
    end

    set local_user (id -un)

    if test -n "$SSH_CLIENT" -o "$USER" != "$local_user"
        echo -n "$my_emoji $USER"
    else
        echo -n "$my_emoji"
    end

    printf '%b' "$prompt_cell_marker"

    set_color --bold $lucid_cwd_color
    set_prompt_cwd
    echo "$PROMPT_CWD"
    printf '%b  ' "$prompt_cell_marker"
    set_color normal
end

function fish_right_prompt
    return
end
