function jjns
    if not jj__require_cmds jj git arh
        return 1
    end
    if not jj__ensure_repo
        return 1
    end
    if not jj__ensure_origin
        return 1
    end

    if test (count $argv) -lt 1
        echo "Usage: jjns <name> [base]"
        return 1
    end

    set name (jj__normalize_bookmark $argv[1])
    if test -z "$name"
        return 1
    end

    set trunk (jj__base_bookmark)
    if test -z "$trunk"
        return 1
    end

    set base ""
    if test (count $argv) -ge 2
        set base $argv[2]
    else
        set base (jj__stack_head_bookmark)
    end
    if test -z "$base"
        set base $trunk
    end
    if test "$base" != "main" -a "$base" != "master"
        set base (jj__normalize_bookmark $base)
    end

    if not jj --ignore-working-copy log -r $base -n 1 >/dev/null 2>&1
        echo "Unknown revision: $base"
        return 1
    end

    if test "$base" != "main" -a "$base" != "master"
        if not jj --ignore-working-copy log -r "bookmarks(\"$base\") & ::@" -n 1 >/dev/null 2>&1
            echo "Base bookmark is not in current stack: $base"
            return 1
        end
    end

    set -l prefix "[jj-stack]"

    function __jjns_print_command --no-scope-shadowing
        printf '  $ %s\n' (string join ' ' -- (string escape -- $argv))
    end

    set -l current_description (jj --ignore-working-copy log -r @ -n 1 --no-graph -T 'description.first_line().trim()' 2>/dev/null)
    if test -z "$current_description"
        set_color --bold bryellow
        printf '\n%s ' "$prefix"
        set_color normal
        printf "Current change has no description; setting placeholder\n"
        set -l cmd jj describe -m placeholder
        __jjns_print_command $cmd
        $cmd; or begin
            functions -e __jjns_print_command
            return 1
        end
    end

    set_color --bold brcyan
    printf '\n%s ' "$prefix"
    set_color normal
    printf "Creating stacked PR branch '%s' on '%s'\n" "$name" "$base"

    set_color --bold brcyan
    printf '\n%s ' "$prefix"
    set_color normal
    printf "Sealing current work with 'jj new'\n"
    set -l cmd jj new
    __jjns_print_command $cmd
    $cmd; or begin
        functions -e __jjns_print_command
        return 1
    end

    if jj --ignore-working-copy log -r "bookmarks($name)" -n 1 >/dev/null 2>&1
        set_color --bold brcyan
        printf '\n%s ' "$prefix"
        set_color normal
        printf "Moving existing bookmark '%s' to @-\n" "$name"
        set -l cmd jj bookmark set $name -r @-
        __jjns_print_command $cmd
        $cmd; or begin
            functions -e __jjns_print_command
            return 1
        end
    else
        set_color --bold brcyan
        printf '\n%s ' "$prefix"
        set_color normal
        printf "Creating bookmark '%s' at @-\n" "$name"
        set -l cmd jj bookmark create $name -r @-
        __jjns_print_command $cmd
        $cmd; or begin
            functions -e __jjns_print_command
            return 1
        end
    end

    set_color --bold brcyan
    printf '\n%s ' "$prefix"
    set_color normal
    printf "Pointing git branch '%s' upstream at '%s'\n" "$name" "$base"
    set -l cmd git branch $name --set-upstream-to=$base
    __jjns_print_command $cmd
    $cmd; or begin
        functions -e __jjns_print_command
        return 1
    end

    set_color --bold brcyan
    printf '\n%s ' "$prefix"
    set_color normal
    printf "Tracking origin/%s in jj\n" "$name"
    set -l cmd jj bookmark track $name --remote=origin
    __jjns_print_command $cmd
    $cmd; or begin
        functions -e __jjns_print_command
        return 1
    end

    set_color --bold brcyan
    printf '\n%s ' "$prefix"
    set_color normal
    printf "Pushing bookmark '%s'\n" "$name"
    set -l cmd jj git push --bookmark $name
    __jjns_print_command $cmd
    $cmd; or begin
        functions -e __jjns_print_command
        return 1
    end

    set_color --bold brcyan
    printf '\n%s ' "$prefix"
    set_color normal
    printf "Checking out git branch '%s'\n" "$name"
    set -l cmd git checkout $name
    __jjns_print_command $cmd
    $cmd; or begin
        functions -e __jjns_print_command
        return 1
    end

    set_color --bold brcyan
    printf '\n%s ' "$prefix"
    set_color normal
    printf "Publishing PR with arh\n"
    set -l cmd arh publish --no-interactive --apply-fixes
    __jjns_print_command $cmd
    $cmd; or begin
        functions -e __jjns_print_command
        return 1
    end

    set_color --bold brgreen
    printf '\n%s ' "$prefix"
    set_color normal
    printf "Done: stacked PR branch '%s' is ready\n" "$name"
    functions -e __jjns_print_command
end
