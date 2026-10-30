(ns bbh.inbox-tty
  "The terminal front end's Inbox delivery, driven through a real PTY against a
  fake bridge. Run it with `bb test:inbox-tty`."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [org.httpkit.server :as server]))

(def ^:private steps
  ["one"
   "two-a" "two-b"
   {:code "(Thread/sleep 2500)"} "three-a" "three-b"
   {:code "(Thread/sleep 60000)"}
   {:fail "boom"} "five-a" "five-b"
   {:text "full" :usage {:input 1000}} "six-a" "six-b"
   "seven"])

(defn- bridge
  "A bridge that answers each session's commands until they are acked."
  [commands calls]
  (fn [{:keys [uri body]}]
    (let [{:keys [session type id] :as req} (json/parse-string (slurp body) true)
          poll? (str/ends-with? uri "/poll")]
      (swap! calls conj (assoc req :poll? poll?))
      (when (= "delivered" type)
        (swap! commands update session (fn [cs] (vec (remove #(= id (:id %)) cs)))))
      (when poll? (Thread/sleep 50))
      {:status 200 :headers {"Content-Type" "application/json"}
       :body (json/generate-string (if poll? {:commands (get @commands session [])} {:ok true}))})))

(defn- wait-for [what pred ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) end) (do (is false (str "timed out waiting for " what)) false)
            :else (do (Thread/sleep 50) (recur))))))

(defn- command [id]
  {:type "reply" :id id :thread_id "t" :subject "s" :body (str "wake-" id)})

(defn- users [root]
  (->> (fs/glob root "**/messages.jsonl")
       (mapcat #(str/split-lines (slurp (str %))))
       (map #(json/parse-string % true))
       (filter #(= "user" (:role %)))
       (map :content)))

(defn- count-of [root text]
  (count (filter #(str/includes? % text) (users root))))

(deftest the-terminal-delivers-and-acks-through-a-real-pty
  (let [home (str (fs/create-temp-dir {:prefix "bbh-inbox-tty"}))
        conversations (fs/create-dirs (fs/path home "bbh" "conversations"))
        script (str (fs/path home "script.edn"))
        init (str (fs/path home "init.clj"))
        commands (atom {})
        calls (atom [])
        stop (server/run-server (bridge commands calls) {:port 0 :ip "127.0.0.1" :legacy-return-value? false})
        _ (do (spit script (pr-str steps))
              (spit init "(ns bbh.env) (def manifest {:rollover-at-tokens 1000})")
              (spit (str (fs/path (fs/create-dirs (fs/path conversations "other")) "messages.jsonl"))
                    (str (json/generate-string {:role "user" :content "before"}) "\n"))
              (spit (str (fs/path (fs/create-dirs (fs/path home "inbox")) "inbox.json"))
                    (json/generate-string {:port (server/server-port stop) :token "t"})))
        log (str (fs/path home "tty.log"))
        proc (p/process {:in :pipe :out :write :out-file (fs/file log) :err :out
                         :extra-env {"TERM" "xterm-256color" "XDG_DATA_HOME" home
                                     "PLANNOTATOR_DATA_DIR" home "BBH_INIT" init
                                     "BBH_MODEL" (str "script/" script) "HERDR_ENV" ""}}
                        "script" "-qefc" "stty cols 200; bb --config bb.edn -m bbh.main" "/dev/null")
        keys! (fn [text] (.write (:in proc) (.getBytes text)) (.flush (:in proc)))
        ids (fn [] (->> @calls (filter :poll?) (map :session) set))
        acked (fn [id] (some #(and (= "delivered" (:type %)) (= id (:id %))) @calls))
        wake! (fn [session id] (swap! commands update session (fnil conj []) (command id)))
        said? (fn [text] (str/includes? (slurp log) text))
        first-id #(first (disj (ids) "other"))]
    (try
      (wait-for "the first poll" #(seq (ids)) 20000)
      (let [root (first-id)]
        ;; An idle prompt takes a wake by itself.
        (wake! root "m1")
        (wait-for "m1 acked" #(acked "m1") 10000)
        (is (= 1 (count-of home "wake-m1")))

        ;; A draft holds the wake back until that prompt ran.
        (keys! "half typ")
        (Thread/sleep 300)
        (wake! root "m2")
        (Thread/sleep 2500)
        (is (zero? (count-of home "wake-m2")) "a draft is never interrupted")
        (keys! "ed\r")
        (wait-for "m2 acked" #(acked "m2") 10000)
        (is (= 1 (count-of home "half typed")))
        (is (= 1 (count-of home "wake-m2")))

        ;; A wake during a turn runs after it, once, though the bridge repeats it.
        (keys! "sleep\r")
        (wait-for "the sleep" #(said? "Thread/sleep 2500") 5000)
        (wake! root "m3")
        (Thread/sleep 1000)
        (is (zero? (count-of home "wake-m3")))
        (wait-for "m3 acked" #(acked "m3") 10000)
        (is (= 1 (count-of home "wake-m3")))

        ;; Ctrl-C cancels a wake's turn, and the wake reached the transcript.
        (wake! root "m4")
        (wait-for "the long sleep" #(said? "Thread/sleep 60000") 10000)
        (keys! "\u0003")
        (wait-for "m4 acked" #(acked "m4") 15000)

        ;; A failed turn is not acked, and the next completed turn sends it again.
        (wake! root "m5")
        (wait-for "the failure" #(said? "boom") 10000)
        (Thread/sleep 1500)
        (is (not (acked "m5")))
        (is (= 1 (count-of home "wake-m5")))
        (keys! "works\r")
        (wait-for "m5 acked" #(acked "m5") 10000)
        (is (= 2 (count-of home "wake-m5")) "at least once, because the failed turn recorded it")

        ;; A rollover keeps the inbox id, and the wake lands in the new conversation.
        (keys! "fill\r")
        (wait-for "the full reply" #(said? "full") 5000)
        (keys! "after\r")
        (wait-for "the rolled reply" #(said? "six-a") 5000)
        (wake! root "m6")
        (wait-for "m6 acked" #(acked "m6") 10000)
        (is (= 1 (count (fs/glob home "bbh/conversations/*/continued-in"))))
        (is (= #{root} (ids)))

        ;; /resume moves the poller to that conversation's inbox id.
        (keys! "/resume other\r")
        (wait-for "polling as other" #((ids) "other") 10000)
        (wake! "other" "m7")
        (wait-for "m7 acked" #(acked "m7") 10000)
        (let [polls (filter :poll? @calls)]
          (is (= "other" (:session (last polls))))))
      (keys! "/quit\r")
      (finally
        (p/destroy-tree proc)
        (server/server-stop! stop)
        (when (some-> (System/getenv "BBH_INBOX_TTY_LOG") (= "1"))
          (println (slurp log)))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'bbh.inbox-tty)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
