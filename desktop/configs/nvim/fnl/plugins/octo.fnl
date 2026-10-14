(local lazy-spec (require :lib.lazy))
(local plugin lazy-spec.plugin)
(local key lazy-spec.key)

[(plugin :pwntester/octo.nvim
         {:enabled (fn [] (= (vim.fn.executable :gh) 1))
          :cmd :Octo
          :dependencies [:nvim-lua/plenary.nvim
                         :folke/snacks.nvim
                         :nvim-tree/nvim-web-devicons]
          :keys [(key :<leader>oo :<cmd>Octo<cr> {:desc "Octo actions"})]
          :opts {:picker :snacks
                 :enable_builtin true
                 :default_remote [:github :upstream :origin]
                 :timeout 30000
                 :mappings {:pull_request {:checkout_pr {:lhs :<leader>ok
                                                         :desc "checkout PR"}
                                           :list_changed_files {:lhs :<leader>oc
                                                                :desc "list changed files"}
                                           :show_pr_diff {:lhs :<leader>od
                                                          :desc "show PR diff"}}
                            :review_diff {:focus_files {:lhs :<leader>oc
                                                        :desc "focus changed files"}
                                          :toggle_files {:lhs :<leader>ob
                                                         :desc "toggle changed files"}}
                            :file_panel {:focus_files {:lhs :<leader>oc
                                                       :desc "focus changed files"}
                                         :toggle_files {:lhs :<leader>ob
                                                        :desc "toggle changed files"}}}}})]
