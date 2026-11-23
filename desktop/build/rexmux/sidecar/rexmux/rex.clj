(ns rexmux.rex
  "Rex from bb, through the rex CLI: one-shot API calls, and a background
  events stream per server. Servers are {:label .. :address ..}; an address is
  what rex -S takes, such as unix:///run/user/1000/rex/server.sock. A nil
  address means rex's own default (REX_SERVER, else the local socket)."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def bin (or (System/getenv "REXMUX_REX") "rex"))

(defn- base [server] (cond-> [bin "--autostart=false"] (:address server) (into ["-S" (:address server)])))

(defn call
  "The parsed result of METHOD with ARGS on SERVER, or throws with Rex's error."
  [server method args]
  (let [{:keys [exit out err]} (apply p/sh (concat (base server) ["api" "call" method (json/generate-string (or args {}))]))]
    (if (zero? exit)
      (when-not (str/blank? out) (json/parse-string out true))
      (throw (ex-info (str/trim (str err out)) {:method method :args args})))))

(defn help
  "Rex's help document (rex api list --json) for SERVER."
  [server]
  (let [{:keys [exit out err]} (apply p/sh (concat (base server) ["api" "list" "--json"]))]
    (if (zero? exit) (json/parse-string out true) (throw (ex-info (str/trim err) {})))))

(defn sessions [server] (:sessions (call server "session.list" {})))

(defn program-statuses
  "Every OSC 7501 record Rex holds on SERVER, as agent reports
  [{:session :block :id :record}]. A sidecar that starts, or restarts after a
  crash, folds these first, so it agrees with Rex before the window hears
  from it (verify/quint/rexmux.qnt, sidecarAgrees)."
  [server]
  (vec (for [{:keys [session_id]} (sessions server)
             {:keys [block_id creator_name]} (:blocks (call server "session.list_blocks" {:session_id session_id}))
             :when (= "com.superlogical.terminal" creator_name)
             rec (:records (try (call server "com.superlogical.terminal.program_status"
                                      {:session_id session_id :block_id block_id :args {}})
                                (catch Exception _ nil)))]
         {:session session_id :block block_id :id (or (:id rec) "") :record rec})))

(defn capture
  "The screen and scrollback text of BLOCK in SESSION."
  [server session block]
  (let [{:keys [out]} (apply p/sh (concat (base server) ["capture" "-s" session "-b" block]))]
    out))

(defn events!
  "Follow every event of SERVER in a background process, calling (f msg) for
  each parsed line. Returns the process; destroy it to stop."
  [server f]
  (let [proc (p/process (concat (base server) ["events" "--all" "--json"]) {:err :discard})]
    (future
      (with-open [r (io/reader (:out proc))]
        (doseq [line (line-seq r) :when (not (str/blank? line))]
          (try (f (json/parse-string line true)) (catch Exception _)))))
    proc))
