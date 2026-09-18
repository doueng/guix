function fish_user_key_bindings
    bind -M insert ctrl-o de_prevd_if_prompt_empty
    bind -M insert \t de_nextd_if_prompt_empty
    bind -M default ctrl-o de_prevd
    bind -M default \t de_nextd
    bind -M insert '!' bind_bang
    bind -M insert '$' bind_dollar
    bind -M insert \cf accept-autosuggestion execute
    bind -M insert \cl fish_clear
    bind -M insert \cr fzf_history
    bind -M default \cr fzf_history
    bind -M insert ctrl-backspace backward-kill-word
end
