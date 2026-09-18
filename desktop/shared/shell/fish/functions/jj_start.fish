function jj_start
    if not jj__require_cmds jj
        return 1
    end
    if not jj__ensure_repo
        return 1
    end
    if not jj__ensure_main
        return 1
    end

    set base (jj__base_bookmark)
    if test -z "$base"
        return 1
    end

    jj new $base
end
