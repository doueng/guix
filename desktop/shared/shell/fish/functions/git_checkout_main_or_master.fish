function git_checkout_main_or_master
    if git rev-parse --verify main >/dev/null 2>&1
        git checkout main -- $argv
    else if git rev-parse --verify master >/dev/null 2>&1
        git checkout master -- $argv
    else
        echo "Neither 'main' nor 'master' branch exists."
    end
end

