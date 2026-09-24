function jj_diff_parent
    if not jj__require_cmds jj
        return 1
    end
    if not jj__ensure_repo
        return 1
    end

    jj diff -r @-..@
end
