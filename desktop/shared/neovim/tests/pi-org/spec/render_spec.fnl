;; Unit tests for pi-org.render: feed scripted agent events through
;; make-handler and assert on the transcript buffer's org-mode text.
;;
;; These tests use a real scratch buffer (nvim_buf_set_lines etc.) but do NOT
;; attach a UI or open windows — render only touches the buffer, not the
;; screen layout.
;;
;; Scratch buffers start with one empty line, so appended content begins at
;; line 2. We filter empty lines via the content helper for content checks.

(local render (require :pi-org.render))
(local config (require :pi-org.config))
(local eq assert.are.same)
(local is-true assert.is_true)

;; Build a render state backed by a fresh scratch buffer + a config with
;; defaults (show_thinking/show_tool_args/show_tool_results all true).
(fn fresh-state []
  (local buf (vim.api.nvim_create_buf false true))
  (local cfg (config.parse {:command :pi} true))
  (values (render.new-state buf cfg) buf))

(fn lines [buf]
  (vim.api.nvim_buf_get_lines buf 0 -1 false))

;; Return all non-empty lines (filters trailing/leading empties for content checks).
(fn content [buf]
  (local l (lines buf))
  (local out [])
  (each [_ line (ipairs l)]
    (when (not= line "")
      (table.insert out line)))
  out)

;; Drive a list of events through the handler.
(fn play [st events]
  (local handler (render.make-handler st))
  (each [_ e (ipairs events)]
    (handler e)))

(describe "pi-org.render user prompt"
  (fn []
    (it "renders a * User headline with the prompt body"
      (fn []
        (local (st buf) (fresh-state))
        (play st
          [{:type :message_start
            :message {:role :user :content "fix the bug"}}])
        (local c (content buf))
        (is-true (vim.tbl_contains c "* User"))
        (is-true (vim.tbl_contains c "fix the bug"))))

    (it "skips a duplicate user-echo when last-user-prompt matches"
      (fn []
        (local (st buf) (fresh-state))
        (tset st :last-user-prompt "same prompt")
        (play st
          [{:type :message_start
            :message {:role :user :content "same prompt"}}])
        (local c (content buf))
        (is-true (not (vim.tbl_contains c "* User")))))))

(describe "pi-org.render assistant streaming"
  (fn []
    (it "accumulates text deltas and rewrites the block in place"
      (fn []
        (local (st buf) (fresh-state))
        (play st
          [{:type :agent_start}
           {:type :message_start :message {:role :assistant}}
           {:type :message_update
            :assistantMessageEvent {:type :text_delta :delta "Hello"}}
           {:type :message_update
            :assistantMessageEvent {:type :text_delta :delta " world"}}])
        (local c (content buf))
        (is-true (vim.tbl_contains c "** Assistant"))
        (is-true (vim.tbl_contains c "Hello world"))))

    (it "text_end adds a trailing blank line and clears the streaming marks"
      (fn []
        (local (st buf) (fresh-state))
        (play st
          [{:type :agent_start}
           {:type :message_start :message {:role :assistant}}
           {:type :message_update
            :assistantMessageEvent {:type :text_delta :delta "Hi"}}
           {:type :message_update
            :assistantMessageEvent {:type :text_end}}])
        (local c (content buf))
        (is-true (vim.tbl_contains c "** Assistant"))
        (is-true (vim.tbl_contains c "Hi"))
        (is-true (= st.mark nil))
        (is-true (= st.end_mark nil))))

    (it "busy is true during a turn and false after agent_end"
      (fn []
        (local (st buf) (fresh-state))
        (play st [{:type :agent_start}])
        (is-true st.busy)
        (play st [{:type :agent_end}])
        (is-true (not st.busy))))))

(describe "pi-org.render thinking"
  (fn []
    (it "renders a folded src block when show_thinking is on"
      (fn []
        (local (st buf) (fresh-state))
        (play st
          [{:type :agent_start}
           {:type :message_start :message {:role :assistant}}
           {:type :message_update :assistantMessageEvent {:type :text_end}}
           {:type :message_update
            :assistantMessageEvent {:type :thinking_end :content "plan A"}}])
        (local c (content buf))
        (is-true (vim.tbl_contains c "#+BEGIN_SRC text"))
        (is-true (vim.tbl_contains c "plan A"))
        (is-true (vim.tbl_contains c "#+END_SRC"))))

    (it "omits the thinking block when show_thinking is off"
      (fn []
        (local buf (vim.api.nvim_create_buf false true))
        (local cfg (config.parse {:command :pi
                                  :org {:show_thinking false}} true))
        (local st (render.new-state buf cfg))
        (play st
          [{:type :agent_start}
           {:type :message_start :message {:role :assistant}}
           {:type :message_update :assistantMessageEvent {:type :text_end}}
           {:type :message_update
            :assistantMessageEvent {:type :thinking_end :content "secret"}}])
        (local c (content buf))
        (is-true (not (vim.tbl_contains c "secret")))))))

(describe "pi-org.render tool calls + results"
  (fn []
    (it "renders a *** Tool header + src block, then replaces it with the result"
      (fn []
        (local (st buf) (fresh-state))
        (play st
          [{:type :agent_start}
           {:type :message_start :message {:role :assistant}}
           {:type :message_update :assistantMessageEvent {:type :text_end}}
           {:type :message_update
            :assistantMessageEvent
            {:type :toolcall_end
             :toolCall {:id :call_1 :name :read
                        :arguments {:path "src/main.rs"}}}}
           {:type :tool_execution_end :toolCallId :call_1
            :result {:content [{:type :text :text "fn main() {}"}]}}])
        (local c (content buf))
        (is-true (vim.tbl_contains c "*** Tool: read"))
        ;; After the result arrives, the src block is replaced by an example block.
        (is-true (vim.tbl_contains c "#+BEGIN_EXAMPLE"))
        (is-true (vim.tbl_contains c "fn main() {}"))
        (is-true (vim.tbl_contains c "#+END_EXAMPLE"))
        ;; The original src block of args is gone.
        (is-true (not (vim.tbl_contains c "#+BEGIN_SRC text")))))

    (it "best-effort language hint for edit/write uses the path extension"
      (fn []
        ;; The language hint surfaces in the #+BEGIN_SRC line before the result
        ;; replaces it. We catch it by sending a tool call and reading the block
        ;; BEFORE the result.
        (local (st buf) (fresh-state))
        (play st
          [{:type :agent_start}
           {:type :message_start :message {:role :assistant}}
           {:type :message_update :assistantMessageEvent {:type :text_end}}
           {:type :message_update
            :assistantMessageEvent
            {:type :toolcall_end
             :toolCall {:id :c2 :name :write
                        :arguments {:path "foo.lua"}}}}])
        (local c (content buf))
        (is-true (vim.tbl_contains c "#+BEGIN_SRC lua"))))))

(describe "pi-org.render notices"
  (fn []
    (it "renders a compaction notice"
      (fn []
        (local (st buf) (fresh-state))
        (play st [{:type :compaction_end}])
        (local c (content buf))
        (is-true (vim.tbl_contains c "/Compacted context./"))))

    (it "renders an auto-retry notice with the attempt number and error"
      (fn []
        (local (st buf) (fresh-state))
        (play st [{:type :auto_retry_start :attempt 2
                   :errorMessage "rate limited"}])
        (local c (content buf))
        (is-true (vim.tbl_contains c "/Retrying (attempt 2): rate limited/"))))))
