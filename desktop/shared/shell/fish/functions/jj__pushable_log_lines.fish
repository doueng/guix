function jj__pushable_log_lines
    while read -l line
        if test -z "$line"
            continue
        end

        if jj__bookmark_from_log_line "$line" >/dev/null
            printf '%s\n' "$line"
        end
    end
end
