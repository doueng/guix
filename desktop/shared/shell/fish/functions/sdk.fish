function sdk --description "SDKMAN wrapper for fish"
    if test -z "$SDKMAN_DIR"
        set -gx SDKMAN_DIR "$HOME/.sdkman"
    end

    if not test -e "$SDKMAN_DIR/bin/sdkman-init.sh"
        echo "SDKMAN is not installed at $SDKMAN_DIR."
        echo "Install it with: curl -s \"https://get.sdkman.io\" | bash"
        return 1
    end

    command bash -lc 'source "$SDKMAN_DIR/bin/sdkman-init.sh" >/dev/null; sdk "$@"' sdk $argv
    set -l exit_code $status
    __sdkman_refresh
    return $exit_code
end
