function jj_stack_restack_push
    if not jj__require_cmds jj git
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

    jj git fetch; or return 1
    jj rebase -b @ -o $base; or return 1

    jj__push_engstrand_bookmarks
end
