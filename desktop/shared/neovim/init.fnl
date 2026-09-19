;; Entrypoint for Neovim configuration.
;; Mirrors the files Home Manager previously embedded so edits apply instantly.
;; Leader keys must be set before plugins load.
(set vim.g.mapleader " ")
(set vim.g.maplocalleader "\\")

(vim.loader.enable)

;; Guix installs the Fennel Lua module beside its executable rather than in
;; Neovim's default Lua path.
(local fennel-bin (vim.fn.exepath :fennel))
(when (not= fennel-bin "")
  (local fennel-path (.. (vim.fn.fnamemodify fennel-bin :p:h:h)
                         "/share/lua/5.3/?.lua"))
  (set package.path (.. fennel-path ";" package.path)))
(local fennel (require :fennel))
(local config-dir
       (vim.fn.fnamemodify (vim.fn.resolve (.. (vim.fn.stdpath :config) "/init.fnl"))
                           ":p:h"))

;; Put the real config checkout on runtimepath so LazyVim/lazy.nvim can discover
;; lua/config/*.lua shims even before Home Manager has linked ~/.config/nvim/lua.
(vim.opt.rtp:prepend config-dir)

(set fennel.path (.. config-dir "/fnl/?.fnl;" config-dir "/fnl/?/init.fnl;"
                     fennel.path))

(local searchers (or package.searchers package.loaders))
(when searchers
  ;; Prefer Fennel modules when a migrated file shadows an old Lua path.
  (table.insert searchers 2 fennel.searcher))

(require :config.lazy)
(require :config.fff)

;; Local modules that aren't lazy.nvim plugins: set them up directly.
;; pi-org drives `pi --mode rpc` and renders in org-mode buffers; it depends
;; on nvim-orgmode, which is declared in fnl/plugins/pi-org.fnl and lazy-loaded
;; on :PiOrg / filetype=org.
(let [pi-org (require :pi-org)]
  (pi-org.setup {}))
