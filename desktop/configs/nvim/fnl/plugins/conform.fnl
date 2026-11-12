(local plugin (. (require :lib.lazy) :plugin))

(plugin :stevearc/conform.nvim
        {:opts {:formatters_by_ft {:fennel [:fnlfmt]
                                   :lua [:stylua]
                                   :nix [:nixfmt]
                                   :python [:black]}}})
