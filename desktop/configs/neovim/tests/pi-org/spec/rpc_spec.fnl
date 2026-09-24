;; Unit tests for pi-org.rpc: subscription/dispatch + request/response
;; correlation, WITHOUT spawning a real process.
;;
;; We construct a client via rpc.new and drive it with handle-line against
;; canned JSONL, intercepting vim.fn.chansend so we can inspect what would be
;; sent to pi's stdin.

(local rpc (require :pi-org.rpc))
(local jsonl (require :pi-org.jsonl))
(local eq assert.are.same)
(local is-true assert.is_true)

(describe "pi-org.rpc on/dispatch"
  (fn []
    (it "dispatches events to subscribed handlers by type"
      (fn []
        (local client (rpc.new {:command :pi :args {}}))
        (local got {})
        (rpc.on client :agent_start (fn [e] (table.insert got e)))
        (rpc.dispatch client {:type :agent_start})
        (rpc.dispatch client {:type :agent_end}) ;; no handler for this
        (eq 1 (length got))
        (eq :agent_start (. (. got 1) :type))))

    (it "supports multiple handlers for the same type"
      (fn []
        (local client (rpc.new {:command :pi :args {}}))
        (var a 0)
        (var b 0)
        (rpc.on client :agent_end (fn [] (set a (+ a 1))))
        (rpc.on client :agent_end (fn [] (set b (+ b 1))))
        (rpc.dispatch client {:type :agent_end})
        (eq 1 a)
        (eq 1 b)))

    (it "unsubscribe removes only the right handler"
      (fn []
        (local client (rpc.new {:command :pi :args {}}))
        (local calls {})
        (local h1 (fn [] (table.insert calls 1)))
        (local h2 (fn [] (table.insert calls 2)))
        (local unsub (rpc.on client :agent_start h1))
        (rpc.on client :agent_start h2)
        (rpc.dispatch client {:type :agent_start})
        (eq [1 2] calls)
        (unsub)
        (rpc.dispatch client {:type :agent_start})
        (eq [1 2 2] calls)))

    (it "does not invoke handlers for other event types"
      (fn []
        (local client (rpc.new {:command :pi :args {}}))
        (var called false)
        (rpc.on client :agent_start (fn [] (set called true)))
        (rpc.dispatch client {:type :message_start})
        (is-true (not called))))))

(describe "pi-org.rpc handle-line correlation"
  (fn []
    ;; Drive the real send path by faking chansend + job.
    ;; Use a shared table because plenary's coroutine-based runner doesn't
    ;; share var upvalues reliably between before_each and it closures.
    (local ctx {})

    (before_each
      (fn []
        (tset ctx :client (rpc.new {:command :pi :args {}}))
        ;; Fake a live job so send() doesn't error.
        (tset ctx.client :job 9999)
        (tset ctx.client :alive true)
        (tset ctx :sent {})
        (tset ctx :saved-chansend vim.fn.chansend)
        (tset vim.fn :chansend (fn [_job data] (table.insert ctx.sent data)))))

    (after_each
      (fn []
        (tset vim.fn :chansend ctx.saved-chansend)))

    (it "send assigns a unique sequential id and frames the command as JSONL"
      (fn []
        (local client ctx.client)
        (local sent ctx.sent)
        (local id1 (rpc.send client {:type :prompt :message :hi}))
        (local id2 (rpc.send client {:type :prompt :message :yo}))
        (eq :req_1 id1)
        (eq :req_2 id2)
        (eq 2 (length sent))
        ;; each frame is a JSONL record terminated by \n
        (is-true (= (: (. sent 1) :sub -1) "\n"))
        (local decoded (vim.json.decode (: (. sent 1) :sub 1 -2)))
        (eq :prompt decoded.type)
        (eq :req_1 decoded.id)
        (eq :hi decoded.message)))

    (it "on-result is called when a matching success response arrives"
      (fn []
        (local client ctx.client)
        (var result nil)
        (local id (rpc.send client {:type :get_state}
                            {:on-result (fn [data] (set result data))}))
        ;; Simulate pi's response: invoke the pending callback directly.
        (local pending (. client.pending id))
        (is-true (not= pending nil))
        (pending.on-result {:model {:id :x}})
        (eq {:model {:id :x}} result)))

    (it "on-error is called when a failure response arrives"
      (fn []
        (local client ctx.client)
        (var err nil)
        (local id (rpc.send client {:type :prompt :message :x}
                            {:on-error (fn [e] (set err e))}))
        (local pending (. client.pending id))
        (pending.on-error :boom)
        (eq :boom err)))

    (it "send errors when the client is not alive"
      (fn []
        (local client ctx.client)
        (tset client :alive false)
        (tset client :job nil)
        (local ok (pcall rpc.send client {:type :prompt :message :x}))
        (is-true (not ok))))))
