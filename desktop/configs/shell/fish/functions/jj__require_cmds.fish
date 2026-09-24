function jj__require_cmds
    for cmd in $argv
        if not type -q $cmd
            echo "Missing required command: $cmd"
            return 1
        end
    end
    return 0
end
