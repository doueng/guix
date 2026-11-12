(local plugin (. (require :lib.lazy) :plugin))

(plugin :stevearc/conform.nvim
        {:opts {:formatters_by_ft {:fennel [:fnlfmt]
                                   :lua [:stylua]
                                   :java [:google_java_format]
                                   :nix [:nixfmt]
                                   :python [:black]}}})
