function de_prevd --description "Move back in the directory history"
    __fish_move_last dirprev dirnext

    # Set direction for 'cd -'
    set -g __fish_cd_direction next

    commandline -f repaint
end
