function ssh_openclaw --description "autossh into the openclaw host and attach/create tmux session derived from host"
    set -l host $argv[1]
    if test -z "$host"
        echo "Usage: ssh_openclaw <host>"
        return 2
    end

    # 1) drop everything after first dot or @: openclaw / engstrand
    set -l left (string split -r -m1 '@' -- $host)[-1]
    set -l left (string split -m1 '.' -- $left)[1]

    # 2) use hostname as session name
    set -l session $left

    # 3) sanitize (optional)
    set -l session (string replace -ar '[^A-Za-z0-9_.-]' '_' -- $session)

    autossh -M 0 -tt $host "bash -lc 'cd ~/nixos && exec tmux new-session -A -s $session'"
end
