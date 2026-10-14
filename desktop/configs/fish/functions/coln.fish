function coln --description 'Print the Nth whitespace-separated column of each line'
    while read -l input
        echo $input | awk '{print $'$argv[1]'}'
    end
end
