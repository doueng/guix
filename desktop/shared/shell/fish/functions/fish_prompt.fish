# Default appearance options. Override in config.fish if you want.
if ! set -q lucid_cwd_color
    set -g lucid_cwd_color brmagenta
end

function fish_prompt
    set -l prompt_cell_marker '\u00a0'

    # Breathing room above the prompt inside Herdr panes.
    if set -q HERDR_ENV
        echo
    end

    # Set default emoji if not defined
    if ! set -q my_emoji
        set -g my_emoji " 🐟"
    end

    # Detect your local username
    set local_user (id -un)

    # Check if you're connected via SSH or if $USER differs from $local_user
    if test -n "$SSH_CLIENT" -o "$USER" != "$local_user"
        # Show emoji + user
        echo -n "$my_emoji $USER"
    else
        # Show just the emoji
        echo -n "$my_emoji"
    end

    printf '%b' "$prompt_cell_marker"

    # Display a separator (space or arrow)
    set_color --bold $lucid_cwd_color
    set_prompt_cwd
    echo "$PROMPT_CWD"
    printf '%b  ' "$prompt_cell_marker"
    set_color normal
end

function fish_right_prompt
    return
end
