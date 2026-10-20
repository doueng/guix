(vim.keymap.set [:n :x] :j "v:count == 0 ? 'gj' : 'j'"
                {:expr true :silent true})

(vim.keymap.set [:n :x] :k "v:count == 0 ? 'gk' : 'k'"
                {:expr true :silent true})

;; smart-splits moves between windows, then Herdr panes, then hands off to
;; keyleader for Hyprland. Resizing grows the Herdr pane once Neovim fills it.
(let [keylog (require :config.keylog)]
  (each [_ [key direction] (ipairs [[:h :left] [:j :down] [:k :up] [:l :right]])]
    (keylog.map (.. :<C- key ">") #((. (require :smart-splits) (.. :move_cursor_ direction)))
                (.. "Focus " direction) (.. :Ctrl+ key)))
  (each [_ [key direction] (ipairs [[:Left :left] [:Down :down] [:Up :up] [:Right :right]])]
    (keylog.map (.. :<C- key ">") #((. (require :smart-splits) (.. :resize_ direction)))
                (.. "Resize " direction) (.. :Ctrl+ key))))

(vim.keymap.set :n :<A-j> "<cmd>m .+1<cr>==" {:desc "Move down"})
(vim.keymap.set :n :<A-k> "<cmd>m .-2<cr>==" {:desc "Move up"})
(vim.keymap.set :i :<A-j> "<esc><cmd>m .+1<cr>==gi" {:desc "Move down"})
(vim.keymap.set :i :<A-k> "<esc><cmd>m .-2<cr>==gi" {:desc "Move up"})
(vim.keymap.set :v :<A-j> ":m '>+1<cr>gv=gv" {:desc "Move down"})
(vim.keymap.set :v :<A-k> ":m '<-2<cr>gv=gv" {:desc "Move up"})

(vim.keymap.set [:i :n] :<esc> :<cmd>noh<cr><esc>
                {:desc "Escape and clear hlsearch"})

(vim.keymap.set [:i :x :n :s] :<C-s> :<cmd>w<cr><esc> {:desc "Save file"})

(vim.keymap.set :v "<" :<gv)
(vim.keymap.set :v ">" :>gv)

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

(vim.keymap.set [:n :v] :<C-v> "\"+p" {:desc "Paste from clipboard"})
(vim.keymap.set :i :<C-v> :<C-r>+ {:desc "Paste from clipboard"})
(vim.keymap.set :c :<C-v> :<C-r>+
                {:desc "Paste from clipboard in command mode"})

(vim.keymap.set [:n :v] :<C-c> "\"+y" {:desc "Copy to clipboard"})
(vim.keymap.set [:n :v] :<C-x> "\"+d" {:desc "Cut to clipboard"})

(require :config.shared-keys)
