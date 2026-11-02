(ns bbh.super.client
  "What every surface and Worker needs to talk to `bbh super`: where it lives
  and `call!`. This loads no database, so the CLI, the website and the pane
  start without libsqlite3."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net Socket]))

(defn data-dir []
  (fs/path (or (System/getenv "XDG_DATA_HOME") (fs/expand-home "~/.local/share")) "bbh"))

(defn super-dir [] (str (fs/path (data-dir) "super")))

(defn listeners-dir [dir] (fs/path dir "listeners"))

(defn register-pane!
  "Remember the fzf pane listening on PORT so commits reload it."
  [dir port]
  (fs/create-dirs (listeners-dir dir))
  (spit (str (fs/path (listeners-dir dir) (str port))) (str port)))

(defn new-id
  "A command id for a caller that has none: unique and never repeated."
  []
  (format "c-%d-%04x" (System/currentTimeMillis) (rand-int 0x10000)))

(defn call!
  "Send REQUEST {:command :args :id :token} to the controller under DIR and
  return its response map, {:ok ...} or {:error ...}. The token defaults to
  BBH_SUPER_TOKEN, else the owner's in <dir>/token; the id to a fresh one."
  ([request] (call! (super-dir) request))
  ([dir {:keys [token id] :as request}]
   (let [port-file (fs/path dir "port")
         _ (when-not (fs/exists? port-file) (throw (ex-info "bbh super is not running" {:dir dir :error :down})))
         port (parse-long (str/trim (slurp (str port-file))))
         token (or token (System/getenv "BBH_SUPER_TOKEN") (str/trim (slurp (str (fs/path dir "token")))))]
     (with-open [sock (Socket. "127.0.0.1" (int port))
                 out (io/writer (.getOutputStream sock))
                 in (io/reader (.getInputStream sock))]
       (.write out (pr-str (assoc request :token token :id (or id (new-id)))))
       (.write out "\n")
       (.flush out)
       (edn/read-string (.readLine in))))))

(defn- print-list! [{:keys [badge sections]}]
  (println (format "%d need you" badge))
  (doseq [{:keys [title threads]} sections]
    (println)
    (println (str/upper-case title))
    (doseq [{:keys [id title ask summary came-back]} threads]
      (println (format "  %-4s %s%s" id title (if came-back "  (came back)" "")))
      (when-let [line (or (:text ask) summary)] (println (str "       " line))))))

(defn -main
  "bbh super serve, bbh super list, or bbh super COMMAND [EDN-ARGS] [--id ID].
  Only serve loads the store."
  [& [command & args]]
  (case command
    "serve" ((requiring-resolve 'bbh.super/-main))
    (nil "help" "--help")
    (println (str/join "\n" ["usage: bbh super serve"
                               "       bbh super list"
                               "       bbh super COMMAND [EDN-ARGS] [--id ID]"
                               "commands: thread! link! attach-worker! ask! reply! dismiss! archive! note! milestone! answered! threads thread events sections"
                               "example: bbh super thread! '{:title \"Alt+W closes windows\"}'"]))
    (let [[edn-args & more] (if (= "list" command) (cons nil args) args)
          id (second (drop-while #(not= "--id" %) more))
          response (try (call! {:command (if (= "list" command) :sections (keyword command))
                                :args (when edn-args (edn/read-string edn-args)) :id id})
                        (catch clojure.lang.ExceptionInfo e {:error (or (:error (ex-data e)) :failed) :message (ex-message e)}))]
      (cond (:error response) (do (binding [*out* *err*] (println "bbh super:" (name (:error response)) (or (:message response) ""))) (System/exit 1))
            (= "list" command) (print-list! (:ok response))
            :else (prn response)))))
