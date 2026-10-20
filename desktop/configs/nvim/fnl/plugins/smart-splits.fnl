(local spec (require :lib.lazy))

(fn hand-off [ctx]
  ;; The Herdr backend declined: Herdr's edge, a zoomed pane, or no Herdr.
  ;; keyleader re-checks with Herdr, so a Herdr error never moves desktop focus.
  (vim.system [(vim.fn.expand "~/.local/bin/keyleader") :nav ctx.direction
               "from Neovim edge"]
              {:detach true}))

(spec.plugin :smart-splits-nvim/smart-splits.nvim
             {:version "^3.0.0"
              :lazy false
              :dependencies [(spec.plugin :adriankarlen/smart-splits-backend-herdr
                                          {:main :smart-splits-backend-herdr
                                           :opts {:disable_nav_when_zoomed true}})]
              :opts {:mux {:backend :smart-splits-backend-herdr
                           :warn_if_unusable false}
                     :move {:at_edge hand-off}}})
