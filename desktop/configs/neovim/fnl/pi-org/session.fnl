;; Session: owns the transcript buffer (org filetype), the input buffer
;; (org filetype), the window layout, and the wiring between the RPC client
;; and the renderer.
;;
;; One session per pi-org invocation. `open` creates the buffers + windows,
;; starts the RPC client, and subscribes the renderer to its events. `close`
;; tears it all down and stops pi.

(local rpc (require :pi-org.rpc))
(local render (require :pi-org.render))

(local M {})

;; Singleton state for the active session.
;;   :config    table
;;   :client    rpc client table
;;   :render    render state
;;   :trans-buf number
;;   :trans-win number|nil
;;   :input-buf number
;;   :input-win number|nil
;;   :orig-win  number|nil  window we took over (transcript)
;;   :orig-buf  number|nil  buffer that was in orig-win before takeover
;;   :info      table  {model, thinking, agent-mode, session} from get_state/ui
;;   :saved-wo  table  win -> {wrap, winbar} to restore on close
;;   :spinner   table  {timer, frame} for the working indicator
;;   :unsub     table  list of unsubscribe fns

(local state {:config nil
              :client nil
              :render nil
              :trans-buf nil
              :trans-win nil
              :input-buf nil
              :input-win nil
              :orig-win nil
              :orig-buf nil
              :info {:model nil :thinking nil :agent-mode nil :session nil}
              :saved-wo {}
              :spinner {:timer nil :frame 1}
              :unsub []})

(fn active? []
  (and state.client (rpc.alive? state.client)))

;; ----- buffer creation ----------------------------------------------------

;; Create a transcript buffer backed by a temp .org file. A pi-org session
;; owns at most one transcript buffer; on reopen after `close` we reuse the
;; existing buffer (clearing its contents) instead of allocating a new one.
;; Using a real temp file path (rather than a pi-org:// URI) keeps
;; nvim-orgmode happy: its mappings call get_current_file() which asserts
;; the buffer has a loadable file path.
(fn make-transcript-buf []
  (var buf state.trans-buf)
  (when (not (and buf (vim.api.nvim_buf_is_valid buf)))
    (set buf (vim.api.nvim_create_buf false true))
    (let [tmp (.. (vim.fn.tempname) ".org")]
      (vim.fn.writefile [] tmp)
      (vim.api.nvim_buf_set_name buf tmp)))
  (vim.api.nvim_set_option_value :swapfile false {: buf})
  (vim.api.nvim_set_option_value :bufhidden :hide {: buf})
  (vim.api.nvim_set_option_value :modifiable true {: buf})
  ;; Reset contents on reuse so a reopened session starts clean.
  (vim.api.nvim_buf_set_lines buf 0 -1 false [])
  buf)

;; See make-transcript-buf: reuse the existing input buffer on reopen to
;; avoid an E95 name clash, and reset its contents to the prompt header.
;; Uses a real temp .org file path so nvim-orgmode mappings work.
(fn make-input-buf []
  (var buf state.input-buf)
  (when (not (and buf (vim.api.nvim_buf_is_valid buf)))
    (set buf (vim.api.nvim_create_buf false true))
    (let [tmp (.. (vim.fn.tempname) ".org")]
      (vim.fn.writefile [] tmp)
      (vim.api.nvim_buf_set_name buf tmp)))
  (vim.api.nvim_set_option_value :swapfile false {: buf})
  (vim.api.nvim_set_option_value :bufhidden :hide {: buf})
  (vim.api.nvim_set_option_value :modifiable true {: buf})
  ;; A header headline so the input reads as org.
  (vim.api.nvim_buf_set_lines buf 0 -1 false ["* Prompt" "" ""])
  buf)

;; ----- window layout ------------------------------------------------------

(fn dim [value max]
  (if (= (type value) :string)
      (let [pct (value:match "^(%d+)%%$")]
        (if pct (math.floor (* max (/ (tonumber pct) 100))) (tonumber value)))
      (math.floor (or value (* max 0.8)))))

(fn pos [value size max]
  (let [p (if (= value :center)
              (math.floor (/ (- max size) 2))
              (dim value max))]
    (math.max 0 (math.min p (- max size)))))

;; ----- status line / spinner / wrapping ----------------------------------

(local spinner-frames ["⠋"
                       "⠙"
                       "⠹"
                       "⠸"
                       "⠼"
                       "⠴"
                       "⠦"
                       "⠧"
                       "⠇"
                       "⠏"])

;; Build the winbar string: agent-mode | model | thinking | working spinner.
(fn status-line []
  (let [info state.info
        parts []
        mode (or info.agent-mode :pi-org)
        model (or info.model "…")
        think (or info.thinking "")]
    (table.insert parts (.. "⟁ " mode))
    (table.insert parts model)
    (when (and think (not= think "")) (table.insert parts (.. "think:" think)))
    (when (and state.render state.render.busy)
      (let [frame (. spinner-frames state.spinner.frame)]
        (table.insert parts (.. frame " working…"))))
    (table.concat parts "  │  ")))

(fn refresh-status []
  (let [line (status-line)
        win state.input-win]
    ;; Footer lives only on the bottom (input) pane.
    (when (and win (vim.api.nvim_win_is_valid win))
      (vim.api.nvim_set_option_value :winbar line {: win}))))

(fn spinner-start []
  (when (not state.spinner.timer)
    (set state.spinner.frame 1)
    (refresh-status)
    (set state.spinner.timer (vim.uv.new_timer))
    (when state.spinner.timer
      (state.spinner.timer:start 120 120
                                 (vim.schedule_wrap (fn []
                                                      (set state.spinner.frame
                                                           (+ (% state.spinner.frame
                                                                 (length spinner-frames))
                                                              1))
                                                      (refresh-status)))))))

(fn spinner-stop []
  (when state.spinner.timer
    (state.spinner.timer:stop)
    (state.spinner.timer:close)
    (set state.spinner.timer nil))
  (refresh-status))

;; Save wrap/winbar so we can restore them when pi-org closes.
(fn save-wo [win]
  (tset state.saved-wo win
        {:wrap (vim.api.nvim_get_option_value :wrap {: win})
         :winbar (vim.api.nvim_get_option_value :winbar {: win})}))

(fn restore-wo [win]
  (let [saved (. state.saved-wo win)]
    (when (and saved (vim.api.nvim_win_is_valid win))
      (pcall vim.api.nvim_set_option_value :wrap saved.wrap {: win})
      (pcall vim.api.nvim_set_option_value :winbar saved.winbar {: win}))
    (tset state.saved-wo win nil)))

;; Attach the org filetype with the buffer as the current buffer, so the
;; nvim-orgmode ftplugin (which keys off nvim_get_current_buf) sets up
;; treesitter/folding/mappings on the right buffer.
(fn attach-org [win buf]
  (when (and win (vim.api.nvim_win_is_valid win) buf
             (vim.api.nvim_buf_is_valid buf))
    (vim.api.nvim_set_current_win win)
    (vim.api.nvim_set_option_value :filetype :org {: buf})))

;; Put the cursor at the end of the input body and enter insert mode, so the
;; user can start typing a prompt immediately.
(fn focus-input [st]
  (let [buf st.input-buf]
    (when (and buf (vim.api.nvim_buf_is_valid buf))
      (let [count (vim.api.nvim_buf_line_count buf)
            last-line (. (vim.api.nvim_buf_get_lines buf (- count 1) count
                                                     false)
                         1)]
        (vim.api.nvim_win_set_cursor st.input-win
                                     [count (length (or last-line ""))]))
      ;; startinsert must be deferred so the window/buffer switch settles.
      (vim.schedule (fn [] (vim.cmd :startinsert))))))

(fn open-windows []
  (let [cfg state.config.window
        trans-buf state.trans-buf
        input-buf state.input-buf]
    (if (= cfg.position :float)
        (let [w (dim cfg.float.width vim.o.columns)
              h (dim cfg.float.height (- vim.o.lines vim.o.cmdheight 1))
              r (pos cfg.float.row h (- vim.o.lines vim.o.cmdheight 1))
              c (pos cfg.float.col w vim.o.columns)
              trans-win (vim.api.nvim_open_win trans-buf true
                                               {:relative (or cfg.float.relative
                                                              :editor)
                                                :width w
                                                :height (math.floor (* h
                                                                       (- 1
                                                                          cfg.input_ratio)))
                                                :row r
                                                :col c
                                                :border (or cfg.float.border
                                                            :rounded)
                                                :style :minimal})
              input-win (vim.api.nvim_open_win input-buf false
                                               {:relative (or cfg.float.relative
                                                              :editor)
                                                :width w
                                                :height (math.floor (* h
                                                                       cfg.input_ratio))
                                                :row (+ r
                                                        (math.floor (* h
                                                                       (- 1
                                                                          cfg.input_ratio))))
                                                :col c
                                                :border (or cfg.float.border
                                                            :rounded)
                                                :style :minimal})]
          (tset state :trans-win trans-win)
          (tset state :input-win input-win)
          (attach-org trans-win trans-buf)
          (attach-org input-win input-buf)
          (vim.api.nvim_set_current_win input-win)
          (focus-input state))
        (let [vertical (not (not (cfg.position:match :vertical)))
              ;; The transcript takes over the current window (replacing the
              ;; file buffer, which we remember so :PiOrg toggle restores it).
              ;; Only the input pane is a new split, so we end up with exactly
              ;; two panes: transcript (large) + input (small), instead of a
              ;; three-way split that also keeps the old file window.
              orig-win (vim.api.nvim_get_current_win)
              orig-buf (vim.api.nvim_win_get_buf orig-win)
              _ (vim.api.nvim_win_set_buf orig-win trans-buf)
              trans-win orig-win
              ;; Split off the input pane below (horizontal) or to the right
              ;; (vertical) of the transcript.
              _ (vim.cmd (if vertical "belowright vsplit" "belowright split"))
              input-win (vim.api.nvim_get_current_win)
              _ (vim.api.nvim_win_set_buf input-win input-buf)
              _ (if vertical
                    (vim.cmd (.. "vertical resize "
                                 (math.floor (* vim.o.columns cfg.input_ratio))))
                    (vim.cmd (.. "resize "
                                 (math.floor (* vim.o.lines cfg.input_ratio)))))]
          (tset state :orig-win orig-win)
          (tset state :orig-buf orig-buf)
          (tset state :trans-win trans-win)
          (tset state :input-win input-win)
          ;; Attach the org filetype now that each buffer is shown in its
          ;; window (the nvim-orgmode ftplugin keys off the current buffer).
          (attach-org trans-win trans-buf)
          (attach-org input-win input-buf)
          (vim.api.nvim_set_current_win input-win)
          (focus-input state)))
    ;; Window cosmetics: hide numbers/signcolumn, enable visual line wrap,
    ;; and install the status footer (winbar) on the input pane only.
    (each [_ win (ipairs [state.trans-win state.input-win])]
      (when (and win (vim.api.nvim_win_is_valid win))
        (save-wo win)
        (when cfg.hide_numbers
          (vim.api.nvim_set_option_value :number false {: win})
          (vim.api.nvim_set_option_value :relativenumber false {: win}))
        (when cfg.hide_signcolumn
          (vim.api.nvim_set_option_value :signcolumn :no {: win}))
        (vim.api.nvim_set_option_value :wrap true {: win})))
    (refresh-status)))

(fn close-windows []
  (spinner-stop)
  ;; Close only the input split; the transcript window was the user's
  ;; original window (taken over), so restore the buffer it held before
  ;; pi-org opened and leave the window in place.
  (when state.trans-win (restore-wo state.trans-win))
  (when state.input-win (pcall vim.api.nvim_win_close state.input-win true))
  (tset state :input-win nil)
  (when (and state.trans-win (vim.api.nvim_win_is_valid state.trans-win)
             state.orig-buf)
    (pcall vim.api.nvim_win_set_buf state.trans-win state.orig-buf))
  (tset state :trans-win nil)
  (tset state :orig-win nil)
  (tset state :orig-buf nil))

;; ----- input submission ---------------------------------------------------

(fn read-input []
  (let [lines (vim.api.nvim_buf_get_lines state.input-buf 0 -1 false)]
    ;; Skip only the leading "* Prompt" header line; preserve any other
    ;; org-formatted text the user may have typed as their prompt.
    (if (and (> (length lines) 0) (= (. lines 1) "* Prompt"))
        (table.concat (vim.list_slice lines 2) "\n")
        (table.concat lines "\n"))))

(fn clear-input []
  (vim.api.nvim_buf_set_lines state.input-buf 0 -1 false ["* Prompt" "" ""]))

;; Render a user prompt in the transcript immediately (optimistic local echo).
;; Record the echoed text so the renderer's `message_start` user-echo handler
;; can skip the duplicate copy pi echoes back over RPC.
(fn render-user [text]
  (when state.render
    (set state.render.last-user-prompt text)
    (let [lines (vim.list_extend ["* User"] (vim.split text "\n" {:plain true}))]
      (table.insert lines "")
      (vim.api.nvim_buf_set_lines state.render.buf
                                  (vim.api.nvim_buf_line_count state.render.buf)
                                  -1 false lines))))

(fn submit []
  (when (active?)
    (let [text (read-input)]
      (if (or (= text "") (text:match "^%s*$"))
          (vim.notify "pi-org: empty prompt" vim.log.levels.WARN)
          (do
            ;; Render the prompt in the transcript immediately (optimistic).
            (render-user text)
            ;; If the agent is streaming, treat the message as a steering
            ;; message; otherwise a fresh prompt.
            (if (and state.render state.render.busy)
                (rpc.steer state.client text)
                (rpc.prompt state.client text))
            (clear-input))))))

;; ----- buffer-local keymaps ----------------------------------------------

(fn local-map-input [cfg]
  (let [buf state.input-buf]
    (when cfg.keymaps.submit
      (vim.keymap.set :i cfg.keymaps.submit (fn [] (vim.schedule submit))
                      {:buffer buf :desc "pi-org: submit prompt"})
      (vim.keymap.set :n cfg.keymaps.submit (fn [] (vim.schedule submit))
                      {:buffer buf :desc "pi-org: submit prompt"}))
    (when cfg.keymaps.abort
      (vim.keymap.set :n cfg.keymaps.abort
                      (fn [] (when (active?) (rpc.abort state.client)))
                      {:buffer buf :desc "pi-org: abort turn"})
      (vim.keymap.set :i cfg.keymaps.abort
                      (fn [] (when (active?) (rpc.abort state.client)))
                      {:buffer buf :desc "pi-org: abort turn"}))
    (when cfg.keymaps.new_session
      (vim.keymap.set :n cfg.keymaps.new_session
                      (fn [] (when (active?) (rpc.new-session state.client)))
                      {:buffer buf :desc "pi-org: new session"}))
    (when cfg.keymaps.cycle_model
      (vim.keymap.set :n cfg.keymaps.cycle_model
                      (fn []
                        (when (active?)
                          (rpc.cycle-model state.client)))
                      {:buffer buf :desc "pi-org: cycle model"}))))

;; ----- session lifecycle --------------------------------------------------

(fn open [config]
  (when vim.g.pi_org_debug
    (io.stderr:write (.. "[pi-org] open called, active?=" (tostring (active?))
                         " state.client=" (tostring state.client) "\n")))
  (if (active?)
      ;; Already running: toggle window visibility.
      (if (and state.trans-win (vim.api.nvim_win_is_valid state.trans-win))
          (close-windows)
          (open-windows))
      ;; Fresh start: create buffers, start pi, wire events.
      (let [cfg config
            trans-buf (make-transcript-buf)
            input-buf (make-input-buf)]
        (when vim.g.pi_org_debug
          (io.stderr:write (.. "[pi-org] fresh-start trans-buf="
                               (tostring trans-buf) " input-buf="
                               (tostring input-buf) "\n")))
        (tset state :config cfg)
        (tset state :trans-buf trans-buf)
        (tset state :input-buf input-buf)
        (tset state :render (render.new-state trans-buf cfg))
        (tset state :client (rpc.new cfg))
        ;; Subscribe the renderer to every event type it cares about.
        (let [handler (render.make-handler state.render)
              types [:agent_start
                     :agent_end
                     :message_start
                     :message_update
                     :tool_execution_end
                     :compaction_end
                     :auto_retry_start
                     :exit]]
          (each [_ t (ipairs types)]
            (table.insert state.unsub (rpc.on state.client t handler))))
        ;; Surface unexpected exits only; code 0/143 (SIGTERM during
        ;; teardown) is normal so we stay quiet for those.
        (table.insert state.unsub
                      (rpc.on state.client :exit
                              (fn [e]
                                (when (and e.code (not= e.code 0)
                                           (not= e.code 143))
                                  (vim.notify (.. "pi-org: pi exited (code "
                                                  (tostring e.code) ")")
                                              vim.log.levels.ERROR)))))
        ;; Spinner + status footer: start the working indicator when a turn
        ;; begins and stop it when the turn ends.
        (table.insert state.unsub
                      (rpc.on state.client :agent_start
                              (fn [_]
                                (when state.render (set state.render.busy true))
                                (spinner-start))))
        (table.insert state.unsub
                      (rpc.on state.client :agent_end
                              (fn [_]
                                (when state.render
                                  (set state.render.busy false))
                                (spinner-stop))))
        ;; The agent-mode-card extension widget carries the current agent
        ;; mode (e.g. DEFAULT) in its first widget line, with ANSI escapes.
        ;; Parse it out for the status footer.
        (table.insert state.unsub
                      (rpc.on state.client :extension_ui_request
                              (fn [event]
                                (when (and event.method
                                           (= event.method :setWidget)
                                           (= event.widgetKey :agent-mode-card)
                                           event.widgetLines)
                                  (let [raw (or (. event.widgetLines 1) "")
                                        stripped (raw:gsub "\027%[[0-9;]*m" "")
                                        mode (stripped:match "^(%S+)")]
                                    (when (and mode (not= mode ""))
                                      (tset state.info :agent-mode mode)
                                      (refresh-status)))))))
        ;; Open the windows BEFORE spawning pi so a layout failure fails
        ;; fast instead of orphaning a `pi --mode rpc` process with no UI.
        (let [(wok werr) (pcall open-windows)]
          (when (not wok)
            (when vim.g.pi_org_debug
              (io.stderr:write (.. "[pi-org] open-windows error: "
                                   (tostring werr) "\n")))
            ;; Tear down the subscriptions we already wired so a retry is clean.
            (each [_ unsub (ipairs state.unsub)] (when unsub (unsub)))
            (tset state :unsub [])
            (tset state :render nil)
            (tset state :client nil)
            (error (.. "pi-org: failed to open windows: " (tostring werr)))))
        (rpc.start state.client)
        ;; Ask pi for its current state so the footer can show model/thinking.
        (rpc.get-state state.client
                       {:on-result (fn [data]
                                     (when data
                                       (when (and data.model data.model.id)
                                         (tset state.info :model
                                               (.. (or data.model.provider "")
                                                   "/" data.model.id)))
                                       (when data.thinkingLevel
                                         (tset state.info :thinking
                                               data.thinkingLevel))
                                       (when data.sessionName
                                         (tset state.info :session
                                               data.sessionName))
                                       (refresh-status)))})
        ;; Keymaps local to the input buffer.
        (local-map-input cfg)
        (when vim.g.pi_org_debug
          (io.stderr:write (.. "[pi-org] open done, state addr="
                               (tostring state) " state.trans-buf="
                               (tostring state.trans-buf) " state.input-buf="
                               (tostring state.input-buf) "\n")))
        state)))

(fn close []
  (each [_ unsub (ipairs state.unsub)] (when unsub (unsub)))
  (tset state :unsub [])
  (when (and state.client (rpc.alive? state.client)) (rpc.stop state.client))
  (tset state :client nil)
  (close-windows)
  (tset state :render nil)
  ;; Reset carry-over singleton fields so the next session starts clean.
  ;; trans-buf/input-buf are intentionally kept (bufhidden=hide) and reused
  ;; by make-transcript-buf/make-input-buf on the next open to avoid an E95
  ;; buffer-name clash.
  (tset state :saved-wo {})
  (tset state :info {:model nil :thinking nil :agent-mode nil :session nil}))

;; ----- public API ---------------------------------------------------------

(tset M :state state)
(tset M :active? active?)
(tset M :open open)
(tset M :close close)
(tset M :submit submit)
(tset M :read-input read-input)
M
