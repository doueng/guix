(local plugin (. (require :lib.lazy) :plugin))

[(plugin :nvim-treesitter/nvim-treesitter
         {:opts {:ensure_installed [:clojure :fennel :java]}})]
