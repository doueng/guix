(ns bbh.mcp
  "The MCP server Claude Code starts for the clojure tool. It forwards every
  call to the bbh session that started Claude Code."
  (:require [bbh.session :as session]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(def ^:private port (some-> (System/getenv "BBH_BRIDGE_PORT") parse-long))
(def ^:private token (System/getenv "BBH_BRIDGE_TOKEN"))

(defn- ask [req]
  (with-open [sock (java.net.Socket. "127.0.0.1" (int port))]
    (let [out (io/writer sock)]
      (.write out (str (json/generate-string (assoc req :token token)) "\n"))
      (.flush out)
      (json/parse-string (.readLine (io/reader sock)) true))))

(def ^:private out-lock (Object.))

(defn- send! [msg]
  (locking out-lock
    (println (json/generate-string (assoc msg :jsonrpc "2.0")))
    (flush)))

(defn- call [id {:keys [arguments _meta]}]
  (try
    (let [{:keys [text error]} (ask {:op "call" :id (get _meta :claudecode/toolUseId (str (random-uuid)))
                                     :code (:code arguments)})]
      (send! {:id id :result {:content [{:type "text" :text (or text error)}] :isError (boolean error)}}))
    (catch Exception e
      (send! {:id id :result {:content [{:type "text" :text (str "bbh bridge: " (ex-message e))}] :isError true}}))))

(defn -main [& _]
  (doseq [line (line-seq (io/reader System/in))]
    (let [{:keys [id method params]} (json/parse-string line true)]
      (case method
        "initialize" (send! {:id id :result {:protocolVersion (:protocolVersion params)
                                             :capabilities {:tools {}}
                                             :serverInfo {:name "bbh" :version "1"}}})
        "tools/list" (send! {:id id :result {:tools [{:name "clojure"
                                                      :description (:description (ask {:op "describe"}))
                                                      :inputSchema session/tool-input-schema}]}})
        "tools/call" (future (call id params))
        (when id (send! {:id id :result {}}))))))
