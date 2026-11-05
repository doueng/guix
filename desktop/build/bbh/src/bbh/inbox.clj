(ns bbh.inbox
  "What a live session and the website share, kept in the conversation's
  directory so that neither needs the other running. A poller announces the
  session in agent.json and hands each file in mailbox/ to its front end. The
  directory is the rollover root's, so a rollover keeps both.

  A front end owns delivery. The poller hands it each message once and is told
  `ack`, `retry` or `busy`. Delivery is at least once, because an ack follows
  the turn that held the message."
  (:require [babashka.fs :as fs]
            [bbh.transcript :as transcript]
            [bbh.super.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private scan-ms 500)

(defn root-dir
  "The directory of conversation DIR's rollover root."
  [dir]
  (str (fs/path (fs/parent dir) (transcript/inbox-id dir))))

(defn- mailbox [root]
  (fs/path root "mailbox"))

(defn- agent-file [root]
  (str (fs/path root "agent.json")))

(defn- image-line
  "The line that puts the picture at PATH in a prompt. Prompts are text, so it
  says how the agent can look at it."
  [path]
  (format "[Image from the phone: %s. See it with (look \"your question\" \"%s\").]" path path))

(defn parts
  "Prompt TEXT as {:text} with its words, and :images with its pictures' file names."
  [text]
  (let [lines (re-seq #"(?m)^\[Image from the phone: .*/([^/]+\.jpg)\. See it with .*\]$" text)]
    (cond-> {:text (str/trim (reduce #(str/replace %1 (first %2) "") text lines))}
      (seq lines) (assoc :images (mapv second lines)))))

(defn- save-images!
  "Write the JPEG byte arrays IMAGES for message ID. Returns their paths."
  [root id images]
  (let [dir (fs/create-dirs (fs/path root "images"))]
    (vec (map-indexed (fn [n image]
                        (let [path (fs/path dir (format "%s-%d.jpg" id n))]
                          (fs/write-bytes path image)
                          (str path)))
                      images))))

(defn send!
  "Leave TEXT and the JPEG byte arrays IMAGES for the session of conversation
  ROOT. Returns the message."
  ([root text] (send! root text nil))
  ([root text images] (send! root text images nil))
  ([root text images {:keys [kind message-id]}]
   (let [id (or message-id (format "%d-%04x" (System/currentTimeMillis) (rand-int 0x10000)))
         lines (map image-line (save-images! root id images))
         message (cond-> {:id id :text (str/join "\n\n" (remove str/blank? [text (str/join "\n" lines)]))}
                   kind (assoc :kind kind))
         dir (fs/create-dirs (mailbox root))
         tmp (fs/create-temp-file {:dir dir :prefix "."})]
     (spit (str tmp) (json/generate-string message))
     (fs/move tmp (fs/path dir (str id ".json")) {:atomic-move true :replace-existing true})
     message)))

(defn pending
  "The messages in ROOT's mailbox, oldest first."
  [root]
  (let [dir (mailbox root)]
    (when (fs/exists? dir)
      (->> (fs/glob dir "[0-9]*.json") (map str) sort
           (keep #(try (json/parse-string (slurp %) true) (catch java.io.IOException _ nil)))
           vec))))

(defn announce!
  "Mark conversation ROOT's session, this process, as live in agent.json, with
  its PROJECT and whether it is BUSY in a turn."
  [{:keys [root] :as p} busy]
  (let [tmp (fs/create-temp-file {:dir (fs/create-dirs root) :prefix ".agent"})]
    (spit (str tmp) (json/generate-string {:pid (.pid (java.lang.ProcessHandle/current))
                                           :start (:start (process/identity-of (.pid (java.lang.ProcessHandle/current))))
                                           :project (:project p) :busy busy}))
    (fs/move tmp (agent-file root) {:atomic-move true :replace-existing true})))

(defn agent
  "The live session of conversation ROOT as {:pid :project :busy}, or nil."
  [root]
  (let [f (agent-file root)]
    (when (fs/exists? f)
      (let [{:keys [pid] :as a} (json/parse-string (slurp f) true)]
        (when (if (:start a) (process/matches? a)
                  (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) .isAlive))
          a)))))

(defn- scan [{:keys [root seen deliver]}]
  (doseq [{:keys [id] :as message} (pending root)
          :when (not (contains? @seen id))]
    (swap! seen conj id)
    (deliver message)))

(defn start!
  "Announce conversation ROOT's session and scan its mailbox on a daemon thread,
  calling DELIVER with {:id :text} for each new message. Returns the poller."
  [{:keys [root scan-ms] :or {scan-ms scan-ms} :as options}]
  (let [p (assoc options :scan-ms scan-ms :seen (atom #{}) :stopped (atom false))]
    (fs/create-dirs root)
    (announce! p false)
    (doto (Thread. #(while (not @(:stopped p))
                      (try (scan p)
                           (catch Exception e
                             (binding [*out* *err*] (println "bbh inbox:" (ex-message e)))))
                      (Thread/sleep (long scan-ms))))
      (.setDaemon true) (.start))
    p))

(defn withdraw!
  "Mark conversation ROOT's session as gone."
  [root]
  (fs/delete-if-exists (agent-file root)))

(defn stop! [{:keys [root stopped]}]
  (reset! stopped true)
  (withdraw! root))

(defn ack! [{:keys [root]} id]
  (fs/delete-if-exists (fs/path (mailbox root) (str (fs/file-name id) ".json"))))

(defn retry!
  "Have the next scan deliver ID again."
  [{:keys [seen]} id]
  (swap! seen disj id))

(defn busy! [p busy]
  (announce! p busy))

(defn -poll
  "Serve conversation DIR to a front end on stdio. Deliveries go out as JSON
  lines. Input lines are `ack ID`, `retry ID`, `busy true` and `busy false`, and
  EOF ends it."
  [dir project]
  (let [out (Object.)
        p (start! {:root (root-dir dir) :project project
                   ;; The session acts on a cancellation itself; it is never a prompt.
                   :deliver (fn [message]
                              (when-not (= "cancel" (:kind message))
                                (locking out
                                  (println (json/generate-string message))
                                  (flush))))})]
    (doseq [line (line-seq (io/reader System/in))]
      (let [[cmd arg] (str/split line #" " 2)]
        (try
          (case cmd
            "ack" (ack! p arg)
            "retry" (retry! p arg)
            "busy" (busy! p (= "true" arg))
            nil)
          (catch Exception e
            (binding [*out* *err*] (println "bbh inbox:" (ex-message e)))))))
    (stop! p)))
