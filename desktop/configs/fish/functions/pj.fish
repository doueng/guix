function pj
    set -l filter '.'
    if set -q argv[1]
        set filter $argv[1]
    end

    fish_clipboard_paste | jq -Rrs ". as \$raw | try (fromjson | $filter) catch \$raw"
end
