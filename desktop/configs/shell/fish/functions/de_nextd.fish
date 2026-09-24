function de_nextd --description 'Move forward in the directory history'

    __fish_move_last dirnext dirprev

    # Set direction for 'cd -'
    set -g __fish_cd_direction prev

    commandline -f repaint
end
