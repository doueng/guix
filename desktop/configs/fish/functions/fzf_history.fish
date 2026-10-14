function fzf_history
    set cmd (history | fzf --height=40% --reverse --tac)
    if test -n "$cmd"
        commandline --replace "$cmd"
        commandline --function repaint
    end
end
