function sesh_connect --description "Interactive sesh session switcher using fzf"
    if not command -q sesh
        echo "sesh is not installed" >&2
        return 127
    end

    if not command -q fzf
        echo "fzf is not installed" >&2
        return 127
    end

    set -l selected (sesh list | fzf)
    if test -n "$selected"
        sesh connect $selected
    end
end
