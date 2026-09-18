function jj_update_main
    if not jj__require_cmds jj
        return 1
    end
    if not jj__ensure_repo
        return 1
    end
    if not jj__ensure_origin
        return 1
    end

    jj git fetch; or return 1

    set base (jj__base_bookmark)
    if test -z "$base"
        return 1
    end

    if not jj --ignore-working-copy log -r "origin/$base" -n 1 >/dev/null 2>&1
        echo "Remote bookmark 'origin/$base' not found. Run: jj bookmark track $base --remote=origin"
        return 1
    end

    set local_id (jj --ignore-working-copy log -r $base -n 1 --no-graph -T 'commit_id')
    set remote_id (jj --ignore-working-copy log -r origin/$base -n 1 --no-graph -T 'commit_id')

    if test -n "$local_id" -a -n "$remote_id" -a "$local_id" != "$remote_id"
        echo "$base is behind origin/$base. Run: jj bookmark set $base -r origin/$base"
        return 1
    end

    echo "$base is up to date."
end
