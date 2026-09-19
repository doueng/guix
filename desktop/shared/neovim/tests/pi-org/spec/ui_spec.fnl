;; UI tests for pi-org: assert on window layout, winbar status footer, and
;; buffer-local keymaps — all of which are queryable headlessly via window
;; options and nvim_win_* APIs (no virtual screen needed).
;;
;; nvim 0.12 removed in-process access to nvim_ui_attach, so screenstring
;; doesn't work headless. Instead we assert on:
;;   - winbar option of the input pane (the status footer)
;;   - window geometry (transcript vs input split sizes)
;;   - buffer-local keymaps on the input buffer
;;   - the original buffer being restored to the transcript window on close

(local session (require :pi-org.session))
(local helpers (require :helpers))
(local eq assert.are.same)
(local is-true assert.is_true)

(fn active? []
  (and session.active? (session.active?)))

(fn winbar [win]
  (when (and win (vim.api.nvim_win_is_valid win))
    (vim.api.nvim_get_option_value :winbar {:win win})))

(describe "pi-org UI layout"
  (fn []
    (before_each
      (fn []
        ;; Ensure no session is left over from a previous test (the session
        ;; module is a singleton).
        (when (active?) (session.close))
        (helpers.ensure-setup)
        ;; Start with a single empty buffer in the current window.
        (vim.cmd :enew)
        ;; Mark it unmodified so +qa teardown doesn't complain.
        (set vim.bo.modified false)))

    (after_each
      (fn []
        (when (active?) (session.close))
        ;; Clear any leftover state so tests are isolated.
        (pcall
          (fn []
            (each [_ w (ipairs (vim.api.nvim_list_wins))]
              (when (vim.api.nvim_win_is_valid w)
                (pcall vim.api.nvim_set_option_value :winbar "" {:win win})))))))

    (it "opens two panes (transcript + input) and a winbar footer on the input pane"
      (fn []
        (local cfg (helpers.ensure-setup))
        (local orig-win (vim.api.nvim_get_current_win))
        (session.open cfg)
        ;; Wait for the stub's get_state response so the footer model is set.
        (vim.wait 2000 (fn [] (not= session.state.info.model nil)) 50)

        (local trans-win session.state.trans-win)
        (local input-win session.state.input-win)
        (is-true (vim.api.nvim_win_is_valid trans-win))
        (is-true (vim.api.nvim_win_is_valid input-win))

        ;; The transcript took over the original window.
        (eq orig-win trans-win)

        ;; The winbar lives on the input pane only, and shows the stub model.
        (local input-bar (winbar input-win))
        (is-true (and input-bar (not= input-bar "")))
        (is-true (not= (: input-bar :match "stub%-model") nil))

        ;; The transcript pane has no pi-org winbar (the footer is input-only).
        (local trans-bar (winbar trans-win))
        ;; trans_win was the user's original window; its winbar may be "" or the
        ;; saved value, but it must NOT contain the pi-org spinner/model footer.
        (is-true (or (= trans-bar nil)
                     (= trans-bar "")
                     (not (: trans-bar :match "stub%-model"))))

        ;; Both panes should have wrap enabled (set by open-windows).
        (is-true (vim.api.nvim_get_option_value :wrap {:win trans-win}))
        (is-true (vim.api.nvim_get_option_value :wrap {:win input-win}))))

    (it "restores the original buffer to the transcript window on close"
      (fn []
        (local orig (vim.api.nvim_create_buf false true))
        (vim.api.nvim_buf_set_lines orig 0 -1 false [ "before pi-org" ])
        (vim.api.nvim_set_current_buf orig)
        (local orig-win (vim.api.nvim_get_current_win))

        (session.open (helpers.ensure-setup))
        (vim.wait 2000 (fn [] (not= session.state.info.model nil)) 50)

        (eq orig-win session.state.trans-win)
        (eq session.state.trans-buf (vim.api.nvim_win_get_buf orig-win))

        (session.close)
        (vim.wait 300)

        (is-true (vim.api.nvim_win_is_valid orig-win))
        (eq orig (vim.api.nvim_win_get_buf orig-win))))

    (it "installs the submit/abort keymaps on the input buffer"
      (fn []
        (session.open (helpers.ensure-setup))
        (vim.wait 2000 (fn [] (not= session.state.info.model nil)) 50)

        (local input-buf session.state.input-buf)
        (fn has-buf-map [mode lhs]
          (local maps (vim.api.nvim_buf_get_keymap input-buf mode))
          (local lhs-lower (: lhs :lower))
          (var found false)
          (each [_ m (ipairs maps)]
            (when (and m.lhs (= (: m.lhs :lower) lhs-lower))
              (set found true)))
          found)

        ;; Default keymaps from config: <C-n> submit, <C-c> abort.
        (is-true (has-buf-map :i "<C-n>"))
        (is-true (has-buf-map :n "<C-n>"))
        (is-true (has-buf-map :i "<C-c>"))
        (is-true (has-buf-map :n "<C-c>"))))))
