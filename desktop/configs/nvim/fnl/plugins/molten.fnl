(local plugin (. (require :lib.lazy) :plugin))

;; Notebooks: jupytext.nvim opens .ipynb as a `# %%` Python buffer and writes
;; it back on save; molten runs that buffer's cells in a Jupyter kernel. Both
;; need python3 with pynvim, jupyter_client and jupytext from the Guix home
;; profile. Register a project kernel with
;;   .venv/bin/python -m ipykernel install --user --name <project>
;; then pick it with :MoltenInit.

;; The nixpkgs nvim wrapper sets loaded_python3_provider=0 with --cmd; undo it
;; so molten's remote plugin can start.
(set vim.g.loaded_python3_provider nil)

(local cell-marker "^# %%")

(fn cell-range []
  "First and last line of the `# %%` cell around the cursor."
  (let [cursor (vim.fn.line ".")
        last (vim.fn.line "$")
        lines (vim.api.nvim_buf_get_lines 0 0 -1 false)]
    (var start 1)
    (var stop last)
    (for [i cursor 1 -1 &until (> start 1)]
      (when (: (. lines i) :match cell-marker)
        (set start (+ i 1))))
    (for [i (+ cursor 1) last &until (< stop last)]
      (when (: (. lines i) :match cell-marker)
        (set stop (- i 1))))
    (values start stop)))

(fn run-cell []
  (let [(start stop) (cell-range)]
    (when (<= start stop)
      (vim.fn.MoltenEvaluateRange start stop))
    stop))

(fn run-cell-and-next []
  (let [stop (run-cell)]
    (vim.api.nvim_win_set_cursor 0 [(math.min (+ stop 2) (vim.fn.line "$")) 0])))

(fn run-all []
  (vim.api.nvim_win_set_cursor 0 [1 0])
  (while (let [stop (run-cell)]
           (when (< stop (vim.fn.line "$"))
             (vim.api.nvim_win_set_cursor 0 [(+ stop 2) 0])
             true))))

(fn map [lhs rhs desc buf mode]
  (vim.keymap.set (or mode :n) lhs rhs {:buffer buf :silent true : desc}))

(fn notebook-keys [buf]
  (map :<localleader>i ":MoltenInit<CR>" "Molten init kernel" buf)
  (map :<localleader>c run-cell "Molten run cell" buf)
  (map :<localleader>n run-cell-and-next "Molten run cell and next" buf)
  (map :<localleader>a run-all "Molten run all cells" buf)
  (map :<localleader>l ":MoltenEvaluateLine<CR>" "Molten run line" buf)
  (map :<localleader>r ":<C-u>MoltenEvaluateVisual<CR>gv<Esc>"
       "Molten run selection" buf :x)
  (map :<localleader>o ":noautocmd MoltenEnterOutput<CR>"
       "Molten enter output" buf)
  (map :<localleader>h ":MoltenHideOutput<CR>" "Molten hide output" buf)
  (map :<localleader>d ":MoltenDelete<CR>" "Molten delete cell" buf)
  (map :<localleader>x ":MoltenInterrupt<CR>" "Molten interrupt kernel" buf)
  (map :<localleader>R ":MoltenRestart!<CR>" "Molten restart kernel" buf))

[(plugin :GCBallesteros/jupytext.nvim
         {:lazy false :opts {:style :hydrogen :output_extension :auto}})
 (plugin :benlubas/molten-nvim
         {:version :^1.0.0
          :ft [:python]
          :build ":UpdateRemotePlugins"
          :init (fn []
                  (set vim.g.molten_output_win_max_height 20)
                  (set vim.g.molten_auto_open_output false)
                  (set vim.g.molten_wrap_output true)
                  (set vim.g.molten_virt_text_output true)
                  (set vim.g.molten_virt_lines_off_by_1 true)
                  (vim.api.nvim_create_autocmd :FileType
                                               {:pattern :python
                                                :callback (fn [args]
                                                            (notebook-keys args.buf))}))})]
