(local path (or vim.env.KEYLOG_FILE
                (.. (or vim.env.XDG_STATE_HOME (.. vim.env.HOME :/.local/state))
                    :/keybindings/keys.tsv)))

(fn clean [s]
  (pick-values 1 (: (tostring s) :gsub "[\t\n]" " ")))

(fn log [keys effect]
  "Append which KEYS ran and their EFFECT in Neovim. Never typed text."
  (vim.fn.mkdir (vim.fn.fnamemodify path ":h") :p)
  (match (io.open path :a)
    file (do
           (file:write (table.concat [(os.date "%Y-%m-%dT%H:%M:%S") :Neovim
                                      (clean keys) (clean effect)]
                                     "\t")
                       "\n")
           (file:close))))

(fn map [lhs action desc keys]
  (vim.keymap.set :n lhs
                  (fn []
                    (log keys desc)
                    (if (= (type action) :function)
                        (action)
                        (vim.api.nvim_feedkeys (vim.api.nvim_replace_termcodes action
                                                                               true
                                                                               false
                                                                               true)
                                               :n false)))
                  {: desc :silent true}))

{: log : map : path}
