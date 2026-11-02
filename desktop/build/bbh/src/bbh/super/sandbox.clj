(ns bbh.super.sandbox
  "The Router's evaluator. The Router has delegation authority, not execution
  authority, so its session sends every tool call here instead of to the REPL:
  SCI with pure Clojure and these functions only, no files, shell, interop or
  host namespaces. Commands carry the pane's Router token, never the owner's."
  (:require [bbh.super.client :as client]
            [sci.core :as sci]))

(defn- call! [command args]
  (let [token (or (System/getenv "BBH_SUPER_TOKEN") (throw (ex-info "bbh super: no Router token" {:error :unauthorized})))
        {:keys [ok error message data]} (client/call! {:command command :args args :token token})]
    (if error (throw (ex-info (str "bbh super: " (name error) (when message (str ", " message))) (or data {}))) ok)))

(def bindings
  {'super-threads (fn super-threads [] (call! :threads {}))
   'super-thread (fn super-thread [id] (call! :thread {:id id}))
   'super-events (fn super-events [{:keys [thread after]}] (call! :events {:thread thread :after after}))
   'super-thread! (fn super-thread! [title] (call! :thread! {:title title}))
   'super-link! (fn super-link! [id resource] (call! :link! {:thread id :resource resource}))
   'super-start-worker! (fn super-start-worker! [spec] (call! :start-worker! spec))
   'super-steer! (fn super-steer! [worker text] (call! :steer! {:worker worker :text text}))
   'super-cancel! (fn super-cancel! [worker reason] (call! :cancel! {:worker worker :reason reason}))
   'super-note! (fn super-note! [id text] (call! :note! {:thread id :text text}))
   'super-milestone! (fn super-milestone! [id text] (call! :milestone! {:thread id :text text}))})

(defn context [bindings] (sci/init {:namespaces {'user bindings}}))

(defonce ^:private ctx
  ;; Resolved here, outside any sandbox evaluation, where resolve would look in the sandbox.
  (delay (context (assoc bindings 'jev-classify-task
                         (or (some-> (resolve 'bbh.env/jev-classify-task) deref)
                             (fn [_] (throw (ex-info "jev-classify-task is not loaded" {}))))))))

(defn eval-in
  "Evaluate CODE in CTX's user namespace. Under bb, its output goes to the
  caller's *out* and the namespace would otherwise follow the caller's *ns*."
  [ctx code]
  (sci/eval-string* ctx (str "(in-ns 'user) " code)))

(defn eval! "Evaluate CODE in this REPL's Router context." [code] (eval-in @ctx code))
