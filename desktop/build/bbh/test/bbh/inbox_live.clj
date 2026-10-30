(ns bbh.inbox-live
  "A real Doom session on a real model against a sandboxed real Inbox. It spends
  model usage, so it stays manual: bb test:inbox-live claude/haiku"
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [bbh.inbox :as inbox]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- wait-for [what pred ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (if (> (System/currentTimeMillis) end)
            (throw (ex-info (str "Timed out waiting for " what) {}))
            (do (Thread/sleep 250) (recur)))))))

(defn- window [method path & [body]]
  (let [{:keys [port serverSession]} (inbox/connection)]
    (-> (http/request {:method method :uri (str "http://127.0.0.1:" port path)
                       :headers {"Content-Type" "application/json"}
                       :body (when body (json/generate-string (assoc body :serverSession serverSession)))})
        :body
        (json/parse-string true))))

(defn- fail! [message]
  (println "FAILED:" message)
  (throw (ex-info message {})))

(defn- bbh-threads []
  (->> (:sections (window :get "/api/inbox/threads?archived=exclude"))
       (mapcat :threads)
       (filter #(= "bbh" (get-in % [:author :host])))))

(defn- bbh-thread [] (first (bbh-threads)))

(defn- section-of [thread-id]
  (->> (:sections (window :get "/api/inbox/threads?archived=exclude"))
       (filter #(some (fn [t] (= thread-id (:thread_id t))) (:threads %)))
       first
       :id))

(defn- replies [home text]
  (->> (fs/glob home "bbh/conversations/*/messages.jsonl")
       (mapcat #(str/split-lines (slurp (str %))))
       (map #(json/parse-string % true))
       (filter #(and (= "assistant" (:role %)) (str/includes? (str (:content %)) text)))
       count))

(defn- wakes [home text]
  (->> (fs/glob home "bbh/conversations/*/messages.jsonl")
       (mapcat #(str/split-lines (slurp (str %))))
       (map #(json/parse-string % true))
       (filter #(and (= "user" (:role %)) (str/includes? (str (:content %)) text)))
       count))

(defn -main [& [model]]
  (let [bin (or (System/getenv "PLANNOTATOR_BIN") (str (fs/which "plannotator")))
        home (System/getenv "PLANNOTATOR_DATA_DIR")
        project (str (fs/create-dirs (fs/path home "project")))
        answer "purple-giraffe-answer"
        server (p/process {:out :discard :err :discard :dir home
                           :extra-env {"HOME" home "PLANNOTATOR_INBOX_CONNECT" "0"
                                       "PLANNOTATOR_UPDATE_CHECK" "0" "BROWSER" "/bin/false"}}
                          bin "inbox" "--no-open")
        _ (wait-for "the Inbox" inbox/connection 15000)
        doom (p/process {:in :pipe :out :discard :err :discard :dir project
                         :extra-env {"TERM" "xterm-256color" "XDG_DATA_HOME" home
                                     "BBH_MODEL" model "HERDR_ENV" ""}}
                        "script" "-qec"
                        (str "stty rows 40 cols 120; " (fs/home) "/.local/bin/emacs-bbh -nw") "/dev/null")
        send! (fn [text]
                (.write (:in doom) (.getBytes (str text "\u000e")))
                (.flush (:in doom)))
        conversation (fn [] (first (fs/glob home "bbh/conversations/*/repl.port")))]
    (try
      (wait-for "the session" conversation 90000)
      (Thread/sleep 4000)
      (send! "/poteto-mode-off")
      (Thread/sleep 1500)
      (send! "Reply with only the word READY.")
      (wait-for "the status reply" #(pos? (replies home "READY")) 120000)
      (Thread/sleep 5000)
      (when (bbh-thread)
        (fail! "A status reply reached the Inbox"))
      (println "A status reply stayed in the chat")
      (send! "Ask me which colour I want for the banner, then stop and wait for my answer. Do not use any tools.")
      (let [{:keys [thread_id]} (wait-for "the agent's question in the Inbox" bbh-thread 120000)
            messages #(get-in (window :get (str "/api/inbox/threads/" thread_id)) [:thread :messages])
            person #(first (filter (fn [m] (= "person" (get-in m [:author :kind]))) (messages)))]
        (when-not (= "waiting" (section-of thread_id))
          (fail! (str "The question sits in " (section-of thread_id))))
        (println "The question is in Waiting on you")
        (Thread/sleep 3000)
        (window :post (str "/api/inbox/messages/" (:id (first (messages))) "/reply")
                {:words (str "I want " answer ", thanks, go ahead.") :idempotency_key "live-reply"})
        (wait-for "the wake in messages.jsonl" #(pos? (wakes home answer)) 60000)
        (wait-for "delivered" #(= "delivered" (get-in (person) [:delivery :state])) 120000)
        (wait-for "the agent's answer in the thread"
                  #(and (= "agent" (get-in (last (messages)) [:author :kind]))
                        (> (count (messages)) 2))
                  60000)
        (when-not (= 1 (wakes home answer))
          (fail! "The wake repeated"))
        (println "The reply woke the session once and its answer came back")
        (inbox/tool! {:session "live-test" :project project} "resolve_message" {:message_id thread_id})
        (send! "Ask me which size I want for the banner, then stop and wait for my answer. Do not use any tools.")
        (let [next-id (:thread_id (wait-for "the second question"
                                            #(first (remove (fn [t] (= thread_id (:thread_id t))) (bbh-threads)))
                                            120000))]
          (Thread/sleep 4000)
          (send! "Large.")
          (wait-for "the thread to close" #(nil? (section-of next-id)) 60000)
          (println "Answering in Emacs closed the thread")))
      (finally
        (p/destroy-tree doom)
        (p/destroy-tree server)))
    (System/exit 0)))
