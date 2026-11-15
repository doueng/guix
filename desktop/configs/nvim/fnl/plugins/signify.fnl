(local plugin (. (require :lib.lazy) :plugin))

[(plugin :mhinz/vim-signify
         {:event [:BufReadPre :BufNewFile]
          :init (fn []
                  ;; Colocated jj/git: letting Signify try both races and git may win with the wrong base; force jj.
                  (set vim.g.signify_skip {:vcs {:allow [:jj]}})
                  (set vim.g.signify_vcs_cmds
                       {:jj "jj diff --ignore-working-copy --color=never --git --context=0 -r @ -- %f"})
                  (set vim.g.signify_vcs_cmds_diffmode
                       {:jj "jj file show -r @- -- %f"})
                  (set vim.g.signify_sign_add "▎")
                  (set vim.g.signify_sign_change "▎")
                  (set vim.g.signify_sign_delete "")
                  (set vim.g.signify_sign_delete_first_line "")
                  (set vim.g.signify_sign_change_delete "▎")
                  (set vim.g.signify_sign_show_count false))
          :config (fn []
                    (let [apply-signify-highlights (fn []
                                                     (vim.api.nvim_set_hl 0
                                                                          :SignifySignAdd
                                                                          {:link :Added})
                                                     (vim.api.nvim_set_hl 0
                                                                          :SignifySignChange
                                                                          {:link :Changed})
                                                     (vim.api.nvim_set_hl 0
                                                                          :SignifySignDelete
                                                                          {:link :Removed})
                                                     (vim.api.nvim_set_hl 0
                                                                          :SignifySignDeleteFirstLine
                                                                          {:link :Removed})
                                                     (vim.api.nvim_set_hl 0
                                                                          :SignifySignChangeDelete
                                                                          {:link :Changed})
                                                     (vim.cmd "sign define SignifyAdd text=▎ texthl=SignifySignAdd")
                                                     (vim.cmd "sign define SignifyChange text=▎ texthl=SignifySignChange")
                                                     (vim.cmd "sign define SignifyRemove text= texthl=SignifySignDelete")
                                                     (vim.cmd "sign define SignifyRemoveFirstLine text= texthl=SignifySignDeleteFirstLine")
                                                     (vim.cmd "sign define SignifyChangeDelete text=▎ texthl=SignifySignChangeDelete"))]
                      (apply-signify-highlights)
                      (vim.api.nvim_create_autocmd :ColorScheme
                                                   {:callback apply-signify-highlights})
                      (vim.keymap.set :n "]h" "<plug>(signify-next-hunk)"
                                      {:remap true :desc "Next hunk"})
                      (vim.keymap.set :n "[h" "<plug>(signify-prev-hunk)"
                                      {:remap true :desc "Previous hunk"})
                      (vim.keymap.set :n :<leader>gd :<cmd>SignifyDiff<cr>
                                      {:desc "Diff file"})
                      (vim.keymap.set :n :<leader>gp :<cmd>SignifyHunkDiff<cr>
                                      {:desc "Preview hunk"})
                      (vim.keymap.set :n :<leader>gu :<cmd>SignifyHunkUndo<cr>
                                      {:desc "Undo hunk"})))})]
