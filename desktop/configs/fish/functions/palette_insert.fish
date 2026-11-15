function palette_insert --description 'Insert a history line, function or abbreviation chosen in the Dusk palette'
    set -l choice (palette fish | string collect)
    if test -n "$choice"
        commandline --insert -- $choice
    end
    commandline --function repaint
end
