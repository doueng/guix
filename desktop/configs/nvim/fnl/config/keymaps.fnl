(vim.keymap.set [:n :x] :j "v:count == 0 ? 'gj' : 'j'"
                {:expr true :silent true})

(vim.keymap.set [:n :x] :k "v:count == 0 ? 'gk' : 'k'"
                {:expr true :silent true})

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

(vim.keymap.set :n "<A-,>" :<cmd>BufferLineCyclePrev<cr> {:desc "Previous buffer"})
(vim.keymap.set :n :<A-.> :<cmd>BufferLineCycleNext<cr> {:desc "Next buffer"})

(vim.keymap.set :x :p :P {:desc "Paste without yanking the selection"})

;; Cmd is the clipboard key and Ctrl keeps Vim's meaning (docs/clipboard.html).
;; kanata sends Ctrl+Insert for Cmd+C and Shift+Delete for Cmd+X; Ghostty
;; pastes Cmd+V (Shift+Insert) itself.
(vim.keymap.set :x :<C-Insert> "\"+y" {:desc "Copy to clipboard"})
(vim.keymap.set :x :<S-Del> "\"+d" {:desc "Cut to clipboard"})

(require :config.shared-keys)
