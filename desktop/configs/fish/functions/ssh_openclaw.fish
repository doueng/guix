function ssh_openclaw --description "autossh into the openclaw host and attach/create tmux session derived from host"
    set -l host $argv[1]
    if test -z "$host"
        echo "Usage: ssh_openclaw <host>"
        return 2
    end

    set -l left (string split -r -m1 '@' -- $host)[-1]
    set -l left (string split -m1 '.' -- $left)[1]

    set -l session $left

    set -l session (string replace -ar '[^A-Za-z0-9_.-]' '_' -- $session)

    autossh -M 0 -tt $host "bash -lc 'cd ~/nixos && exec tmux new-session -A -s $session'"
end
