(ns bbh.super.client
  "What every surface and Worker needs to talk to `bbh super`: where it lives
  and `call!`. This loads no database, so the CLI, the website and the Inbox
  start without libsqlite3."
  (:require [bbh.sysenv :as sysenv]
            [babashka.fs :as fs]
            [bbh.super.emacs :as emacs]
            [bbh.version :as version]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net Socket]))

(defn data-dir []
  (fs/path (or (sysenv/getenv "XDG_DATA_HOME") (fs/expand-home "~/.local/share")) "bbh"))

(defn super-dir [] (str (fs/path (data-dir) "super")))

(defn policy-file
  "~/.config/bbh/policy.edn, the controller's policy."
  []
  (fs/path (or (sysenv/getenv "XDG_CONFIG_HOME") (fs/expand-home "~/.config")) "bbh" "policy.edn"))

(defn listeners-dir [dir] (fs/path dir "listeners"))

(defn register-view!
  "Remember the Inbox view listening on PORT so commits reload it."
  [dir port]
  (fs/create-dirs (listeners-dir dir))
  (spit (str (fs/path (listeners-dir dir) (str port))) (str port)))

(defonce ^:private hooks (atom {}))

(defn on-change!
  "Call (F) after each commit under DIR that changes what views show, in this
  process, as notify-views! tells registered ports. KEY names the hook, so a
  second registration replaces the first."
  [dir key f]
  (swap! hooks assoc-in [(str dir) key] f))

(defn changed!
  "Run the hooks registered for DIR. One that throws does not stop the rest."
  [dir]
  (doseq [f (vals (get @hooks (str dir)))]
    (try (f) (catch Exception _ nil))))

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
         token (or token (sysenv/getenv "BBH_SUPER_TOKEN") (str/trim (slurp (str (fs/path dir "token")))))]
     (with-open [sock (Socket. "127.0.0.1" (int port))
                 out (io/writer (.getOutputStream sock))
                 in (io/reader (.getInputStream sock))]
       (.write out (pr-str (assoc request :token token :id (or id (new-id)))))
       (.write out "\n")
       (.flush out)
       (edn/read-string (.readLine in))))))

(defn machine-line
  "One line for `bbh super list` from the last health pass's MACHINE report:
  memory, swap, and live sessions against active Workers, stale ones named."
  [{:keys [mem-available-mb swap-used-mb sessions worker-sessions active-workers stale]}]
  (str (format "%s MB available, %s MB swap used; %d sessions live, %d of them Workers' for %d active Workers"
               (or mem-available-mb "?") (or swap-used-mb "?") (or sessions 0) (or worker-sessions 0) (or active-workers 0))
       (when (seq stale) (str "; stale: " (str/join ", " stale)))))

(defn- print-list! [{:keys [badge sections machine]}]
  (println (format "%d need you" badge))
  (println (version/status-line (data-dir)))
  (when machine (println (machine-line machine)))
  (doseq [{:keys [title threads]} sections]
    (println)
    (println (str/upper-case title))
    (doseq [{:keys [id title line came-back model]} threads]
      (println (format "  %-4s %s%s%s" id title (if came-back "  (came back)" "") (if model (str "  [" model "]") "")))
      (when line (println (str "       " line))))))

(defn open-router!
  "The Router and the Inbox in the super-agent's Emacs: the Router session of
  CONVERSATION with TOKEN and BBH_ROLE=router, in buffer Router, and the
  *bbh inbox* buffer."
  [{:keys [conversation token]}]
  {:router (emacs/open! {:conversation conversation :dir (System/getProperty "user.home") :title "Router"
                         :env {"BBH_SUPER_TOKEN" token "BBH_ROLE" "router"}})
   :inbox (emacs/read-string* (emacs/call! "eng/bbh-inbox-buffer"))})

(defn start-router!
  "Start the Router now, as the controller does by itself when none runs.
  Registers the Router first, which revokes an earlier Router token, so a
  Router session already open is replaced."
  [dir]
  (let [{:keys [ok error message]} (call! dir {:command :register-router! :args {:conversation "router"}})]
    (when error (throw (ex-info (str "register-router!: " (name error) " " message) {:error error})))
    (open-router! {:conversation "router" :token (:token ok)})))

(defn -main
  "bbh super serve, bbh super list, or bbh super COMMAND [EDN-ARGS] [--id ID].
  Only serve loads the store."
  [& [command & args]]
  (case command
    "serve" ((requiring-resolve 'bbh.super/-main))
    "start-router" (prn (start-router! (super-dir)))
    "notify-test" (let [critical (some #{"--critical"} args) [t ask] (remove #{"--critical"} args)]
                    ((requiring-resolve 'bbh.super.notify/manual-check!) t ask 60000 (if critical :critical :normal)))
    (nil "help" "--help")
    (println (str/join "\n" ["usage: bbh super serve"
                               "       bbh super list"
                               "       bbh super start-router"
                               "       bbh super notify-test [--critical] [T [ASK]]   send one real notification; a click opens the Inbox there"
                               "       bbh super COMMAND [EDN-ARGS] [--id ID]"
                               "commands: thread! start-worker! finish-worker! steer! cancel! health say! ask! reply! dismiss! archive! sweep! note! milestone! answered! threads thread events sections"
                               "example: bbh super thread! '{:title \"Alt+W closes windows\"}'"]))
    (let [[edn-args & more] (if (= "list" command) (cons nil args) args)
          id (second (drop-while #(not= "--id" %) more))
          response (try (call! {:command (if (= "list" command) :sections (keyword command))
                                :args (when edn-args (edn/read-string edn-args)) :id id})
                        (catch clojure.lang.ExceptionInfo e {:error (or (:error (ex-data e)) :failed) :message (ex-message e)}))]
      (cond (:error response) (do (binding [*out* *err*] (println "bbh super:" (name (:error response)) (or (:message response) ""))) (System/exit 1))
            (= "list" command) (print-list! (:ok response))
            :else (prn response)))))
