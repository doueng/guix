function fzf_history
    # Use Fish history, feed it into fzf, and eval the selected command
    set cmd (history | fzf --height=40% --reverse --tac)
    if test -n "$cmd"
        commandline --replace "$cmd"
        commandline --function repaint
    end
end
