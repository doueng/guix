(ns bbh.super.verbs
  "One table for the super-* helpers that call `bbh super`: the Router's, in its
  sandbox, and a Worker's, in its REPL. Each entry names the command it sends,
  its positional :args, which become the command's argument keys, the
  trailing :options it allows, given as keywords and values or as one map,
  and the :fixed arguments it always adds. An entry with :map takes one map,
  the command's arguments, instead: with the keys :map allows, or any when it
  is true. This namespace requires nothing, so a
  REPL can load it at start."
  (:require [clojure.string :as str]))

(def ^:private reads
  {'super-threads {:command :threads :doc "Every thread, by id."}
   'super-projects {:command :projects :doc "The repositories Workers start in, by name: {:repo :check :about}."}
   'super-thread {:command :thread :args [:id] :doc "Thread ID with its asks, replies and Workers."}
   'super-thread! {:command :thread! :args [:title] :doc "A new thread titled TITLE; returns its handle."}
   'super-start! {:command :start-worker! :args [:thread :brief] :options #{:for :workflow :project :repo :check :bench :repro :replaces}
                  :doc "Start a Worker on THREAD with BRIEF. Options: :workflow :project :repo
  :check :bench :repro :for. What you leave out comes from the thread's last
  Worker; :project names one of (super-projects) and brings its repo and
  check, and :repo, a path, is for a new project or one not listed (one that
  does not exist is made as a new jj repository). An archived thread is
  reopened, and a Worker whose session ended is replaced. :for is the id that
  opens the message you act on, such as \"o12\". :replaces, optional, names the
  Worker you expect to replace, refused unless it is the thread's. Returns its id and state."}})

(def router
  "The Router's verbs. Every one that acts takes a thread id; steer and cancel also take the thread's Worker id."
  (merge reads
         {'super-ask {:command :ask :args [:id] :doc "Ask ID."}
          'super-reply {:command :reply :args [:id] :doc "Reply ID."}
          'super-worker {:command :worker :args [:id] :doc "Worker ID."}
          'super-events {:command :events :map #{:thread :after} :doc "Events {:thread id :after seq}, oldest first."}
          'super-steer! {:command :steer! :args [:thread :text] :options #{:for}
                         :doc "Mail TEXT to THREAD's Worker, resuming it first when its session ended. THREAD may be the Worker's id."}
          'super-cancel! {:command :cancel! :args [:thread :reason] :options #{:for} :doc "Cancel THREAD's Worker, saying REASON. THREAD may be the Worker's id."}
          'super-note! {:command :note! :args [:thread :text] :doc "Set THREAD's summary line."}
          'super-milestone! {:command :milestone! :args [:thread :text] :doc "Mark progress on THREAD worth the person's notice."}}))

(def worker
  "A Worker's verbs. It acts on its own thread and itself, which its token names."
  (merge reads
         {'super-ask! {:command :ask! :map true
                       :doc "File ASK ({:kind :text :options :recommended :details :evidence :stopped :holds-up :supersedes :revision}) and return its id. :text is one line; :details and the :evidence file hold the rest."}
          'super-answered! {:command :answered! :args [:reply] :doc "Record that reply ID has been acted on."}
          'super-note! {:command :note! :args [:text] :doc "Set the thread's summary line."}
          'super-milestone! {:command :milestone! :args [:text] :doc "Mark progress worth the person's notice."}
          'super-done! {:command :finish-worker! :options #{:onto} :fixed {:state-after :done}
                        :doc "End this Worker as done. The controller first rebases its revision onto the
  tip of the line; :onto commit-id names the tip when the line has two.
  When the rebase leaves conflicts it returns {:conflicts :todo} instead and
  the Worker keeps running: resolve them, then call it again."}
          'super-failed! {:command :finish-worker! :args [:reason] :fixed {:state-after :failed}
                          :doc "End this Worker as failed, saying REASON."}}))

(defn- refuse [sym message]
  (throw (ex-info (str "bbh super: " sym " " message) {:error :invalid})))

(defn- checked
  "OPTS, refused when a key is not in ALLOWED."
  [sym opts allowed]
  (when-let [bad (seq (remove allowed (keys opts)))]
    (refuse sym (str "has no option " (pr-str (first bad)) "; allowed: " (str/join " " (sort allowed)))))
  opts)

(defn usage
  "How to call SYM, as a list: (super-steer! thread text & {:for})."
  [sym {:keys [args options map]}]
  (str "(" (str/join " " (concat [sym] (cond (set? map) [(str "{" (str/join " " (sort map)) "}")] map ["{...}"] :else (clojure.core/map name args))
                                    (when options [(str "& {" (str/join " " (sort options)) "}")])))
       ")"))

(defn verb
  "The function for SYM's entry SPEC, which sends its command through CALL!
  (command args) and returns what that returns."
  [call! sym {:keys [command args options map fixed] :as spec}]
  (fn [& xs]
    (if map
      (let [[m & more] xs]
        (when (or more (not (or (nil? m) (map? m)))) (refuse sym (str "takes one map: " (usage sym spec))))
        (call! command (merge (if (set? map) (checked sym m map) m) fixed)))
      (let [[pos more] (split-at (count args) xs)
            opts (cond (empty? more) {}
                       (and (= 1 (count more)) (or (nil? (first more)) (map? (first more)))) (first more)
                       (even? (count more)) (apply hash-map more)
                       :else (refuse sym (str "takes " (usage sym spec))))]
        (when (< (count pos) (count args)) (refuse sym (str "takes " (usage sym spec))))
        (when (and (seq opts) (empty? options)) (refuse sym (str "takes no options: " (usage sym spec))))
        (call! command (merge (checked sym opts (or options #{})) (zipmap args pos) fixed))))))

(defn bind
  "{sym fn} for every verb in TABLE, each sending through CALL!."
  [call! table]
  (into {} (for [[sym spec] table] [sym (verb call! sym spec)])))
