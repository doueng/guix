#!/usr/bin/env fish

function git_diff_main_or_master
    if git rev-parse --verify main > /dev/null 2>&1
        git diff main -- $argv
    else if git rev-parse --verify master > /dev/null 2>&1
        git diff master -- $argv
    else
        echo "Neither 'main' nor 'master' branch exists."
    end
end
