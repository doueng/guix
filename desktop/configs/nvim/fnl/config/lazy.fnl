;; lazy.nvim itself is provided by Home Manager via pkgs.vimPlugins.lazy-nvim.
;; The plugins below, including LazyVim, are still managed by lazy.nvim.
(local lazy (require :lazy))
(local config-dir (vim.fn.fnamemodify (vim.fn.resolve (.. (vim.fn.stdpath :config)
                                                          :/init.fnl))
                                      ":p:h"))

(local plugin-modules [:plugins.lazyvim
                       :plugins.disabled
                       :plugins.snacks
                       :plugins.fff
                       :plugins.signify
                       :plugins.flash
                       :plugins.guess-indent
                       :plugins.markdown
                       :plugins.mini-pairs
                       :plugins.blink
                       :plugins.conjure
                       :plugins.octo
                       :plugins.parinfer
                       :plugins.treesitter
                       :plugins.mason
                       :plugins.colorscheme
                       :plugins.which-key
                       :plugins.dashboard
                       :plugins.telescope
                       :plugins.lualine
                       :plugins.bufferline
                       :plugins.nvim-cmp
                       :plugins.lsp
                       :plugins.java
                       :plugins.noice
                       :plugins.conform])

(local specs [])
(each [_ module-name (ipairs plugin-modules)]
  (table.insert specs (require module-name)))

(lazy.setup {:spec specs
             :defaults {:version false}
             :checker {:enabled true :notify false}
             :performance {:rtp {:paths [config-dir]
                                 :disabled_plugins [:gzip
                                                    :matchparen
                                                    :tarPlugin
                                                    :tohtml
                                                    :tutor
                                                    :zipPlugin]}}})
