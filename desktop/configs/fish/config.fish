set -gx EDITOR nvim
set -gx VISUAL nvim
set -gx SHELL (command -s fish)
set -gx PI_SKIP_VERSION_CHECK 1

# A login shell started from a stripped env, such as a Rex pane whose server
# was autostarted from Rex's allowlist, lacks the Guix session env: no
# SSL_CERT_FILE or GIT_SSL_CAINFO, so git and jj fail certificate checks
# (T219). Load what /etc/profile and Guix Home set, keeping what is set.
if status is-login; and not set -q SSL_CERT_FILE; and test -r $HOME/.guix-home/setup-environment
    for kv in (HOME_ENVIRONMENT=$HOME/.guix-home bash -c '. /etc/profile; . $HOME_ENVIRONMENT/setup-environment; env -0' 2>/dev/null | string split0)
        set -l name (string split -m1 = -- $kv)[1]
        if string match -qr '^[A-Za-z_][A-Za-z0-9_]*$' -- $name; and not set -q $name; and not contains -- $name SHLVL _ PWD OLDPWD
            set -gx $name (string split -m1 = -- $kv)[2]
        end
    end
end

if status is-interactive
    stty -ixon 2>/dev/null
end

set -g fish_greeting
set -g fish_escape_delay_ms 10
set -g fish_key_bindings fish_vi_key_bindings
set -gx AIFX_ENABLE_PRIVATE_PREVIEW_FEATURES 1

abbr --add dotdot --regex '^\.\.+$' --function multicd

set -g my_emoji "🐟"

fish_vi_cursor

if status is-interactive; and command -q zoxide
    zoxide init fish | source
end
