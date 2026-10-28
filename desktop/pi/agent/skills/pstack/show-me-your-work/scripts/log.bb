#!/usr/bin/env bb
;; Append a well-formed row to a show-me-your-work decision log (TSV).
;; Usage: log.bb <logfile> <phase> <decision> <why> <evidence> <result>
(require '[babashka.fs :as fs] '[clojure.string :as str])

(when-not (= 6 (count *command-line-args*))
  (binding [*out* *err*] (println "usage: log.bb <logfile> <phase> <decision> <why> <evidence> <result>"))
  (System/exit 1))

(let [[logfile & cells] *command-line-args*]
  (some-> (fs/parent logfile) fs/create-dirs)
  ;; Append here, never overwrite. A network mount can fail this test for a log
  ;; that exists. Then the cost is one stray header line, not the rows.
  (when-not (and (fs/exists? logfile) (pos? (fs/size logfile)))
    (spit logfile "ts\tphase\tdecision\twhy\tevidence\tresult\n" :append true))
  ;; Strip tabs/newlines/CR so cells stay on one line, and prefix any cell
  ;; whose first char a spreadsheet would parse as a formula (=, +, -, @)
  ;; with a single quote. The skill expects this log to be read in
  ;; spreadsheets, so attacker-controlled evidence (PR titles, filenames,
  ;; generated text) must not become formula execution when a reviewer
  ;; opens the file.
  (let [clean #(let [v (str/replace % #"[\t\n\r]" " ")]
                 (if (re-find #"^[=+\-@]" v) (str "'" v) v))
        ts (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS))]
    (spit logfile (str (str/join "\t" (cons ts (map clean cells))) "\n") :append true)))
