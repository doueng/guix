(local plugin (. (require :lib.lazy) :plugin))

(plugin :neovim/nvim-lspconfig
        {:opts (fn [_ opts]
                 ;; Neovim 0.12 rejects some JDTLS inlay-hint positions as
                 ;; invalid extmark columns. Keep Java LSP features enabled,
                 ;; but leave its inlay hints disabled until the positions are
                 ;; corrected upstream.
                 (table.insert opts.inlay_hints.exclude :java)
                 (local go-servers
                        {:lua_ls {:settings {:Lua {:workspace {:checkThirdParty false}
                                                   :completion {:callSnippet :Replace}}}}
                         :nixd {}
                         :pyright {}
                         :gopls {:filetypes [:go :gomod :gowork :gotmpl]
                                 :settings {:gopls {:gofumpt true
                                                    :staticcheck true
                                                    :analyses {:unusedparams true
                                                               :unusedwrite true}}}}})
                 (set opts.servers
                      (vim.tbl_deep_extend :force (or opts.servers {})
                                           go-servers)))})
