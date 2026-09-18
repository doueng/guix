function jj__ensure_origin
    if not jj --ignore-working-copy git remote list | string match -qr '^origin\b'
        echo "Remote 'origin' not found."
        return 1
    end
end
