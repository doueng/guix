function de_prevd_if_prompt_empty --description 'Move backward in dir history if prompt is empty'
    set -l buf (commandline)
    set -l cursor (commandline -C)
    if test -z "$buf" -a "$cursor" -eq 0
        de_prevd
        return
    end
end
