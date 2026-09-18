function __sdkman_refresh --description "Refresh Fish env from SDKMAN current candidates"
    if test -z "$SDKMAN_DIR"
        set -gx SDKMAN_DIR "$HOME/.sdkman"
    end

    if not test -d "$SDKMAN_DIR/candidates"
        return 0
    end

    set -l new_paths
    for candidate_dir in "$SDKMAN_DIR"/candidates/*
        if test -d "$candidate_dir/current/bin"
            set -a new_paths "$candidate_dir/current/bin"
        end
    end

    set -l managed_paths $new_paths
    if set -q __sdkman_paths
        set managed_paths $managed_paths $__sdkman_paths
    end

    for managed_path in $managed_paths
        while contains -- "$managed_path" $PATH
            set -l idx (contains -i -- "$managed_path" $PATH)
            set -e PATH[$idx]
        end
    end

    if test (count $new_paths) -gt 0
        set -gx PATH $new_paths $PATH
    end

    set -g __sdkman_paths $new_paths

    if test -d "$SDKMAN_DIR/candidates/java/current"
        set -gx JAVA_HOME "$SDKMAN_DIR/candidates/java/current"
    end
end
