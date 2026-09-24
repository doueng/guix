function piorg --description 'Launch Neovim with the pi-org org-mode agent frontend open'
    # --cmd runs before config load so the VimEnter autocmd sees the guard and
    # skips auto-opening the snacks explorer sidebar. -c then opens pi-org.
    # Extra args are forwarded to nvim, e.g. `piorg some/dir`.
    command nvim --cmd "let g:pi_org_skip_explorer = 1" $argv -c "PiOrg"
end
