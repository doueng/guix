function jj_stack_publish
    if not jj__require_cmds jj git arh
        return 1
    end
    if not jj__ensure_repo
        return 1
    end
    if not jj__ensure_origin
        return 1
    end

    jj__push_engstrand_bookmarks; or return 1

    if test -z (git branch --show-current)
        set head (jj__stack_head_bookmark)
        if test -z "$head"
            echo "No engstrand/* bookmarks found in current stack to publish."
            return 1
        end
        git checkout $head; or return 1
    end

    arh publish --full-stack --no-interactive --apply-fixes
end
