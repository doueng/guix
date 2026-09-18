function de_nextd_if_prompt_empty --description 'Move forward in dir history if prompt is empty'
    set -l buf (commandline)
    set -l cursor (commandline -C)
    if test -z "$buf" -a "$cursor" -eq 0
        de_nextd
        return
    end

    commandline -f complete
end
