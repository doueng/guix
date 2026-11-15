(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def root (str (fs/parent (fs/parent (fs/parent (fs/parent (fs/real-path *file*)))))))
(def script (str root "/desktop/bin/clipboard-history"))
(def cliphist-dir
  (or (some-> (fs/which "cliphist") fs/parent str)
      (str (str/trim (:out (p/sh "guix" "build" "cliphist"))) "/bin")))
(def db (str (fs/create-temp-file {:suffix ".db"})))
(fs/delete db)
(def env {"PATH" (str cliphist-dir ":" (System/getenv "PATH"))
          "CLIPHIST_DB_PATH" db
          "HYPRLAND_INSTANCE_SIGNATURE" "none"})

(defn store! [in & [state]]
  (p/sh {:in in :extra-env (cond-> env state (assoc "CLIPBOARD_STATE" state))} "bb" script "store"))

(defn history []
  (->> (str/split-lines (:out (p/sh {:extra-env env} (str cliphist-dir "/cliphist") "list")))
       (keep #(second (str/split % #"\t" 2)))))

(store! "first")
(store! "hunter2" "sensitive")
(store! (apply str (repeat (inc (* 5 1024 1024)) "x")))
(store! "second")
(store! "" "clear")
(store! "third")

(def results
  [["ordinary copies are kept, newest first" (= ["third" "first"] (history))]
   ["a sensitive copy is not stored" (not-any? #{"hunter2"} (history))]
   ["an entry over 5 MiB is not stored" (not-any? #(str/starts-with? % "xxx") (history))]
   ["clearing the clipboard forgets the newest entry" (not-any? #{"second"} (history))]])

(fs/delete-if-exists db)
(doseq [[label ok] results] (println (if ok "ok  " "FAIL") label))
(System/exit (if (every? second results) 0 1))
