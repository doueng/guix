(ns rexmux.live-test
  "The sidecar process against a real Rex server (testserver): it loads the
  help document, numbers its messages, reports a pane's OSC 7501 status, and
  the effects it returns work when applied to Rex, as the window would."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [rexmux.proto :as proto]
            [rexmux.testserver :as ts]))

(def ^:dynamic *srv* nil)
(def ^:private project (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*))))))

(defn sidecar!
  "Start the sidecar on *srv*; returns {:proc :send! :next!}."
  []
  (let [cfg (str (:root *srv*) "/rexmux.edn")
        _ (spit cfg (pr-str {:servers [{:label "test" :address (:address *srv*)}]}))
        proc (p/process {:dir project
                         :env (assoc (ts/env *srv*) "REXMUX_CONFIG" cfg)
                         :err :inherit}
                        "bb" "sidecar")
        r (io/reader (:out proc))
        w (io/writer (:in proc))
        seqn (atom 0)]
    {:proc proc
     :send! (fn [m] (.write w (str (proto/encode (assoc m :seq (swap! seqn inc))) "\n")) (.flush w))
     :next! (fn [pred ms]
              (let [deadline (+ (System/currentTimeMillis) ms)]
                (loop [seen []]
                  (if (> (System/currentTimeMillis) deadline)
                    (throw (ex-info "timed out" {:seen (map :op seen)}))
                    (let [m (proto/decode (.readLine r))]
                      (if (pred m) (conj seen m) (recur (conj seen m))))))))}))

(defn apply-effect!
  "Do what the window does for a :rex/call effect, through the rex CLI."
  [[kind method args]]
  (when (= :rex/call kind)
    (let [{:keys [exit out err]} (ts/rex! *srv* "api" "call" method (json/generate-string args))]
      (if (zero? exit) (json/parse-string out true) (throw (ex-info err {:method method}))))))

(deftest sidecar-drives-a-real-server
  (let [created (json/parse-string (:out (ts/rex! *srv* "new" "work" "--json" "--" "sh" "-c" "sleep 2; printf '\\033]7501;state=blocked:kind=question:app=pi:msg=V2hpY2ggZmlsZT8=\\033\\\\'; sleep 60")) true)
        session (:session_id created)
        block (first (:block_ids (first (:initial_windows created))))
        {:keys [send! next! proc]} (sidecar!)
        ready (next! #(= :ready (:op %)) 10000)
        _ (send! {:op :hello :client-id "client:test" :ctx {:session-id session :block-id block}})
        hello (next! #(= :commands/changed (:op %)) 20000)
        cat (last hello)]
    (is (= 1 (:seq (last ready))))
    (is (< 90 (count (:commands cat))))
    (is (= [] (:conflicts cat)))
    (let [agents (next! #(and (= :agents (:op %)) (seq (:rows %))) 15000)]
      (is (= "blocked" (:state (first (:rows (last agents))))))
      (is (= block (:block (first (:rows (last agents))))))
      (is (apply < (map :seq (concat ready hello agents)))))
    (send! {:op :commands/invoke :id 1 :command :agent/next-blocked :args {}})
    (let [reply (last (next! #(= :reply (:op %)) 10000))]
      (is (= [:focus/block {:block block :session session :server "test"}] (first (:effects reply)))))
    (send! {:op :commands/invoke :id 2 :command :rex/session.new_split
            :args {:direction "horizontal" :side "after" :layout {:block {:flavor "com.superlogical.terminal.shell"}}}})
    (let [reply (last (next! #(= :reply (:op %)) 10000))
          result (apply-effect! (first (:effects reply)))
          view (json/parse-string (:out (ts/rex! *srv* "api" "call" "session.view" (json/generate-string {:session_id session}))) true)]
      (is (= 2 (:id reply)))
      (is (integer? (:revision result)))
      (is (= 2 (count (set (re-seq #"block:[0-9a-f-]+" (json/generate-string (:windows view))))))))
    (send! {:op :commands/invoke :id 3 :command :rex/session.focus_direction :args {:direction "sideways"}})
    (is (= [[:direction]] (map :path (:invalid (last (next! #(= :reply (:op %)) 10000))))))
    (send! {:op :bye})
    (is (= 0 (:exit (deref proc 10000 {:exit :timeout}))))))

(defn -main [& _]
  (let [srv (ts/start!)]
    (try
      (binding [*srv* srv]
        (let [{:keys [fail error]} (t/run-tests 'rexmux.live-test)]
          (ts/stop! srv)
          (System/exit (if (zero? (+ fail error)) 0 1))))
      (finally (ts/stop! srv)))))
