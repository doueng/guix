(ns dusk.core
  "Public runtime API for dusk configs: reactive state, signals from the Rust
  core, timers, calls into the core and request handlers.

  All user code runs on one runtime thread. Timers, nREPL evaluations and
  core messages are queued onto it, and the UI is re-rendered whenever the
  queue drains after something changed."
  (:import [java.util.concurrent LinkedBlockingQueue ScheduledThreadPoolExecutor TimeUnit]))


(defonce ^:no-doc out-writer (atom nil))
(defonce ^:no-doc ^LinkedBlockingQueue queue (LinkedBlockingQueue.))
(defonce ^:no-doc root (atom nil))
(defonce ^:no-doc request-handlers (atom {}))

(def ^:private send-lock (Object.))

(defn send!
  "Send one protocol message (EDN map) to the Rust core."
  [msg]
  (when-let [^java.io.Writer w @out-writer]
    (let [line (binding [*print-length* nil
                         *print-level* nil
                         *print-namespace-maps* false
                         *print-meta* false]
                 (pr-str msg))]
      (locking send-lock
        (.write w (str line "\n"))
        (.flush w)))))

(defn enqueue!
  "Run `f` on the runtime thread."
  [f]
  (.put queue f))

(defn refresh!
  "Schedule a re-render. Call after redefining components from a REPL."
  []
  (enqueue! (fn [])))


(defn state
  "An atom whose changes re-render the shell."
  [init]
  (doto (atom init)
    (add-watch ::render (fn [_ _ old new]
                          (when-not (identical? old new)
                            (refresh!))))))

(defonce ^:private signals (atom {}))

(defn signal
  "The atom holding the latest value the core published for `id`, e.g.
  :notifications, :volume, :microphone or :brightness. Read it with @."
  [id]
  (or (get @signals id)
      (get (swap! signals update id #(or % (state nil))) id)))

(defn ^:no-doc set-signal! [id value]
  (reset! (signal id) value))

(defn changes
  "Call (f old new) on the runtime thread whenever `ref` changes. Keyed, so
  re-evaluating a config replaces the previous watcher instead of stacking."
  [k ref f]
  (add-watch ref k (fn [_ _ old new]
                     (when (not= old new)
                       (enqueue! #(f old new)))))
  k)


(defonce ^:private ^ScheduledThreadPoolExecutor scheduler
  (doto (ScheduledThreadPoolExecutor. 1)
    (.setRemoveOnCancelPolicy true)))

(defonce ^:private timers (atom {}))

(defn cancel!
  "Cancel the timer registered under `k`."
  [k]
  (when-let [^java.util.concurrent.Future fut (get @timers k)]
    (.cancel fut false))
  (swap! timers dissoc k)
  nil)

(defn after!
  "Run `f` once after `ms`. Re-using `k` replaces the pending timer."
  [k ms f]
  (cancel! k)
  (let [self (promise)
        fut (.schedule scheduler
                       ^Runnable (fn []
                                   (swap! timers #(if (identical? (get % k) @self) (dissoc % k) %))
                                   (enqueue! f))
                       (long (max 0 ms)) TimeUnit/MILLISECONDS)]
    (deliver self fut)
    (swap! timers assoc k fut)
    k))

(defn every!
  "Run `f` every `ms` (first run immediately). Re-using `k` replaces it."
  [k ms f]
  (cancel! k)
  (let [fut (.scheduleAtFixedRate scheduler ^Runnable (fn [] (enqueue! f))
                                   0 (long (max 1 ms)) TimeUnit/MILLISECONDS)]
    (swap! timers assoc k fut)
    k))


(defn call!
  "Invoke a core function, e.g. (call! :notifications/dismiss 3)."
  [f & args]
  (send! {:op :call :fn f :args (vec args)}))

(defn exec!
  "Run a program without waiting. Several args are argv: (exec! \"ghostty\" \"-e\" \"btop\").
  A single string runs through sh -c: (exec! \"grim ~/shot.png\")."
  [& argv]
  (apply call! :exec argv))

(defn on-request
  "Handle `dusk msg call NAME` (and `dusk dmenu`, which is the :dmenu request).
  `f` receives the request args and a one-shot `respond` function."
  [request-name f]
  (swap! request-handlers assoc (keyword request-name) f)
  request-name)

(defn shell!
  "Set the root component: a no-arg fn returning surfaces (ui/layer nodes,
  nested in seqs, nils ignored). Pass a var (#'shell) so redefinitions apply."
  [f]
  (reset! root f)
  (refresh!))

(defonce ^:private nrepl-server (atom nil))

(defn nrepl!
  "Start an nREPL server inside the running shell (idempotent)."
  ([] (nrepl! 1667))
  ([port]
   (or @nrepl-server
       (let [start (requiring-resolve 'babashka.nrepl.server/start-server!)
             server (start {:host "127.0.0.1" :port port :quiet true})]
         (binding [*out* *err*] (println "dusk: nREPL listening on 127.0.0.1:" port))
         (reset! nrepl-server server)))))
