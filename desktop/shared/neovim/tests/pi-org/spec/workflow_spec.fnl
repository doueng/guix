;; Workflow (integration) test for pi-org: drive the full user journey
;; against the stub-pi process and assert on the transcript buffer, the
;; render.busy flag, and the winbar footer.
;;
;; nvim 0.12 no longer exposes nvim_ui_attach to in-process Lua, so we can't
;; screenstring-scrape the rendered grid. Instead we assert on:
;;   - transcript buffer lines (content correctness)
;;   - session.state.render.busy (spinner state)
;;   - the input-pane winbar (footer shows model; no spinner after turn ends)

(local session (require :pi-org.session))
(local helpers (require :helpers))
(local eq assert.are.same)
(local is-true assert.is_true)

(local fixtures vim.env.PI_ORG_FIXTURES_DIR)

(fn active? []
  (and session.active? (session.active?)))

(fn transcript-lines []
  (local buf session.state.trans-buf)
  (when (and buf (vim.api.nvim_buf_is_valid buf))
    (vim.api.nvim_buf_get_lines buf 0 -1 false)))

(fn input-winbar []
  (local win session.state.input-win)
  (when (and win (vim.api.nvim_win_is_valid win))
    (vim.api.nvim_get_option_value :winbar {:win win})))

(fn wait-for-model [?timeout]
  (vim.wait (or ?timeout 2000)
            (fn [] (not= session.state.info.model nil))
            50))

(describe "pi-org workflow: submit a prompt and stream a turn"
  (fn []
    (local ctx {})

    (before_each
      (fn []
        (when (active?) (session.close))
        (tset vim.env :PI_ORG_STUB_FIXTURE (.. fixtures "/turn-text-only.jsonl"))
        (vim.cmd :enew)
        (set vim.bo.modified false)
        (tset ctx :config (helpers.ensure-setup))))

    (after_each
      (fn []
        (when (active?) (session.close))))

    (it "echos the user prompt, streams the assistant reply, and clears busy"
      (fn []
        (session.open ctx.config)
        (is-true (wait-for-model) "stub model never arrived in footer")

        ;; Type a prompt into the input buffer (skip the "* Prompt" header line).
        (local input-buf session.state.input-buf)
        (is-true (and input-buf (vim.api.nvim_buf_is_valid input-buf)))
        (vim.api.nvim_buf_set_lines input-buf 0 -1 false
                                    [ "* Prompt" "" "hello from the test" ])
        (session.submit)

        ;; Wait for the streamed assistant text to appear, AND for agent_end
        ;; to clear the busy flag. "Hello world" appears after text_end, but
        ;; agent_end follows, so we poll on busy being cleared.
        (local ok (vim.wait 5000
                    (fn []
                      (and (vim.tbl_contains (transcript-lines) "Hello world")
                           session.state.render
                           (not session.state.render.busy)))
                    50))
        (is-true ok
          (.. "turn did not complete (text + busy cleared); lines:\n"
              (table.concat (transcript-lines) "\n")))

        (local l (transcript-lines))
        (is-true (vim.tbl_contains l "* User"))
        (is-true (vim.tbl_contains l "** Assistant"))
        (is-true (vim.tbl_contains l "Hello world"))

        ;; busy is cleared after agent_end.
        (is-true (not session.state.render.busy))

        ;; The winbar footer still shows the model (no spinner after turn).
        (local bar (input-winbar))
        (is-true (and bar (not= (: bar :match "stub%-model") nil)))
        (is-true (not (: bar :match "working")))

        ;; Close and confirm teardown.
        (session.close)
        (vim.wait 300)
        (is-true (not (active?)))))

    (it "sets render.busy=true while a turn is running"
      (fn []
        ;; The text-only fixture sleeps 300ms after agent_start, giving a window
        ;; to observe the busy state mid-turn.
        (session.open ctx.config)
        (is-true (wait-for-model))

        (local input-buf session.state.input-buf)
        (is-true (and input-buf (vim.api.nvim_buf_is_valid input-buf)))
        (vim.api.nvim_buf_set_lines input-buf 0 -1 false
                                    [ "* Prompt" "" "spin" ])
        (session.submit)

        (var saw-busy false)
        (vim.wait 2000
          (fn []
            (if (and session.state.render session.state.render.busy)
                (do (set saw-busy true) true)
                false))
          10)
        (is-true saw-busy "never observed render.busy=true during the turn")

        ;; Wait for the turn to finish so teardown is clean.
        (vim.wait 5000
          (fn []
            (or (= session.state.render nil)
                (not session.state.render.busy)))
          50)))))

(describe "pi-org workflow: tool call renders in the transcript"
  (fn []
    (local ctx {})

    (before_each
      (fn []
        (when (active?) (session.close))
        (tset vim.env :PI_ORG_STUB_FIXTURE (.. fixtures "/turn-with-tool.jsonl"))
        (vim.cmd :enew)
        (set vim.bo.modified false)
        (tset ctx :config (helpers.ensure-setup))))

    (after_each
      (fn []
        (when (active?) (session.close))))

    (it "renders a tool header, a result example block, and thinking"
      (fn []
        (session.open ctx.config)
        (is-true (wait-for-model))

        (local input-buf session.state.input-buf)
        (is-true (and input-buf (vim.api.nvim_buf_is_valid input-buf)))
        (vim.api.nvim_buf_set_lines input-buf 0 -1 false
                                    [ "* Prompt" "" "check the file" ])
        (session.submit)

        (local ok (vim.wait 5000
                    (fn []
                      (vim.tbl_contains (transcript-lines) "#+END_EXAMPLE"))
                    50))
        (is-true ok
          (.. "tool result block did not appear; lines:\n"
              (table.concat (transcript-lines) "\n")))

        (local l (transcript-lines))
        (is-true (vim.tbl_contains l "*** Tool: read"))
        (is-true (vim.tbl_contains l "#+BEGIN_EXAMPLE"))
        (is-true (vim.tbl_contains l "fn main() {"))
        ;; Thinking block (show_thinking defaults true).
        (is-true (vim.tbl_contains l "The user wants me to check the file."))

        (session.close)
        (vim.wait 300)))))
