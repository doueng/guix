function jj__push_engstrand_bookmarks
    set names (jj__list_engstrand_stack_bookmarks)
    if test $status -ne 0
        echo "No engstrand/* bookmarks found in current stack."
        return 1
    end

    for name in $names
        jj git push --bookmark $name; or return 1
    end
end
