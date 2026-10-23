function palette_insert --description 'Insert a history line, function or abbreviation chosen in the Dusk palette'
    # Insert only. Running a history line by accident could be destructive.
    set -l choice (palette fish | string collect)
    if test -n "$choice"
        commandline --insert -- $choice
    end
    commandline --function repaint
end
