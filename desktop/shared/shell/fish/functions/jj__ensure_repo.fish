function jj__ensure_repo
    if not jj --ignore-working-copy root >/dev/null 2>&1
        echo "Not inside a jj repository."
        return 1
    end
end
