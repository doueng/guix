(ns bbh.repl
  "A conversation's bb REPL process, driven over nREPL."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bencode.core :as b]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private bootstrap
  "(require '[babashka.nrepl.server :as srv])
(spit (System/getenv \"BBH_PORT_FILE\")
      (.getLocalPort (:socket (srv/start-server! {:host \"127.0.0.1\" :port 0 :quiet true}))))
@(promise)")

(defn- decode [v]
  (cond (bytes? v) (String. ^bytes v "UTF-8")
        (sequential? v) (mapv decode v)
        (map? v) (update-vals v decode)
        :else v))

(defn- wait-for-port [port-file proc]
  (loop [tries 500]
    (let [port (some-> (when (fs/exists? port-file) (slurp port-file)) str/trim parse-long)]
      (cond port port
            (or (zero? tries) (not (p/alive? proc)))
            (throw (ex-info "The REPL process did not start; see repl.log" {:port-file port-file}))
            :else (do (Thread/sleep 10) (recur (dec tries)))))))

(defn- read-loop [{:keys [in queues closed]}]
  (try
    (loop []
      (let [m (decode (b/read-bencode in))]
        (some-> (get @queues (get m "id")) (.put m))
        (recur)))
    (catch Exception _
      (reset! closed true)
      (doseq [q (vals @queues)] (.put ^LinkedBlockingQueue q {::closed true})))))

(defn- request! [{:keys [out queues closed]} msg deadline-ms]
  (let [id (str (random-uuid))
        q (LinkedBlockingQueue.)
        end (+ (System/currentTimeMillis) deadline-ms)]
    (swap! queues assoc id q)
    (try
      (if @closed
        {:died true :messages []}
        (do (locking out (b/write-bencode out (assoc msg "id" id)) (.flush out))
            (loop [acc []]
              (let [m (.poll q (max 0 (- end (System/currentTimeMillis))) TimeUnit/MILLISECONDS)]
                (cond (nil? m) {:timeout true :messages acc}
                      (::closed m) {:died true :messages acc}
                      (some #{"done"} (get m "status")) {:messages (conj acc m)}
                      :else (recur (conj acc m)))))))
      (catch java.io.IOException _ {:died true :messages []})
      (finally (swap! queues dissoc id)))))

(defn- clone! [conn]
  (let [{:keys [messages]} (request! conn {"op" "clone"} 5000)]
    (or (some #(get % "new-session") messages)
        (throw (ex-info "nREPL clone failed" {:messages messages})))))

(defn start!
  "Start a REPL process in CWD that logs to DIR/repl.log."
  [{:keys [dir cwd]}]
  (fs/create-dirs dir)
  (let [port-file (str (fs/path dir "repl.port"))
        log (fs/file dir "repl.log")
        _ (fs/delete-if-exists port-file)
        config (System/getProperty "babashka.config")
        ;; Nothing writes the REPL's stdin, and an open pipe there would hang any child that reads it.
        proc (apply p/process {:dir cwd :extra-env {"BBH_PORT_FILE" port-file}
                               :in (fs/file "/dev/null")
                               :out :append :out-file log :err :append :err-file log}
                    (concat ["bb"] (when config ["--config" config]) ["-e" bootstrap]))
        port (wait-for-port port-file proc)
        sock (java.net.Socket. "127.0.0.1" (int port))
        conn {:proc proc :port port :sock sock
              :in (java.io.PushbackInputStream. (.getInputStream sock))
              :out (.getOutputStream sock)
              :queues (atom {}) :closed (atom false)}]
    (future (read-loop conn))
    (assoc conn :user (clone! conn) :control (clone! conn))))

(defn kill! [{:keys [proc sock]}]
  (p/destroy-tree proc)
  (.close ^java.net.Socket sock)
  (deref proc 5000 nil))

(defn- collect [{:keys [messages] :as r}]
  (assoc (select-keys r [:timeout :died])
         :out (apply str (keep #(get % "out") messages))
         :err (apply str (keep #(get % "err") messages))
         :value (last (keep #(get % "value") messages))
         :error? (boolean (some #(get % "ex") messages))))

(defn eval!
  "Evaluate CODE in the conversation's session. Returns {:out :err :value :error?},
  plus :timeout or :died when no complete reply arrived."
  [conn code deadline-ms]
  (collect (request! conn {"op" "eval" "code" code "session" (:user conn)} deadline-ms)))

(def defaults
  {:system "You work inside a persistent babashka REPL through the clojure tool. Definitions persist until the REPL process restarts."
   :tool-description "Evaluate Clojure in the persistent babashka REPL. Returns printed output and the last value."
   :effort "medium"
   :deadline-ms 120000
   :max-output-chars 30000
   :rollover-at-tokens 350000})

(def ^:private checks
  {:system [string? "a string"]
   :tool-description [string? "a string"]
   :effort [#{"none" "minimal" "low" "medium" "high" "xhigh" "max"} "one of none, minimal, low, medium, high, xhigh or max"]
   :max-tokens [pos-int? "a positive integer"]
   :deadline-ms [pos-int? "a positive integer"]
   :max-output-chars [pos-int? "a positive integer"]
   :rollover-at-tokens [pos-int? "a positive integer"]})

(defn- validate [m]
  (if-not (map? m)
    {:manifest defaults :problems [(str "bbh.env/manifest must be a map, got " (pr-str m))]}
    (reduce-kv (fn [acc k [ok? desc]]
                 (let [v (get m k ::absent)]
                   (cond (= v ::absent) acc
                         (ok? v) (assoc-in acc [:manifest k] v)
                         :else (update acc :problems conj (str k " must be " desc ", got " (pr-str v))))))
               {:manifest defaults :problems []}
               checks)))

(defn call
  "Evaluate CODE in the control session, which leaves the model's *1 and *ns*
  alone. Returns {:value data} or {:error description}."
  [conn code deadline-ms]
  (let [r (collect (request! conn {"op" "eval" "session" (:control conn) "code" code} deadline-ms))]
    (cond (:timeout r) {:error (str "took longer than " deadline-ms " ms")}
          (:died r) {:error "the REPL process exited"}
          (:error? r) {:error (str/trim (:err r))}
          :else (try {:value (edn/read-string (:value r))}
                     (catch Exception _ {:error (str "returned unreadable data: " (:value r))})))))

(defn manifest
  "Read bbh.env/manifest, a map or a function returning one, merged onto defaults."
  [conn]
  (let [{:keys [value error]} (call conn "(when-let [v (resolve 'bbh.env/manifest)] (let [m @v] (if (fn? m) (m) m)))" 10000)]
    (cond error {:manifest defaults :problems [(str "bbh.env/manifest " error)]}
          (nil? value) {:manifest defaults :problems []}
          :else (validate value))))
