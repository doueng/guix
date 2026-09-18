function nvim_resume --description 'Resume the last Neovim session in the current project'
    set -lx NVIM_RESTORE_LAST_SESSION 1
    command nvim $argv
end
