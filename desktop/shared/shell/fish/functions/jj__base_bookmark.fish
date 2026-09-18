function jj__base_bookmark
    if jj --ignore-working-copy log -r main -n 1 >/dev/null 2>&1
        echo main
        return 0
    end

    if jj --ignore-working-copy log -r master -n 1 >/dev/null 2>&1
        echo master
        return 0
    end

    echo "Neither 'main' nor 'master' bookmark found." >&2
    return 1
end
