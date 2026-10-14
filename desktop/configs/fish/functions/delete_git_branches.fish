function delete_git_branches
    set -l branches (git for-each-ref --format="%(refname:lstrip=2)" refs/heads/)

    set -l selected_branches (echo $branches | tr ' ' '\n' | fzf --multi --preview "git log --oneline {}")

    if test -n "$selected_branches"
        for branch in $selected_branches
            git branch -D $branch
        end

        echo "Deleted branches: $selected_branches"
    else
        echo "No branches selected for deletion."
    end
end
