function jj__list_engstrand_stack_bookmarks
    set names (jj__list_engstrand_bookmarks)
    if test $status -ne 0
        return 1
    end

    set stack_names
    for name in $names
        set hit (jj --ignore-working-copy log -r "bookmarks(\"$name\") & ::@" -n 1 --no-graph -T 'change_id' 2>/dev/null)
        if test -n "$hit"
            set stack_names $stack_names $name
        end
    end

    if test (count $stack_names) -eq 0
        return 1
    end

    printf "%s\n" $stack_names
end
