function jj__normalize_bookmark
    set name $argv[1]
    if test -z "$name"
        echo ""
        return 1
    end
    if string match -qr '^engstrand/' -- $name
        echo $name
    else
        echo "engstrand/$name"
    end
end
