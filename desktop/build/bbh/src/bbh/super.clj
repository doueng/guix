(ns bbh.super
  "The super-agent controller: `bbh super serve`. Commands arrive on a loopback
  socket as one EDN line each with a token, are run through the store as one
  transaction, and the dispatcher drains the outbox into bbh mailboxes. `call!`
  is the client every surface and Worker uses."
  (:require [babashka.fs :as fs]
            [bbh.inbox :as inbox]
            [bbh.super.model :as model]
            [bbh.super.store :as store]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net ServerSocket Socket InetAddress]
           [java.security MessageDigest SecureRandom]))

(defn data-dir []
  (fs/path (or (System/getenv "XDG_DATA_HOME") (fs/expand-home "~/.local/share")) "bbh"))

(defn super-dir [] (str (fs/path (data-dir) "super")))

(defn- sha256 [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn- new-token []
  (let [b (byte-array 24)] (.nextBytes (SecureRandom.) b) (apply str (map #(format "%02x" %) b))))

(defn new-id
  "A command id for a caller that has none: unique and never repeated."
  []
  (format "c-%d-%04x" (System/currentTimeMillis) (rand-int 0x10000)))

;; ---- controller ---------------------------------------------------------

(defn- run!
  "Commit COMMAND for PRINCIPAL on controller C. Serialized: the controller is
  the single writer."
  [{:keys [db state]} principal command]
  (locking db
    (let [r (store/commit! db @state principal command)]
      (reset! state (:state r))
      r)))

(defn- principal-of [state token]
  (when (string? token)
    (let [t (get-in state [:tokens (sha256 token)])]
      (when (and t (not (:revoked t))) (:principal t)))))

(defn- read-command
  "Reads never write and need no command id."
  [{:keys [db state]} {:keys [command args]}]
  (let [st @state]
    (case command
      :threads (vec (sort-by :id (vals (:threads st))))
      :thread (let [{:keys [id]} args
                    t (get-in st [:threads id])
                    of (fn [kind] (vec (sort-by :seq (filter #(= id (:thread %)) (vals (kind st))))))]
                (when t (assoc t :asks (of :asks) :replies (of :replies) :workers (vec (sort-by :generation (filter #(= id (:thread %)) (vals (:workers st))))))))
      :events (store/events db (or (:after args) 0) (:thread args))
      ::unknown)))

(defn handle
  "The response for one request map on controller C."
  [c {:keys [token id command args]}]
  (try
    (let [principal (or (principal-of @(:state c) token) (throw (ex-info "unauthorized" {:error :unauthorized})))
          command (keyword command)
          read (read-command c {:command command :args args})]
      (if (not= ::unknown read)
        {:ok read}
        (let [cmd {:id (or id (new-id)) :command command :args (or args {})}]
          (if (= :attach-worker! command)
            (let [t (new-token)
                  r (run! c principal (assoc cmd :token-hash (sha256 t)))]
              {:ok (cond-> (:result r) (not (:repeat? r)) (assoc :token t))})
            {:ok (:result (run! c principal cmd))}))))
    (catch clojure.lang.ExceptionInfo e
      {:error (or (:error (ex-data e)) :failed) :message (ex-message e) :data (dissoc (ex-data e) :error)})
    (catch Exception e
      {:error :failed :message (ex-message e)})))

(defn- ensure-owner-token!
  "The owner's token, from <dir>/token when the store still knows it, else a
  new one issued and written there, readable by the user only."
  [{:keys [dir state] :as c}]
  (let [f (fs/path dir "token")
        known (when (fs/exists? f) (str/trim (slurp (str f))))]
    (if (= :owner (principal-of @state known))
      known
      (let [t (new-token)]
        (run! c :controller {:id (str "token-" (sha256 t)) :command :issue-token! :token-hash (sha256 t) :args {:principal :owner}})
        (spit (str f) t)
        (fs/set-posix-file-permissions f "rw-------")
        t))))

(defn- serve-client [c ^Socket sock]
  (with-open [sock sock
              in (io/reader (.getInputStream sock))
              out (io/writer (.getOutputStream sock))]
    (let [line (.readLine in)
          response (try (handle c (edn/read-string line))
                        (catch Exception e {:error :bad-request :message (ex-message e)}))]
      (.write out (pr-str response))
      (.write out "\n")
      (.flush out))))

;; ---- dispatcher ---------------------------------------------------------

(defn- conversation-root [{:keys [conversations]} id]
  (let [dir (str (fs/path conversations id))]
    (if (fs/exists? dir) (inbox/root-dir dir) dir)))

(defn dispatch!
  "One pass: send each pending outbox row to its Worker's mailbox, and mark a
  sent row acked once its mailbox file is gone. Each step is a command with a
  fixed id, so a pass repeated after a crash changes nothing twice."
  [{:keys [state] :as c}]
  (doseq [{:keys [id conversation text message] :as o} (sort-by :id (vals (:outbox @state)))]
    (case (:state o)
      :pending (let [root (conversation-root c conversation)
                     m (inbox/send! root text)]
                 (run! c :controller {:id (str "sent-" id) :command :outbox-sent! :args {:outbox id :message (:id m)}}))
      :sent (when-not (fs/exists? (fs/path (conversation-root c conversation) "mailbox" (str message ".json")))
              (run! c :controller {:id (str "acked-" id) :command :outbox-acked! :args {:outbox id}}))
      nil)))

(defn start!
  "Open the store under DIR, listen on a loopback port written to <dir>/port,
  and run the dispatcher every SCAN-MS. Returns the controller; `stop!` ends it.
  The controller is the store's only writer, so a second start on the same
  directory is refused while <dir>/lock is held."
  [{:keys [dir conversations scan-ms] :or {scan-ms 500}}]
  (fs/create-dirs dir)
  (let [channel (java.nio.channels.FileChannel/open (fs/path dir "lock")
                                                    (into-array java.nio.file.OpenOption
                                                                [java.nio.file.StandardOpenOption/CREATE
                                                                 java.nio.file.StandardOpenOption/WRITE]))
        _ (when-not (try (.tryLock channel) (catch Exception _ nil))
            (.close channel)
            (throw (ex-info (str "bbh super is already running on " dir) {:dir dir})))
        db (store/open (str (fs/path dir "super.db")))
        server (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        c {:dir dir :conversations (or conversations (str (fs/path (data-dir) "conversations")))
           :db db :state (atom (store/load db)) :server server :lock channel :stopped (atom false)}]
    (ensure-owner-token! c)
    (spit (str (fs/path dir "port")) (str (.getLocalPort server)))
    (doto (Thread. #(while (not @(:stopped c))
                      (try (serve-client c (.accept server))
                           (catch Exception e (when-not @(:stopped c) (binding [*out* *err*] (println "bbh super:" (ex-message e))))))))
      (.setDaemon true) (.start))
    (doto (Thread. #(while (not @(:stopped c))
                      (try (dispatch! c)
                           (catch Exception e (binding [*out* *err*] (println "bbh super dispatch:" (ex-message e)))))
                      (Thread/sleep (long scan-ms))))
      (.setDaemon true) (.start))
    c))

(defn stop! [{:keys [stopped server db dir lock]}]
  (reset! stopped true)
  (.close ^ServerSocket server)
  (fs/delete-if-exists (fs/path dir "port"))
  (locking db (store/close! db))
  (.close ^java.nio.channels.FileChannel lock))

;; ---- client -------------------------------------------------------------

(defn call!
  "Send REQUEST {:command :args :id :token} to the controller under DIR and
  return its response map, {:ok ...} or {:error ...}. The token defaults to the
  owner's in <dir>/token, the id to a fresh one."
  ([request] (call! (super-dir) request))
  ([dir {:keys [token id] :as request}]
   (let [port-file (fs/path dir "port")
         _ (when-not (fs/exists? port-file) (throw (ex-info "bbh super is not running" {:dir dir})))
         port (parse-long (str/trim (slurp (str port-file))))
         token (or token (System/getenv "BBH_SUPER_TOKEN") (str/trim (slurp (str (fs/path dir "token")))))]
     (with-open [sock (Socket. "127.0.0.1" (int port))
                 out (io/writer (.getOutputStream sock))
                 in (io/reader (.getInputStream sock))]
       (.write out (pr-str (assoc request :token token :id (or id (new-id)))))
       (.write out "\n")
       (.flush out)
       (edn/read-string (.readLine in))))))

(defn -main
  "bbh super serve, or bbh super COMMAND [EDN-ARGS] [--id ID]."
  [& [command & args]]
  (case command
    "serve" (do (start! {:dir (super-dir)})
                (println "bbh super on" (super-dir))
                @(promise))
    (nil "help" "--help")
    (println (str/join "\n" ["usage: bbh super serve"
                               "       bbh super COMMAND [EDN-ARGS] [--id ID]"
                               "commands: thread! link! attach-worker! ask! reply! note! milestone! answered! threads thread events"
                               "example: bbh super thread! '{:title \"Alt+W closes windows\"}'"]))
    (let [[edn-args & more] args
          id (second (drop-while #(not= "--id" %) more))
          response (call! {:command (keyword command) :args (when edn-args (edn/read-string edn-args)) :id id})]
      (prn response)
      (System/exit (if (:error response) 1 0)))))
