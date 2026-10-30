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

(defn- bbh-thread []
  (->> (:sections (window :get "/api/inbox/threads?archived=exclude"))
       (mapcat :threads)
       (filter #(= "bbh" (get-in % [:author :host])))
       first))

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
      (send! "Run (inbox \"send_message\" {:body \"Live test\" :subject \"Live test\"}) with the clojure tool, then say POSTED.")
      (let [{:keys [thread_id] :as thread} (wait-for "the agent's message" bbh-thread 120000)
            message (get-in (window :get (str "/api/inbox/threads/" thread_id)) [:thread :messages 0 :id])]
        (println "Posted as" (get-in thread [:author :session]))
        (Thread/sleep 3000)
        (window :post (str "/api/inbox/messages/" message "/reply")
                {:words (str "The word is " answer ". Please confirm you got it.") :idempotency_key "live-reply"})
        (wait-for "the wake in messages.jsonl" #(pos? (wakes home answer)) 60000)
        (let [delivery (wait-for "delivered"
                                 #(->> (get-in (window :get (str "/api/inbox/threads/" thread_id)) [:thread :messages])
                                       (filter (fn [m] (= "person" (get-in m [:author :kind]))))
                                       first
                                       :delivery
                                       :state)
                                 120000)
              n (wakes home answer)]
          (println "Delivery" delivery "wake count" n)
          (wait-for "the agent's answer"
                    #(= "agent" (get-in (last (get-in (window :get (str "/api/inbox/threads/" thread_id)) [:thread :messages]))
                                        [:author :kind]))
                    60000)
          (when-not (and (= "delivered" delivery) (= 1 n))
            (System/exit 1))))
      (finally
        (p/destroy-tree doom)
        (p/destroy-tree server)))
    (System/exit 0)))
