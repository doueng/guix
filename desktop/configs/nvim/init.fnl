;; Leader keys must be set before plugins load.
(set vim.g.mapleader " ")
(set vim.g.maplocalleader "\\")

(vim.loader.enable)

;; LazyVim's Treesitter bootstrap checks CC rather than probing gcc. Guix
;; provides gcc-toolchain's gcc executable but does not expose a cc alias.
(when (or (not vim.env.CC) (= vim.env.CC ""))
  (when (= (vim.fn.executable :gcc) 1)
    (set vim.env.CC "gcc")))

(local fennel (require :fennel))
(local config-dir
       (vim.fn.fnamemodify (vim.fn.resolve (.. (vim.fn.stdpath :config) "/init.fnl"))
                           ":p:h"))

(vim.opt.rtp:prepend config-dir)

(set fennel.path (.. config-dir "/fnl/?.fnl;" config-dir "/fnl/?/init.fnl;"
                     fennel.path))

(local searchers (or package.searchers package.loaders))
(when searchers
  (table.insert searchers 2 fennel.searcher))

(require :config.lazy)
(require :config.fff)

