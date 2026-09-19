(local plugin (. (require :lib.lazy) :plugin))

[(plugin :gpanders/nvim-parinfer
         {:ft [:clojure :fennel]
          :init (fn []
                  (set vim.g.parinfer_mode :smart))
          :config (fn []
                    (vim.keymap.set :i :<M-h> "<Plug>(parinfer-backtab)"
                                    {:remap true
                                     :desc "Parinfer previous tab stop"})
                    (vim.keymap.set :i :<M-l> "<Plug>(parinfer-tab)"
                                    {:remap true
                                     :desc "Parinfer next tab stop"}))})]
