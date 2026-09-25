(local plugin (. (require :lib.lazy) :plugin))

(plugin :nvimdev/dashboard-nvim
        {:opts {:config {:header ["                                                     "
                                  "  ███╗   ██╗███████╗ ██████╗ ██╗   ██╗██╗███╗   ███╗ "
                                  "  ████╗  ██║██╔════╝██╔═══██╗██║   ██║██║████╗ ████║ "
                                  "  ██╔██╗ ██║█████╗  ██║   ██║██║   ██║██║██╔████╔██║ "
                                  "  ██║╚██╗██║██╔══╝  ██║   ██║╚██╗ ██╔╝██║██║╚██╔╝██║ "
                                  "  ██║ ╚████║███████╗╚██████╔╝ ╚████╔╝ ██║██║ ╚═╝ ██║ "
                                  "  ╚═╝  ╚═══╝╚══════╝ ╚═════╝   ╚═══╝  ╚═╝╚═╝     ╚═╝ "
                                  "                                                     "]
                         :center [{:action "lua require(\"fff\").find_files()"
                                   :desc " Find File"
                                   :icon " "
                                   :key :f}
                                  {:action "ene | startinsert"
                                   :desc " New File"
                                   :icon " "
                                   :key :n}
                                  {:action "lua Snacks.picker.recent()"
                                   :desc " Recent Files"
                                   :icon " "
                                   :key :r}
                                  {:action "lua require(\"fff\").live_grep()"
                                   :desc " Find Text"
                                   :icon " "
                                   :key :g}
                                  {:action "lua require(\"fff\").find_files({ cwd = vim.fn.stdpath(\"config\") })"
                                   :desc " Config"
                                   :icon " "
                                   :key :c}
                                  {:action "lua require(\"persistence\").load()"
                                   :desc " Restore Session"
                                   :icon " "
                                   :key :s}
                                  {:action :LazyExtras
                                   :desc " Lazy Extras"
                                   :icon " "
                                   :key :x}
                                  {:action :Lazy
                                   :desc " Lazy"
                                   :icon "󰒲 "
                                   :key :l}
                                  {:action (fn []
                                             (vim.api.nvim_input :<cmd>qa<cr>))
                                   :desc " Quit"
                                   :icon " "
                                   :key :q}]}}})
