function jj_bookmark_cleanup_empty --description 'Forget absent bookmarks and abandon empty Jujutsu changes'
    if not jj__require_cmds jj
        return 1
    end
    if not jj__ensure_repo
        return 1
    end

    set absent_bookmarks (jj bookmark list -T 'if(!remote && !present, name ++ "\n")' | string match -rv '^$')
    set empty_changes (jj log -r 'bookmarks() & empty()' --no-graph -T 'commit_id ++ "\n"' | string match -rv '^$')

    if test (count $absent_bookmarks) -eq 0 -a (count $empty_changes) -eq 0
        echo "No empty bookmarks found."
        return 0
    end

    if test (count $absent_bookmarks) -gt 0
        echo "Forgetting absent bookmarks:"
        printf "  %s\n" $absent_bookmarks
        jj bookmark forget $absent_bookmarks; or return 1
    end

    if test (count $empty_changes) -gt 0
        set empty_revset (string join '|' $empty_changes)
        echo "Abandoning empty changes:"
        jj log -r "($empty_revset)" --no-graph -T '"  " ++ commit_id.short() ++ " " ++ local_bookmarks.filter(|b| b.present()).map(|b| b.name()).join(" ") ++ "\n"'
        jj abandon $empty_changes; or return 1
    end
end
