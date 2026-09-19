;; RPC client for `pi --mode rpc`.
;;
;; Spawns pi as a child process and speaks the JSON protocol documented in
;; ~/.pi docs/rpc.md. Commands are sent on stdin (one JSONL record each);
;; responses (type:"response" with matching id) and events (everything else)
;; arrive on stdout as JSONL lines.
;;
;; Neovim's jobstart splits stdout on \n already, but the API also splits on
;; U+2028/U+2029 when the buffer is a string — so we feed raw chunks into our
;; own jsonl.reader to enforce strict LF-only framing.

(local jsonl (require :pi-org.jsonl))

(local M {})

;; A client is a plain table mutated by these functions so callers can hold a
;; stable reference.
;;   :job       number|nil   jobstart id (0 means spawn failed)
;;   :config    table         merged pi-org config
;;   :handlers  table         event handler fns keyed by event type
;;   :pending   table         id -> {on-result, on-error} for in-flight reqs
;;   :seq       number        request id counter
;;   :feed      function|nil  jsonl feed fn (set on start)
;;   :stderr    string        accumulated stderr
;;   :alive     boolean

(fn new [config]
  {:job nil
   : config
   :handlers {}
   :pending {}
   :seq 0
   :feed nil
   :stderr ""
   :alive false})

(fn alive? [client]
  (and client.alive client.job (> client.job 0)))

;; ----- event subscriptions ------------------------------------------------

(fn on [client event-type handler]
  (let [hs (or (. client.handlers event-type) [])]
    (table.insert hs handler)
    (tset client.handlers event-type hs)
    (fn []
      (var idx nil)
      (for [i 1 (length hs)]
        (when (and (not idx) (= (. hs i) handler))
          (set idx i)))
      (when idx (table.remove hs idx)))))

(fn dispatch [client event]
  (let [t (and (= (type event) :table) event.type)
        hs (and t (. client.handlers t))]
    (when hs
      (each [_ h (ipairs hs)] (h event)))))

;; ----- request/response ---------------------------------------------------
;;
;; `send` takes a command table and optional callbacks:
;;   :on-result fn(data)   called with response.data on success
;;   :on-error  fn(err)    called with response.error on failure
;; If neither is supplied, the response is still consumed (fire-and-forget).

(fn send [client command ?opts]
  (when (not (alive? client))
    (error "pi-org: rpc client not started"))
  (set client.seq (+ client.seq 1))
  (let [id (.. :req_ client.seq)
        full (vim.tbl_extend :force command {: id})
        opts (or ?opts {})
        on-result (or opts.on-result (fn [_] nil))
        on-error (or opts.on-error
                     (fn [err]
                       (vim.notify (.. "pi-org rpc: " (tostring err))
                                   vim.log.levels.ERROR)))]
    (tset client.pending id {: on-result : on-error})
    (vim.fn.chansend client.job (jsonl.serialize full))
    id))

;; ----- line handling ------------------------------------------------------

(fn handle-line [client line]
  (when (and line (not= line ""))
    (when vim.g.pi_org_debug (io.stderr:write (.. "[pi-org] line: " line "\n")))
    (let [(ok parsed) (pcall vim.json.decode line)]
      (when (and ok (= (type parsed) :table))
        (if (and (= parsed.type :response) parsed.id
                 (. client.pending parsed.id))
            (let [pending (. client.pending parsed.id)]
              (tset client.pending parsed.id nil)
              (if parsed.success
                  (pending.on-result parsed.data)
                  (pending.on-error (or parsed.error "rpc command failed"))))
            (dispatch client parsed))))))

;; ----- process lifecycle --------------------------------------------------

(fn start [client]
  (when client.alive
    (error "pi-org: rpc client already started"))
  (let [cfg client.config
        feed (jsonl.reader (fn [line] (handle-line client line)))
        cmd-args (vim.list_extend [:--mode :rpc] (or cfg.args []))
        env (vim.tbl_extend :force (vim.fn.environ) (or cfg.env {}))
        opts {:cwd cfg.cwd
              : env
              :stdout_buffered false
              :stderr_buffered false
              :on_stdout (fn [_ data _]
                           (when vim.g.pi_org_debug
                             (io.stderr:write (.. "[pi-org] stdout: "
                                                  (vim.inspect data) "\n")))
                           ;; Neovim delivers stdout split on \n (and, per the
                           ;; jobstart docs, on U+2028/U+2029 too) as a list
                           ;; of lines WITHOUT trailing newlines, plus a
                           ;; trailing "" per chunk representing the final
                           ;; newline. We re-feed each element followed by \n
                           ;; so the strict LF-only framer sees the original
                           ;; stream. The rare case of a JSON string
                           ;; containing U+2028/U+2029 (warned about in pi's
                           ;; rpc.md) is not handled here.
                           (when data
                             (each [_ chunk (ipairs data)]
                               (when chunk
                                 (feed chunk)
                                 (feed "\n")))))
              :on_stderr (fn [_ data _]
                           (when data
                             (each [_ chunk (ipairs data)]
                               (when chunk
                                 (tset client :stderr (.. client.stderr chunk))))))
              :on_exit (fn [_ code _]
                         (set client.alive false)
                         (set client.job nil)
                         (dispatch client
                                   {:type :exit : code :stderr client.stderr}))}]
    (set client.feed feed)
    (let [job (vim.fn.jobstart (vim.list_extend [cfg.command] cmd-args) opts)]
      (if (and job (> job 0))
          (do
            (set client.job job)
            (set client.alive true)
            (when (not cfg.auto_compaction)
              (send client {:type :set_auto_compaction :enabled false}))
            (when cfg.thinking_level
              (send client
                    {:type :set_thinking_level :level cfg.thinking_level}))
            client)
          (error (.. "pi-org: failed to start pi: " (tostring job)))))))

(fn stop [client]
  (when (and client.job (> client.job 0))
    (vim.fn.jobstop client.job))
  (set client.alive false)
  (set client.job nil))

;; ----- convenience command wrappers --------------------------------------

(fn prompt [client message ?opts]
  (send client {:type :prompt : message} ?opts))

(fn steer [client message ?opts]
  (send client {:type :steer : message} ?opts))

(fn follow-up [client message ?opts]
  (send client {:type :follow_up : message} ?opts))

(fn abort [client ?opts]
  (send client {:type :abort} ?opts))

(fn new-session [client ?opts]
  (send client {:type :new_session} ?opts))

(fn get-state [client ?opts]
  (send client {:type :get_state} ?opts))

(fn set-model [client provider model-id ?opts]
  (send client {:type :set_model : provider :modelId model-id} ?opts))

(fn cycle-model [client ?opts]
  (send client {:type :cycle_model} ?opts))

(fn set-thinking-level [client level ?opts]
  (send client {:type :set_thinking_level : level} ?opts))

(fn cycle-thinking-level [client ?opts]
  (send client {:type :cycle_thinking_level} ?opts))

(fn compact [client ?opts]
  (send client {:type :compact} ?opts))

(fn get-messages [client ?opts]
  (send client {:type :get_messages} ?opts))

(fn get-commands [client ?opts]
  (send client {:type :get_commands} ?opts))

(fn respond-ui [client id response]
  (when (alive? client)
    (vim.fn.chansend client.job
                     (jsonl.serialize (vim.tbl_extend :force
                                                      {:type :extension_ui_response
                                                       : id}
                                                      response)))))

(tset M :new new)
(tset M :alive? alive?)
(tset M :on on)
(tset M :dispatch dispatch)
(tset M :send send)
(tset M :start start)
(tset M :stop stop)
(tset M :prompt prompt)
(tset M :steer steer)
(tset M :follow-up follow-up)
(tset M :abort abort)
(tset M :new-session new-session)
(tset M :get-state get-state)
(tset M :set-model set-model)
(tset M :cycle-model cycle-model)
(tset M :set-thinking-level set-thinking-level)
(tset M :cycle-thinking-level cycle-thinking-level)
(tset M :compact compact)
(tset M :get-messages get-messages)
(tset M :get-commands get-commands)
(tset M :respond-ui respond-ui)
M
