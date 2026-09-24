function jj__bookmark_from_log_line
    set line $argv[1]
    if test -z "$line"
        return 1
    end

    set bookmark_group (string match -r --groups-only '\[([^\]]+)\]' -- "$line")
    if test -z "$bookmark_group"
        return 1
    end

    for candidate in (string split ',' -- $bookmark_group)
        set candidate (string trim -- $candidate)
        if test -n "$candidate" -a "$candidate" != "main" -a "$candidate" != "master"
            printf '%s\n' "$candidate"
            return 0
        end
    end

    return 1
end
