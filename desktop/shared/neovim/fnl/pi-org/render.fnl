;; Render pi agent events as org-mode text appended to the transcript buffer.
;;
;; The transcript is a normal `org` filetype buffer managed by session.fnl.
;; This module owns the *content* logic: turn each event into org text and
;; append it at the right mark. We keep a per-client accumulator of "in
;; flight" assistant message state so streaming deltas can be rewritten
;; in-place rather than appended repeatedly.
;;
;; Org conventions used:
;;   * User prompt      -> "* User" headline, body = prompt text
;;   * Assistant turn   -> "** Assistant" subheadline; text under it; thinking
;;                          in a folded #+BEGIN_SRC org block; tool calls in
;;                          #+BEGIN_SRC :language blocks (best-effort lang).
;;   * Tool results     -> "#+BEGIN_EXAMPLE ... #+END_EXAMPLE" under the call.
;;   * Errors / notices -> italic lines.

(local M {})

;; Per-client streaming state.
;;   :buf        number  transcript buffer id
;;   :config     table   pi-org config (for org rendering flags)
;;   :mark       number  extmark id anchoring the live assistant block
;;   :cur-tool   table|nil  {call-id, name, start-row} for the active tool call
;;   :tool-blocks table  call-id -> {start-row, end-row} so results can be inserted
;;   :busy        boolean true while an agent turn is streaming

(fn new-state [buf config]
  {: buf
   : config
   :ns (vim.api.nvim_create_namespace :pi-org)
   :mark nil
   ; extmark (left-gravity) at start of streaming text
   :end-mark nil
   ; extmark (right-gravity) just past streaming text
   :text ""
   ; accumulated streaming text for current assistant msg
   :text-row nil
   ; row where streaming text starts
   :cur-tool nil
   :tool-blocks {}
   :last-user-prompt nil
   :busy false})

;; ----- low-level buffer helpers -------------------------------------------

(fn set-lines [state lines row col]
  (vim.api.nvim_buf_set_lines state.buf row col false lines))

(fn line-count [state]
  (vim.api.nvim_buf_line_count state.buf))

(fn append-lines [state lines]
  (let [n (line-count state)]
    (set-lines state lines n n)
    n))

;; Wrap a string at width (nil = no wrap). Returns a list of lines.
(fn wrap [text width]
  (if (or (not width) (<= width 0))
      (vim.split text "\n" {:plain true})
      (let [out []
            paragraphs (vim.split text "\n" {:plain true})]
        (each [_ para (ipairs paragraphs)]
          (if (= para "")
              (table.insert out "")
              (let [words (vim.split para " " {:plain true})]
                (var line "")
                (each [_ w (ipairs words)]
                  (if (or (= line "") (<= (+ (length line) 1 (length w)) width))
                      (set line (if (= line "") w (.. line " " w)))
                      (do
                        (table.insert out line)
                        (set line w))))
                (when (not= line "")
                  (table.insert out line)))))
        out)))

;; ----- org block helpers --------------------------------------------------

(fn src-block [language body]
  (let [lines [(.. "#+BEGIN_SRC " (or language ""))]]
    (each [_ l (ipairs (vim.split (or body "") "\n" {:plain true}))]
      (table.insert lines l))
    (table.insert lines "#+END_SRC")
    lines))

(fn example-block [body]
  (let [lines ["#+BEGIN_EXAMPLE"]]
    (each [_ l (ipairs (vim.split (or body "") "\n" {:plain true}))]
      (table.insert lines l))
    (table.insert lines "#+END_EXAMPLE")
    lines))

;; Best-effort: extract a language hint from a tool name + args.
(fn tool-language [tool-name args]
  (case tool-name
    (where s (or (= s :edit) (= s :write))) (let [ext (and args.path
                                                           (vim.fn.fnamemodify args.path
                                                                               ":e"))]
                                             (if (and ext (not= ext ""))
                                                 ext
                                                 :text))
    _ :text))

;; ----- event handlers -----------------------------------------------------

(fn on-agent-start [state]
  (set state.busy true)
  (set state.cur-tool nil)
  (tset state :tool-blocks {}))

(fn on-agent-end [state]
  (set state.busy false)
  (set state.mark nil)
  (set state.end-mark nil)
  (set state.cur-tool nil)
  (append-lines state [""]))

;; A user prompt: render as a top-level "* User" headline.
(fn render-user-prompt [state message]
  (let [lines (vim.list_extend [(.. "* User")]
                               (wrap (or message "")
                                     state.config.org.wrap_width))]
    (table.insert lines "")
    (append-lines state lines)))

;; message_start with role=user (pi emits user echoes too): render once.
(fn on-message-start [state event]
  (let [msg (or event.message {})
        role msg.role]
    (case role
      :user (do
              (when (and (= (type msg.content) :string) (not= msg.content "")
                         (not= msg.content state.last-user-prompt))
                (render-user-prompt state msg.content))
              ;; Clear the local-echo dedup flag after pi's user-echo arrives.
              (set state.last-user-prompt nil))
      :assistant (do
                   ;; Begin an "** Assistant" block. Insert a blank line for streaming
                   ;; text and anchor extmarks at its start (left-gravity, stays put)
                   ;; and end (right-gravity, rides the line below) so deltas can
                   ;; replace only the text region without clobbering later tool blocks.
                   (append-lines state ["** Assistant" ""])
                   (let [row (- (line-count state) 2)
                         text-row (+ row 1)]
                     (set state.text-row text-row)
                     (set state.text "")
                     (set state.mark
                          (vim.api.nvim_buf_set_extmark state.buf state.ns
                                                        text-row 0
                                                        {:right_gravity false}))
                     (set state.end-mark
                          (vim.api.nvim_buf_set_extmark state.buf state.ns
                                                        text-row 0
                                                        {:right_gravity true}))))
      _ nil)))

;; Streaming text deltas: accumulate and rewrite the text block in place,
;; bounded by the start/end extmarks so we don't touch later content.
(fn on-text-delta [state event]
  (when (and state.mark state.end-mark event.assistantMessageEvent)
    (let [delta event.assistantMessageEvent.delta]
      (when (and delta (not= delta ""))
        (set state.text (.. state.text delta))
        (let [lines (wrap state.text state.config.org.wrap_width)
              start-info (vim.api.nvim_buf_get_extmark_by_id state.buf state.ns
                                                             state.mark {})
              end-info (vim.api.nvim_buf_get_extmark_by_id state.buf state.ns
                                                           state.end-mark {})
              start-row (. start-info 1)
              end-row (. end-info 1)]
          ;; Replace [start-row, end-row) with the new lines. The end-mark
          ;; is right-gravity so it sits on the line *after* the text; if
          ;; end-row equals start-row the region was empty.
          (set-lines state lines start-row end-row))))))

;; text_end: ensure a trailing blank line after the streaming text.
(fn on-text-end [state _event]
  (when (and state.mark state.end-mark)
    (let [end-info (vim.api.nvim_buf_get_extmark_by_id state.buf state.ns
                                                       state.end-mark {})
          end-row (. end-info 1)
          start-info (vim.api.nvim_buf_get_extmark_by_id state.buf state.ns
                                                         state.mark {})
          start-row (. start-info 1)
          lines (vim.api.nvim_buf_get_lines state.buf start-row end-row false)
          last (and (> (length lines) 0) (. lines (length lines)))]
      (when (or (= last nil) (not= last ""))
        (set-lines state [""] end-row end-row))))
  ;; Clear marks so the next assistant message starts fresh.
  (set state.mark nil)
  (set state.end-mark nil))

;; Thinking block: render as a folded src block under the assistant headline.
(fn on-thinking-end [state event]
  (when (and state.config.org.show_thinking event.assistantMessageEvent)
    (let [content event.assistantMessageEvent.content]
      (when (and content (not= content ""))
        (append-lines state (src-block :text content))))))

;; A tool call completes: render a labeled src block, remember its row range.
(fn on-toolcall-end [state event]
  (let [ev (or event.assistantMessageEvent {})
        call ev.toolCall]
    (when call
      (let [name call.name
            args (or call.arguments {})
            body (if state.config.org.show_tool_args
                     (vim.json.encode args {:indent "  "})
                     "")
            header (.. "*** Tool: " (tostring name))
            _ (append-lines state [header])
            start-row (line-count state)
            _ (append-lines state (src-block (tool-language name args) body))
            ;; end-row points at the #+END_SRC line; +1 at replacement time
            ;; makes the range exclusive of the following line.
            end-row (- (line-count state) 1)]
        (tset state.tool-blocks call.id {: start-row : end-row})))))

;; Tool execution: streaming updates replace the block content in place.
(fn on-tool-execution-end [state event]
  (let [block (. state.tool-blocks event.toolCallId)]
    (when block
      (let [result (or event.result {})
            content (or result.content [])
            text-lines []]
        (each [_ item (ipairs content)]
          (when (and (= (type item) :table) (= item.type :text) item.text)
            (each [_ l (ipairs (vim.split item.text "\n" {:plain true}))]
              (table.insert text-lines l))))
        (when state.config.org.show_tool_results
          (let [new-lines (example-block (table.concat text-lines "\n"))]
            ;; Replace the existing src block with an example block of results.
            (set-lines state new-lines block.start-row (+ block.end-row 1))))))))

;; compaction: render a brief notice.
(fn on-compaction-end [state event]
  (append-lines state ["/Compacted context./" ""]))

;; errors / retries: short italic notices.
(fn on-auto-retry-start [state event]
  (append-lines state
                [(.. "/Retrying (attempt " (tostring event.attempt) "): "
                     (or event.errorMessage "") "/")]))

;; ----- dispatcher ---------------------------------------------------------

;; Returns a single handler bound to a state, suitable for rpc.on.
(fn make-handler [state]
  (fn [event]
    (let [(ok err) (pcall (fn []
                            (case event.type
                              :agent_start (on-agent-start state event)
                              :agent_end (on-agent-end state event)
                              :message_start (on-message-start state event)
                              :message_update (let [ev event.assistantMessageEvent]
                                                (when ev
                                                  (case ev.type
                                                    :text_delta (on-text-delta state
                                                                               event)
                                                    :text_end (on-text-end state
                                                                           event)
                                                    :thinking_end (on-thinking-end state
                                                                                   event)
                                                    :toolcall_end (on-toolcall-end state
                                                                                   event)
                                                    _ nil)))
                              :tool_execution_end (on-tool-execution-end state
                                                                         event)
                              :compaction_end (on-compaction-end state event)
                              :auto_retry_start (on-auto-retry-start state
                                                                     event)
                              _ nil)))]
      (when (not ok)
        (vim.notify (.. "pi-org render: " (tostring err)) vim.log.levels.ERROR)
        (when vim.g.pi_org_debug
          (io.stderr:write (.. "[pi-org] render error: " (tostring err) "\n")))))))

(tset M :new-state new-state)
(tset M :make-handler make-handler)
M
