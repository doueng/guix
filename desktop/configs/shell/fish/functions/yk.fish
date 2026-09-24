function yk
    set output (yktok | tee /dev/tty)
    set number (echo $output | awk '/tokenizer/ {print $2}')
    printf "%s" $number | pbcopy
end
