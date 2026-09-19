(local fff (require :fff))
(local uv (or vim.uv vim.loop))

(fn cwd []
  (or (and uv uv.cwd (uv.cwd)) (vim.fn.getcwd)))

(fn root-dir []
  (if (and _G.LazyVim (= (type _G.LazyVim.root) :function))
      (_G.LazyVim.root)
      (cwd)))

(fn normalize-range [start-pos end-pos]
  (if (or (> (. start-pos 1) (. end-pos 1))
          (and (= (. start-pos 1) (. end-pos 1))
               (> (. start-pos 2) (. end-pos 2))))
      (values end-pos start-pos)
      (values start-pos end-pos)))

(fn visual-selection []
  (var start-pos (vim.api.nvim_buf_get_mark 0 "<"))
  (var end-pos (vim.api.nvim_buf_get_mark 0 ">"))
  (when (or (= (. start-pos 1) 0) (= (. end-pos 1) 0))
    (lua "return nil"))
  (let [(normalized-start normalized-end) (normalize-range start-pos end-pos)]
    (set start-pos normalized-start)
    (set end-pos normalized-end))
  (let [lines (vim.api.nvim_buf_get_text 0 (- (. start-pos 1) 1)
                                         (. start-pos 2) (- (. end-pos 1) 1)
                                         (+ (. end-pos 2) 1) [])]
    (when (= (length lines) 0)
      (lua "return nil"))
    (let [joined (table.concat lines " ")]
      (-> joined
          (: :gsub "%s+" " ")
          (: :gsub "^%s+" "")
          (: :gsub "%s+$" "")))))

(fn cword-query []
  (vim.fn.expand :<cword>))

(fn current-file-dir []
  (let [path (vim.api.nvim_buf_get_name 0)]
    (if (= path "")
        (cwd)
        (vim.fn.fnamemodify path ":p:h"))))

(fn picker-dir [use-root]
  (if use-root (root-dir) (cwd)))

(fn historical-query [grep?]
  (let [(ok-core core) (pcall (fn []
                                ((. (require :fff.core) :ensure_initialized))))]
    (when ok-core
      (let [history-fn (if grep? core.get_historical_grep_query core.get_historical_query)
            (ok-query query) (pcall history-fn 0)]
        (when (and ok-query query (not= query ""))
          query)))))

(fn find-files [use-root]
  (fn []
    (let [picker-opts {:cwd (picker-dir use-root)}
          query (historical-query false)]
      (when query
        (tset picker-opts :query query))
      (fff.find_files picker-opts))))

(fn find-files-current-file-dir []
  (fn []
    (let [picker-opts {:cwd (current-file-dir)}
          query (historical-query false)]
      (when query
        (tset picker-opts :query query))
      (fff.find_files picker-opts))))

(fn live-grep [use-root opts]
  (let [opts (or opts {})]
    (fn []
      (let [picker-opts (vim.tbl_deep_extend :force
                                             {:cwd (picker-dir use-root)} opts)
            query (if opts.query (opts.query) (historical-query true))]
        (set picker-opts.query (if (and query (not= query "")) query nil))
        (fff.live_grep picker-opts)))))

(fn snacks-picker [name fallback]
  (fn []
    (let [(ok-snacks snacks) (pcall require :snacks)]
      (if (and ok-snacks snacks.picker (. snacks.picker name))
          ((. snacks.picker name))
          (when fallback
            (fallback))))))

(fff.setup {:lazy_sync true
            :prompt_vim_mode true
            :layout {:prompt_position :bottom
                     :preview_position :right
                     :flex {:size 130 :wrap :top}}
            ;; Match the Snacks picker/explorer background override: use the
            ;; regular editor background instead of NormalFloat.
            :hl {:normal :Normal}
            :preview {:enabled true
                      :filetypes {:markdown {:wrap_lines true}
                                  :text {:wrap_lines true}}}
            :grep {:modes [:plain :regex :fuzzy]}})

;; <leader><space> is reserved for the Snacks command palette.
;; Use <leader>ff for project files.
(vim.keymap.set :n :<leader>ff (find-files true)
                {:desc "Find Files (Root Dir)"})

(vim.keymap.set :n :<leader>. (find-files-current-file-dir)
                {:desc "Find Files (Current File Dir)"})

(vim.keymap.set :n :<leader>f. (find-files-current-file-dir)
                {:desc "Find Files (Current File Dir)"})

(vim.keymap.set :n :<leader>fF (find-files false) {:desc "Find Files (cwd)"})
(vim.keymap.set :n :<leader>fb
                (snacks-picker :buffers (fn [] (vim.cmd :buffers)))
                {:desc :Buffers})

(vim.keymap.set :n :<leader>fh (snacks-picker :help (fn [] (vim.cmd :help)))
                {:desc "Help Pages"})

(vim.keymap.set :n :<leader>fg (live-grep true) {:desc "Grep (Root Dir)"})
(vim.keymap.set :n :<leader>/ (live-grep true) {:desc "Grep (Root Dir)"})
(vim.keymap.set :n :<leader>sg (live-grep true) {:desc "Grep (Root Dir)"})
(pcall vim.keymap.del :n :<leader>sp)
(vim.keymap.set :n :<leader>sp (live-grep true) {:desc "Search Project"})
(vim.keymap.set :n :<leader>sG (live-grep false) {:desc "Grep (cwd)"})
(vim.keymap.set :n :<leader>sw (live-grep true {:query cword-query})
                {:desc "Word (Root Dir)"})

(vim.keymap.set :x :<leader>sw (live-grep true {:query visual-selection})
                {:desc "Selection (Root Dir)"})

(vim.keymap.set :n :<leader>sW (live-grep false {:query cword-query})
                {:desc "Word (cwd)"})

(vim.keymap.set :x :<leader>sW (live-grep false {:query visual-selection})
                {:desc "Selection (cwd)"})

(vim.keymap.set :n :<leader>fw (live-grep true {:query cword-query})
                {:desc "Find Word"})
