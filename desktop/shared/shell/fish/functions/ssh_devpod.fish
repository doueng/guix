function ssh_devpod --description "autossh into a devpod and attach/create tmux session derived from host"
    set -l host $argv[1]
    if test -z "$host"
        echo "Usage: ssh_devpod <host>"
        return 2
    end

    # 1) drop everything after first dot:  engstrand-nix-again
    set -l left (string split -m1 '.' -- $host)[1]

    # 2) drop leading "<user>-": nix-again
    # If your username can contain '-', this still works because it removes up to the first '-'.
    set -l session (string replace -r '^[^-]+-' '' -- $left)

    # 3) sanitize (optional)
    set -l session (string replace -ar '[^A-Za-z0-9_.-]' '_' -- $session)

    autossh -M 0 -tt $host "bash -lc 'exec tmux new-session -A -s $session'"
end
