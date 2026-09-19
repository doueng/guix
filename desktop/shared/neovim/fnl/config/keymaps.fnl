;; Better up/down
(vim.keymap.set [:n :x] :j "v:count == 0 ? 'gj' : 'j'"
                {:expr true :silent true})

(vim.keymap.set [:n :x] :k "v:count == 0 ? 'gk' : 'k'"
                {:expr true :silent true})

;; Move to window using the <ctrl> hjkl keys
(vim.keymap.set :n :<C-h> :<C-w>h {:desc "Go to left window" :remap true})
(vim.keymap.set :n :<C-j> :<C-w>j {:desc "Go to lower window" :remap true})
(vim.keymap.set :n :<C-k> :<C-w>k {:desc "Go to upper window" :remap true})
(vim.keymap.set :n :<C-l> :<C-w>l {:desc "Go to right window" :remap true})

;; Resize window using <ctrl> arrow keys
(vim.keymap.set :n :<C-Up> "<cmd>resize +2<cr>"
                {:desc "Increase window height"})

(vim.keymap.set :n :<C-Down> "<cmd>resize -2<cr>"
                {:desc "Decrease window height"})

(vim.keymap.set :n :<C-Left> "<cmd>vertical resize -2<cr>"
                {:desc "Decrease window width"})

(vim.keymap.set :n :<C-Right> "<cmd>vertical resize +2<cr>"
                {:desc "Increase window width"})

;; Move Lines
(vim.keymap.set :n :<A-j> "<cmd>m .+1<cr>==" {:desc "Move down"})
(vim.keymap.set :n :<A-k> "<cmd>m .-2<cr>==" {:desc "Move up"})
(vim.keymap.set :i :<A-j> "<esc><cmd>m .+1<cr>==gi" {:desc "Move down"})
(vim.keymap.set :i :<A-k> "<esc><cmd>m .-2<cr>==gi" {:desc "Move up"})
(vim.keymap.set :v :<A-j> ":m '>+1<cr>gv=gv" {:desc "Move down"})
(vim.keymap.set :v :<A-k> ":m '<-2<cr>gv=gv" {:desc "Move up"})

;; Clear search with <esc>
(vim.keymap.set [:i :n] :<esc> :<cmd>noh<cr><esc>
                {:desc "Escape and clear hlsearch"})

;; Save file
(vim.keymap.set [:i :x :n :s] :<C-s> :<cmd>w<cr><esc> {:desc "Save file"})

;; Better indenting
(vim.keymap.set :v "<" :<gv)
(vim.keymap.set :v ">" :>gv)

;; Buffer navigation
(pcall vim.keymap.del :n :<leader>bb)
(vim.keymap.set :n :<leader>bb
                "<cmd>Telescope buffers sort_mru=true ignore_current_buffer=true<cr>"
                {:desc "Search open buffers"})
(let [(ok which-key) (pcall require :which-key)]
  (when ok
    (which-key.add [{1 :<leader>b :group :buffer}
                    {1 :<leader>bb :desc "Search open buffers"}])))
(vim.keymap.set :n "<A-,>" :<cmd>BufferLineCyclePrev<cr> {:desc "Previous buffer"})
(vim.keymap.set :n :<A-.> :<cmd>BufferLineCycleNext<cr> {:desc "Next buffer"})

;; macOS-style clipboard operations (works with compositor Super key mappings)
(vim.keymap.set [:n :v] :<C-v> "\"+p" {:desc "Paste from clipboard"})
(vim.keymap.set :i :<C-v> :<C-r>+ {:desc "Paste from clipboard"})
(vim.keymap.set :c :<C-v> :<C-r>+
                {:desc "Paste from clipboard in command mode"})

(vim.keymap.set [:n :v] :<C-c> "\"+y" {:desc "Copy to clipboard"})
(vim.keymap.set [:n :v] :<C-x> "\"+d" {:desc "Cut to clipboard"})

(vim.api.nvim_create_user_command :Sourcegraph
                                  (fn []
                                    (let [file-path (vim.fn.expand "%:p")
                                          line-number (vim.fn.line ".")]
                                      (vim.fn.system [:sourcegraph.py
                                                      file-path
                                                      (tostring line-number)])))
                                  {:nargs 0})

(vim.keymap.set [:n :v] :<leader>gr ":Sourcegraph<CR>" {:silent false})
