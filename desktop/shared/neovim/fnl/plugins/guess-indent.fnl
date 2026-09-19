(local plugin (. (require :lib.lazy) :plugin))

(plugin :nmac427/guess-indent.nvim
        {:opts {:auto_cmd true :override_editorconfig true}})
