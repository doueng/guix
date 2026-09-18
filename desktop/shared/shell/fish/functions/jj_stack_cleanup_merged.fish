function jj_stack_cleanup_merged
    if not jj__require_cmds jj git arh
        return 1
    end
    if not jj__ensure_repo
        return 1
    end
    if not jj__ensure_main
        return 1
    end
    if not jj__ensure_origin
        return 1
    end

    set base (jj__base_bookmark)
    if test -z "$base"
        return 1
    end

    if test (count $argv) -lt 2
        echo "Usage: jj_stack_cleanup_merged <merged-bookmark> <next-bookmark>"
        return 1
    end

    set merged (jj__normalize_bookmark $argv[1])
    set next (jj__normalize_bookmark $argv[2])
    if test -z "$merged" -o -z "$next"
        return 1
    end

    if not jj --ignore-working-copy log -r $next -n 1 >/dev/null 2>&1
        echo "Unknown revision: $next"
        return 1
    end

    if not jj --ignore-working-copy log -r "bookmarks(\"$merged\") & ::@" -n 1 >/dev/null 2>&1
        echo "Bookmark is not in current stack: $merged"
        return 1
    end

    if not jj --ignore-working-copy log -r "bookmarks(\"$next\") & ::@" -n 1 >/dev/null 2>&1
        echo "Bookmark is not in current stack: $next"
        return 1
    end

    if git show-ref --verify --quiet "refs/heads/$merged"
        git branch -D $merged; or return 1
    else
        echo "Git branch not found: $merged"
    end

    if not jj bookmark delete $merged
        echo "Bookmark not found or could not delete: $merged"
    end

    jj rebase -s $next -o $base; or return 1
    git branch $next --set-upstream-to=$base; or return 1

    jj git push --bookmark $next; or return 1

    git checkout $next; or return 1
    arh publish --no-interactive --apply-fixes; or return 1
end
