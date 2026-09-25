(local plugin (. (require :lib.lazy) :plugin))

[(plugin :Olical/conjure
         {:ft [:clojure :fennel]
          :init (fn []
                  (tset vim.g "conjure#mapping#prefix" :<localleader>)
                  (tset vim.g
                        "conjure#client#clojure#nrepl#connection#auto_repl#enabled"
                        true)
                  (tset vim.g
                        "conjure#client#clojure#nrepl#connection#auto_repl#hidden"
                        true)
                  (tset vim.g "conjure#client#clojure#nrepl#eval#auto_require"
                        false)
                  (tset vim.g "conjure#client#fennel#aniseed#aniseed_module"
                        nil)
                  (tset vim.g "conjure#extract#tree_sitter#enabled" true))
          :config (fn []
                    (vim.api.nvim_create_autocmd :FileType
                                                 {:pattern [:clojure :fennel]
                                                  :callback (fn [args]
                                                              (vim.keymap.set :x
                                                                              :gr
                                                                              :<localleader>E
                                                                              {:buffer args.buf
                                                                               :remap true
                                                                               :silent true
                                                                               :nowait true
                                                                               :desc "Conjure eval visual selection"}))}))})]
