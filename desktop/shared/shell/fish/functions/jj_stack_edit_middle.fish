function jj_stack_edit_middle
    if not jj__require_cmds jj git
        return 1
    end
    if not jj__ensure_repo
        return 1
    end
    if not jj__ensure_origin
        return 1
    end

    if test (count $argv) -lt 1
        echo "Usage: jj_stack_edit_middle <bookmark>"
        return 1
    end

    set name (jj__normalize_bookmark $argv[1])
    if test -z "$name"
        return 1
    end

    if not jj --ignore-working-copy log -r "bookmarks($name)" -n 1 >/dev/null 2>&1
        echo "Bookmark not found: $name"
        return 1
    end

    if not jj --ignore-working-copy log -r "bookmarks(\"$name\") & ::@" -n 1 >/dev/null 2>&1
        echo "Bookmark is not in current stack: $name"
        return 1
    end

    set before (jj --ignore-working-copy log -r @ -n 1 --no-graph -T 'change_id')
    if test -z "$before"
        echo "Could not determine current change id."
        return 1
    end

    if not jj --ignore-working-copy log -r "@ & bookmarks($name)" -n 1 >/dev/null 2>&1
        echo "Current commit is not $name. Run 'jj edit $name' first."
        return 1
    end

    jj bookmark set $name; or return 1
    jj rebase -s "children(bookmarks($name))" -o $name; or return 1

    jj__push_engstrand_bookmarks; or return 1

    jj edit $before; or return 1
end
