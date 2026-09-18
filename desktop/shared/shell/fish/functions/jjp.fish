function jjp
    if test (count $argv) -gt 1
        echo "Usage: jjp [bookmark]"
        return 1
    end

    if not jj__require_cmds jj
        return 1
    end
    if not jj__ensure_repo
        return 1
    end

    set bookmark ""
    if test (count $argv) -eq 1
        set bookmark $argv[1]
    else
        if not jj__require_cmds fzf
            return 1
        end

        set selection (
            jj --ignore-working-copy log -n 20 --no-graph 2>/dev/null \
                | jj__pushable_log_lines \
                | fzf --prompt='jj log> '
        )

        if test $status -ne 0 -o -z "$selection"
            return 1
        end

        set bookmark (jj__bookmark_from_log_line "$selection")
        if test $status -ne 0 -o -z "$bookmark"
            echo "Selected line has no bookmark."
            return 1
        end
    end

    if not jj --ignore-working-copy log -r "bookmarks(\"$bookmark\")" -n 1 >/dev/null 2>&1
        echo "Unknown bookmark: $bookmark"
        return 1
    end

    echo "Pushing bookmark '$bookmark'"
    jj git push --bookmark $bookmark
end
