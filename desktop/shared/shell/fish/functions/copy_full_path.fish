function copy_full_path --description 'resolve a clipboard path and copy the result back'
    if test (count $argv) -ne 0
        echo "usage: copy_full_path" >&2
        return 2
    end

    set -l nbsp (printf '\u00A0')
    set -l figure_space (printf '\u2007')
    set -l narrow_nbsp (printf '\u202F')
    set -l trim_chars " \t\n$nbsp$figure_space$narrow_nbsp"

    set -l raw_text (fish_clipboard_paste | string collect)
    set raw_text (string replace -ra '\r' '' -- "$raw_text")

    set -l candidate
    for line in (printf '%s' "$raw_text" | string split '\n')
        set line (string trim -c "$trim_chars" -- "$line")
        if test -z "$line"
            continue
        end

        set line (string replace -ra '^[^~/[:alnum:]_.-]+' '' -- "$line")

        if test (string length -- "$line") -ge 2
            set -l first_char (string sub -s 1 -l 1 -- "$line")
            set -l last_char (string sub -s -1 -- "$line")

            if test "$first_char" = "'" -a "$last_char" = "'"
                set line (string sub -s 2 -l (math (string length -- "$line") - 2) -- "$line")
            else if test "$first_char" = '"' -a "$last_char" = '"'
                set line (string sub -s 2 -l (math (string length -- "$line") - 2) -- "$line")
            end
        end

        set line (string trim -c "$trim_chars" -- "$line")

        if string match -qr '^(~|/|\./|\.\./|[^[:space:]]+/)' -- "$line"
            set candidate "$line"
            break
        end
    end

    if test -z "$candidate"
        echo "copy_full_path: clipboard does not contain a path" >&2
        return 1
    end

    set -l resolved_input "$candidate"

    if string match -q '~' -- "$resolved_input"
        set resolved_input "$HOME"
    else if string match -q '~/*' -- "$resolved_input"
        set resolved_input "$HOME"/(string sub -s 3 -- "$resolved_input")
    else if not string match -qr '^/' -- "$resolved_input"
        set -l repo_root (git rev-parse --show-toplevel 2>/dev/null)
        if test -n "$repo_root"
            set -l repo_base (path basename -- "$repo_root")
            set -l repo_prefix "$repo_base/"

            if test "$resolved_input" = "$repo_base"
                set resolved_input "$repo_root"
            else if string match -q -- "$repo_prefix*" "$resolved_input"
                set -l suffix (string sub -s (math (string length -- "$repo_prefix") + 1) -- "$resolved_input")
                set resolved_input "$repo_root/$suffix"
            end
        end
    end

    set -l resolved_path (path resolve -- "$resolved_input" 2>/dev/null)
    if test $status -ne 0
        echo "copy_full_path: could not resolve path: $candidate" >&2
        return 1
    end

    printf '%s\n' "$resolved_path" | fish_clipboard_copy
    printf '%s\n' "$resolved_path"
end
