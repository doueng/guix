;; Basic editor options
(vim.filetype.add {:extension {:bb :clojure} :filename {:bb.edn :clojure}})

(set vim.opt.number false)
(set vim.opt.relativenumber false)
(set vim.opt.mouse "")
(set vim.opt.ignorecase true)
(set vim.opt.smartcase true)
(set vim.opt.hlsearch false)
(set vim.opt.incsearch true)
(set vim.opt.showcmd false)
(set vim.opt.wrap false)
(set vim.opt.breakindent true)
(set vim.opt.tabstop 2)
(set vim.opt.shiftwidth 2)
(set vim.opt.expandtab true)
(set vim.opt.autoindent true)
(set vim.opt.smartindent true)
(set vim.opt.hidden true)
(set vim.opt.confirm true)
(set vim.opt.autoread true)

(set vim.opt.timeout true)
(set vim.opt.ttimeout true)
(set vim.opt.ttimeoutlen 10)

(set vim.opt.synmaxcol 240)
(set vim.opt.redrawtime 1500)

(set vim.opt.completeopt [:menu :menuone :noselect])
(set vim.opt.pumheight 10)

(set vim.opt.scrolloff 8)
(set vim.opt.sidescrolloff 8)
(set vim.opt.smoothscroll true)

(set vim.opt.ignorecase true)
(set vim.opt.smartcase true)
(set vim.opt.hlsearch false)

(set vim.opt.swapfile false)
(set vim.opt.undofile true)
(set vim.opt.backup false)
(set vim.opt.writebackup true)

;; Smooth movement/redraws: avoid per-cursor-line highlights.
(set vim.opt.cursorline false)

;; Disable folding completely. LazyVim defaults to Treesitter foldexpr, which can
;; add redraw/movement overhead even when folds are not actively used.
(set vim.opt.foldenable false)
(set vim.opt.foldcolumn :0)
(set vim.opt.foldlevel 99)
(set vim.opt.foldlevelstart 99)
(set vim.opt.foldmethod :manual)
(set vim.opt.foldexpr :0)

(set vim.opt.conceallevel 0)

;; Visual
(set vim.opt.list false)
(set vim.opt.termguicolors true)
(fn apply-hidden-cursor-highlight []
  ;; `blend` alone does not materialize as a real highlight attr in Nvim;
  ;; include a dummy bg so the TUI sees hl_blend=100 and hides the cursor.
  (vim.api.nvim_set_hl 0 :HiddenCursor {:bg "#000000" :blend 100}))

(apply-hidden-cursor-highlight)
(vim.api.nvim_create_autocmd :ColorScheme
                             {:group (vim.api.nvim_create_augroup :HiddenCursorHighlight
                                                                  {:clear true})
                              :callback apply-hidden-cursor-highlight})

(set vim.opt.guicursor "n-v-c-sm:block,i-ci-ve:ver25-HiddenCursor,r-cr-o:hor20")
(set vim.opt.signcolumn :yes)
(set vim.opt.updatetime 250)
(set vim.opt.timeoutlen 300)

;; Splits
(set vim.opt.splitright true)
(set vim.opt.splitbelow true)

(fn command-copy [script register]
  (fn [lines regtype]
    (let [text (table.concat lines "\n")]
      (vim.fn.system [script register]
                     (if (= regtype :V)
                         (.. text "\n")
                         text)))))

(fn command-paste [script register]
  (fn []
    (let [text (vim.fn.system [script register])
          regtype (if (= (text:sub -1) "\n") :V :v)
          lines (vim.split text "\n" {:plain true})]
      (when (and (= regtype :V) (= (. lines (length lines)) ""))
        (table.remove lines (length lines)))
      (values lines regtype))))

(set vim.g.clipboard {:name "Wayland"
                      :copy {:+ [:wl-copy "--type" "text/plain"]
                             :* [:wl-copy "--primary" "--type" "text/plain"]}
                      :paste {:+ [:wl-paste "--no-newline"]
                              :* [:wl-paste "--primary" "--no-newline"]}
                      :cache_enabled 0})

(vim.api.nvim_create_autocmd :TextYankPost
                             {:callback (fn []
                                          (vim.highlight.on_yank))})

(fn autosave-current-file []
  (let [buf (vim.api.nvim_get_current_buf)]
    (when (and (vim.api.nvim_buf_is_valid buf) (. vim.bo buf :modified)
               (. vim.bo buf :modifiable) (not (. vim.bo buf :readonly))
               (= (. vim.bo buf :buftype) "")
               (not= (vim.api.nvim_buf_get_name buf) ""))
      (let [(ok err) (pcall vim.cmd "silent update")]
        (when (not ok)
          (vim.notify (.. "Autosave failed: " (tostring err))
                      vim.log.levels.WARN))))))

(vim.api.nvim_create_autocmd [:FocusLost :BufLeave :InsertLeave :VimSuspend]
                             {:group (vim.api.nvim_create_augroup :IntelliJLikeAutosave
                                                                  {:clear true})
                              :callback autosave-current-file})

(vim.api.nvim_create_autocmd [:FocusGained :BufEnter :CursorHold :CursorHoldI]
                             {:group (vim.api.nvim_create_augroup :IntelliJLikeAutoread
                                                                  {:clear true})
                              :command :checktime})

(set vim.opt.clipboard :unnamedplus)

(fn cd-project-root []
  (let [cwd (vim.uv.cwd)
        marker (. (vim.fs.find [:.jj
                                :.git
                                :flake.nix
                                :package.json
                                :pyproject.toml]
                               {:path cwd :upward true}) 1)]
    (when marker
      (let [root (vim.fs.dirname marker)]
        (when (and root (not= root ""))
          (vim.cmd.cd root))))))

(fn open-explorer []
  (vim.schedule (fn []
                  (let [(ok snacks) (pcall require :snacks)]
                    (when ok
                      (if snacks.explorer
                          (snacks.explorer)
                          (when (and snacks.picker snacks.picker.explorer)
                            (snacks.picker.explorer {:cwd (vim.fn.getcwd)}))))))))

(fn restore-last-session? []
  (or (= vim.g.restore_last_session true) (= vim.g.restore_last_session 1)
      (= vim.env.NVIM_RESTORE_LAST_SESSION :1)
      (= vim.env.NVIM_RESTORE_LAST_SESSION :true)))

(vim.api.nvim_create_autocmd :VimEnter
                             {:callback (fn []
                                          (when (= (vim.fn.argc) 0)
                                            (cd-project-root)
                                            (if (restore-last-session?)
                                                (vim.schedule (fn []
                                                                (let [(ok persistence) (pcall require
                                                                                              :persistence)]
                                                                  (when ok
                                                                    (persistence.load {:last true})))))
                                                ;; Skip the auto-explorer when launching straight into
                                                ;; pi-org (e.g. the `piorg` fish function sets this).
                                                (if vim.g.pi_org_skip_explorer
                                                    nil
                                                    (open-explorer)))))})
