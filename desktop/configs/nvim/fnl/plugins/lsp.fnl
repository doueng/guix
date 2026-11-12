(local plugin (. (require :lib.lazy) :plugin))

(plugin :neovim/nvim-lspconfig
        {:opts (fn [_ opts]
                 (local go-servers
                        {:lua_ls {:settings {:Lua {:workspace {:checkThirdParty false}
                                                   :completion {:callSnippet :Replace}}}}
                         :pyright {}
                         :gopls {:filetypes [:go :gomod :gowork :gotmpl]
                                 :settings {:gopls {:gofumpt true
                                                    :staticcheck true
                                                    :analyses {:unusedparams true
                                                               :unusedwrite true}}}}})
                 (set opts.servers
                      (vim.tbl_deep_extend :force (or opts.servers {})
                                           go-servers)))})
