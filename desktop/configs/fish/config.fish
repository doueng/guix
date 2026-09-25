# Fish shell configuration

# Environment variables
# Use -gx instead of -Ux to override parent shell's EDITOR
set -gx EDITOR nvim
set -gx VISUAL nvim
set -gx SHELL (command -s fish)
set -gx PI_SKIP_VERSION_CHECK 1

# Keep Ctrl+S available for terminal/picker bindings instead of XOFF flow control.
if status is-interactive
    stty -ixon 2>/dev/null
end

# Shell settings
set -g fish_greeting
set -g fish_escape_delay_ms 10
set -g fish_key_bindings fish_vi_key_bindings
set -gx KEYTIMEOUT 1
set -gx AIFX_ENABLE_PRIVATE_PREVIEW_FEATURES 1

# Set up abbreviations
abbr --add dotdot --regex '^\.\.+$' --function multicd

# Prompt customization
set -g my_emoji "🐟"

# FZF options
set -g fzf_git_log_opts --no-mouse
# Completely override fish's cursor system for vi mode
function fish_vi_cursor --description 'Custom vi cursor with hide support'
    # Override the built-in function completely
    function __fish_vi_cursor_set --argument mode
        if not status is-interactive; and not status is-interactive-read
            return
        end

        switch $mode
            case insert
                printf '\e[?25l' # hide cursor
            case default replace replace_one visual
                printf '\e[?25h\e[4 q' # show + underline cursor
            case '*'
                printf '\e[?25h\e[4 q' # show + underline cursor (fallback)
        end
    end

    # Handle all the events that can change cursor
    function __fish_vi_cursor_handle --on-variable fish_bind_mode --on-event fish_postexec --on-event fish_focus_in --on-event fish_read --on-event fish_prompt
        __fish_vi_cursor_set $fish_bind_mode
    end

    function __fish_vi_cursor_handle_preexec --on-event fish_preexec --on-event fish_exit
        if not status is-interactive; and not status is-interactive-read
            return
        end

        printf '\e[?25h\e[2 q' # show block cursor for external commands
    end
end

# Initialize the custom cursor system
fish_vi_cursor

if status is-interactive; and command -q zoxide
    zoxide init fish | source
end
