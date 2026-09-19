(local plugin (. (require :lib.lazy) :plugin))

;; If go-code's .envrc.local exports a Bazel-provided Go SDK in VIM_PATH,
;; use it inside Neovim so gopls avoids the monorepo's wrapped `go` binary.
(when (and vim.env.VIM_PATH (not= vim.env.VIM_PATH ""))
  (set vim.env.PATH vim.env.VIM_PATH))

(fn go-monorepo-gopls-cmd []
  ;; go-code-managed-gopls sets GOPACKAGESDRIVER and Bazel's Go SDK path,
  ;; then connects through the uLSP gopls proxy. This avoids the monorepo's
  ;; wrapped `go` binary while keeping uLSP's performance benefits.
  (if (= (vim.fn.executable :go-code-managed-gopls) 1)
      [:go-code-managed-gopls :-mode=stdio]
      [:gopls :-mode=stdio]))

(fn go-root-dir [arg on-dir]
  (let [fname (if (= (type arg) :number) (vim.api.nvim_buf_get_name arg) arg)
        util (require :lspconfig.util)
        root (or ((util.root_pattern :MODULE.bazel :WORKSPACE :WORKSPACE.bzlmod
                                     :.monorepo) fname)
                 ((util.root_pattern :go.work :go.mod :.git) fname))]
    (if on-dir
        (on-dir root)
        root)))

(fn ulsp-default-config []
  {:cmd [:socat "-" "tcp:localhost:27883,ignoreeof"]
   :flags {:debounce_text_changes 1000}
   :filetypes [:go]
   :root_dir go-root-dir
   :single_file_support false})

(fn setup-ulsp-lspconfig []
  (let [config (ulsp-default-config)
        configs (require :lspconfig.configs)]
    (set configs.ulsp
         {:default_config (vim.tbl_extend :force config
                                          {:docs {:description "Uber Language Server"}})})
    ;; Neovim 0.12's native LSP config path is what LazyVim ultimately uses.
    (when (and vim.lsp vim.lsp.config)
      (vim.lsp.config :ulsp config))))

(plugin :neovim/nvim-lspconfig
        {:opts (fn [_ opts]
                 ;; Neovim 0.12 rejects some JDTLS inlay-hint positions as
                 ;; invalid extmark columns. Keep Java LSP features enabled,
                 ;; but leave its inlay hints disabled until the positions are
                 ;; corrected upstream.
                 (table.insert opts.inlay_hints.exclude :java)
                 (setup-ulsp-lspconfig)
                 (local go-servers
                        {:lua_ls {:settings {:Lua {:workspace {:checkThirdParty false}
                                                   :completion {:callSnippet :Replace}}}}
                         :nixd {}
                         :pyright {}
                         ;; uLSP adds monorepo-specific actions like "Re-Sync Dependencies".
                         :ulsp {}
                         ;; gopls still provides Go code intelligence, via uLSP's gopls proxy.
                         :gopls {:cmd (go-monorepo-gopls-cmd)
                                 :root_dir go-root-dir
                                 :single_file_support false
                                 :filetypes [:go :gomod :gowork :gotmpl]
                                 :settings {:gopls {:gofumpt true
                                                    :staticcheck true
                                                    :analyses {:unusedparams true
                                                               :unusedwrite true}}}}})
                 (set opts.servers
                      (vim.tbl_deep_extend :force (or opts.servers {})
                                           go-servers)))})
