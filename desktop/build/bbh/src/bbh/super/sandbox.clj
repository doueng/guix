(ns bbh.super.sandbox
  "The Router's evaluator. The Router has delegation authority, not execution
  authority, so its session sends every tool call here instead of to the REPL:
  SCI with pure Clojure and these functions only, no files, shell, interop or
  host namespaces. Commands carry the pane's Router token, never the owner's."
  (:require [bbh.sysenv :as sysenv]
            [bbh.super.client :as client]
            [bbh.super.verbs :as verbs]
            [sci.core :as sci]))

(defn- call! [command args]
  (let [token (or (sysenv/getenv "BBH_SUPER_TOKEN") (throw (ex-info "bbh super: no Router token" {:error :unauthorized})))
        {:keys [ok error message data]} (client/call! {:command command :args args :token token})]
    (if error (throw (ex-info (str "bbh super: " (name error) (when message (str ", " message))) (or data {}))) ok)))

(def bindings "The Router's verbs, from the one table in bbh.super.verbs." (verbs/bind call! verbs/router))

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
