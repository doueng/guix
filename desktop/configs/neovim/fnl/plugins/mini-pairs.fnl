(local plugin (. (require :lib.lazy) :plugin))

(plugin :nvim-mini/mini.pairs
        {:opts {:mappings {"\"" false
                           "'" false}}})
