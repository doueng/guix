function jj__list_engstrand_bookmarks
    set names (jj --ignore-working-copy bookmark list -T 'name ++ "\n"' | string match -r '^engstrand/.*')
    if test (count $names) -eq 0
        return 1
    end
    printf "%s\n" $names
end
