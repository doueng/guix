(ns bbh.inbox
  "What a live session and the website share, kept in the conversation's
  directory so that neither needs the other running. A session announces itself
  in agent.json, and mailbox/ holds the messages it has not taken yet. The
  directory is the rollover root's, so a rollover keeps both.

  The session takes its own mail and deletes a message once its transcript
  holds it. A sender that names a message after what it owes (`send!` with
  :message-id) can send again after a crash without leaving two, and a session
  skips a message its transcript already holds, so such mail is read once."
  (:require [babashka.fs :as fs]
            [bbh.conversation :as conversation]
            [bbh.super.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private scan-ms 500)

(defn- mailbox [root] (conversation/file root :mailbox))

(defn- agent-file [root] (conversation/file root :agent))

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
  (let [dir (fs/create-dirs (conversation/file root :images))]
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
  its PROJECT, whether it is BUSY in a turn, as epoch ms when the person
  last typed a prompt into it (PROMPTED-AT), and the usage LIMIT that stopped
  its last turn, {:at :until :window :text}, which bbh super pauses it on."
  [{:keys [root] :as p} busy]
  (let [tmp (fs/create-temp-file {:dir (fs/create-dirs root) :prefix ".agent"})]
    (spit (str tmp) (json/generate-string (cond-> {:pid (.pid (java.lang.ProcessHandle/current))
                                                   :start (:start (process/identity-of (.pid (java.lang.ProcessHandle/current))))
                                                   :project (:project p) :busy busy}
                                            (:prompted-at p) (assoc :prompted-at (:prompted-at p))
                                            (:limit p) (assoc :limit (:limit p)))))
    (fs/move tmp (agent-file root) {:atomic-move true :replace-existing true})))

(defn agent
  "The live session of conversation ROOT as {:pid :project :busy :prompted-at :limit}, or nil."
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

(defn- message-file [root id] (fs/path (mailbox root) (str (fs/file-name id) ".json")))

(defn ack!
  "Take message ID out of conversation ROOT's mailbox."
  [{:keys [root]} id]
  (fs/delete-if-exists (message-file root id)))

(defn waiting-since
  "When message ID was left in conversation ROOT's mailbox, as epoch ms, or nil
  once its session took it."
  [root id]
  (try (.toMillis (fs/last-modified-time (message-file root id)))
       (catch java.io.IOException _ nil)))
