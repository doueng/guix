function jj__stack_head_bookmark
    set head (jj --ignore-working-copy log -r 'latest(bookmarks("engstrand/*") & ::@, 1)' --no-graph -T 'bookmarks.joined(" ")' 2>/dev/null)
    if test -z "$head"
        return 1
    end

    set first (string split -m 1 ' ' -- $head)
    if test (count $first) -ge 1
        echo $first[1]
        return 0
    end

    return 1
end
