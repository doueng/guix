# The vi mode cursor (T223): none in insert mode, an underscore in the other
# modes; a command gets the terminal's own cursor. fish_vi_key_bindings and
# config.fish call it. ghostty-rex's Bombadil cursor spec runs this file.
function fish_vi_cursor --description 'Custom vi cursor with hide support'
    function __fish_vi_cursor_set --argument mode
        if not status is-interactive; and not status is-interactive-read
            return
        end

        switch $mode
            case insert
                printf '\e[?25l'
            case '*'
                printf '\e[?25h\e[4 q'
        end
    end

    function __fish_vi_cursor_handle --on-variable fish_bind_mode --on-event fish_postexec --on-event fish_focus_in --on-event fish_read --on-event fish_prompt
        __fish_vi_cursor_set $fish_bind_mode
    end

    function __fish_vi_cursor_handle_preexec --on-event fish_preexec --on-event fish_exit
        if not status is-interactive; and not status is-interactive-read
            return
        end

        # A command gets the terminal's own cursor (Ghostty's cursor-style),
        # which every layer passes on alike; rexg-render draws a block as
        # that too (T223).
        printf '\e[?25h\e[0 q'
    end
end
