function copy_full_current_path --description 'copy the fully resolved path of the current directory'
    set -l resolved_path (path resolve -- . 2>/dev/null)
    if test $status -ne 0
        echo "copy_full_current_path: could not resolve current directory" >&2
        return 1
    end

    printf '%s\n' "$resolved_path" | fish_clipboard_copy
    printf '%s\n' "$resolved_path"
end
