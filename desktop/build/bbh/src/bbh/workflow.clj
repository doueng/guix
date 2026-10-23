(ns bbh.workflow
  "Runs pstack workflows as state machines. Gates run here, so a claim alone never advances a phase."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-max-visits 3)

(defn state-root []
  (str (fs/path (or (System/getenv "XDG_STATE_HOME") (fs/expand-home "~/.local/state"))
                "bbh" "workflows")))

(defn- run-dir [id] (str (fs/path (state-root) id)))
(defn- journal-file [id] (str (fs/path (run-dir id) "journal.edn")))
(defn- evidence-dir [id] (str (fs/path (run-dir id) "evidence")))

(defn load-workflow [wf-name]
  (if-let [res (io/resource (str "bbh/workflows/" (name wf-name) ".edn"))]
    (edn/read-string (slurp res))
    (throw (ex-info (str "unknown workflow " (name wf-name)) {:workflow wf-name}))))

;; The journal is the source of truth. State is folded from it.

(defn fold-event [st {:keys [type] :as e}]
  (case type
    :start   (merge st (select-keys e [:id :workflow :cwd :task :repro :check :phase])
                    {:status :active :visits {(:phase e) 1} :verdicts []})
    :verdict (update st :verdicts conj e)
    :attest  (assoc st :last-attest e)
    :enter   (-> st
                 (assoc :phase (:phase e))
                 (assoc-in [:visits (:phase e)] (:visit e)))
    :done    (assoc st :status :done)
    :block   (assoc st :status :blocked :block-reason (:reason e))))

(defn replay [events] (reduce fold-event {} events))

(defn- verdict-for [st phase visit]
  (last (filter #(and (= phase (:phase %)) (= visit (:visit %))) (:verdicts st))))

(defn- enter [wf st phase]
  (let [visit (inc (get-in st [:visits phase] 0))
        cap (get (get-in wf [:phases phase]) :max-visits default-max-visits)]
    (if (> visit cap)
      [{:type :block :phase phase :reason (str "phase " (name phase) " exceeded " cap " visits")}]
      [{:type :enter :phase phase :visit visit}])))

(defn- failed? [g] (and (not (:pass? g)) (not (:pending? g))))

(defn outcome
  "A failed gate outranks a pending one, so missing evidence never hides behind a waiting verdict."
  [results]
  (cond (some failed? results) :fail
        (some :pending? results) :pending
        :else :pass))

(defn decide
  "Events that follow one attest. Pure: the phase spec and the gate results decide."
  [wf st results]
  (let [phase (:phase st)
        spec (get-in wf [:phases phase])]
    (cond
      (= :pending (outcome results)) []
      (= :pass (outcome results)) (if-let [nxt (:next spec)]
                                (enter wf st nxt)
                                [{:type :done}])
      :else (enter wf st (or (:on-fail spec) phase)))))

(defn- sha256 [s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- command-text [st cmd]
  (case cmd :repro (:repro st) :check (:check st) cmd))

(def default-timeout-seconds 600)

(defn- run-command [id st phase visit i gate]
  (if-let [cmd (command-text st (:cmd gate))]
    (let [limit (get gate :timeout default-timeout-seconds)
          {:keys [exit out err]} (p/shell {:dir (:cwd st) :out :string :err :string :continue true}
                                          "timeout" (str limit) "sh" "-c" cmd)
          timed-out? (= 124 exit)
          text (str out err (when timed-out? (str "\ntimed out after " limit "s")))
          evidence (str (evidence-dir id) "/" (name phase) "-" visit "-" i ".txt")]
      (spit evidence (str "$ " cmd "\n" text "\nexit " exit "\n"))
      {:index i :kind :command :cmd cmd :exit exit :evidence evidence :sha256 (sha256 text)
       :timed-out? timed-out?
       :pass? (and (not timed-out?)
                   (if (= :zero (get gate :expect :zero)) (zero? exit) (not (zero? exit))))})
    (if (:optional gate)
      {:index i :kind :command :skipped? true :pass? true}
      {:index i :kind :command :pass? false
       :detail (str "no " (name (:cmd gate)) " command was given at start")})))

(defn- run-artifact [id i gate]
  (let [f (fs/path (evidence-dir id) (:path gate))]
    {:index i :kind :artifact :path (str f)
     :pass? (boolean (and (fs/exists? f) (pos? (fs/size f))))}))

(defn- run-verdict [st phase visit i gate]
  (if-let [v (verdict-for st phase visit)]
    {:index i :kind :verdict :criteria (:criteria gate) :reviewer (:reviewer v)
     :pass? (= :pass (:verdict v))}
    {:index i :kind :verdict :criteria (:criteria gate) :pending? true}))

(defn- run-gates [wf id st]
  (let [phase (:phase st)
        visit (get-in st [:visits phase])]
    (vec (map-indexed
          (fn [i gate]
            (case (:kind gate)
              :command  (run-command id st phase visit i gate)
              :artifact (run-artifact id i gate)
              :verdict  (run-verdict st phase visit i gate)))
          (get-in wf [:phases phase :gates])))))

(defn- read-journal [id]
  (->> (str/split-lines (slurp (journal-file id)))
       (remove str/blank?)
       (mapv edn/read-string)))

(defn- current [id]
  (when-not (fs/exists? (journal-file id))
    (throw (ex-info (str "no run named " id) {:id id})))
  (replay (read-journal id)))

(defn- append! [id event]
  (let [e (assoc event :at (str (java.time.Instant/now)))]
    (spit (journal-file id) (str (pr-str e) "\n") :append true)
    e))

(defn start! [wf-name {:keys [cwd task repro check]}]
  (let [wf (load-workflow wf-name)]
    (when (and (some #{:check} (:requires wf)) (nil? check))
      (throw (ex-info (str (name wf-name) " needs --check, a command that proves the change")
                      {:workflow wf-name})))
    (let [id (str (.format (java.time.LocalDateTime/now)
                           (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss-SSS"))
                  "-" (name wf-name))]
      (fs/create-dirs (evidence-dir id))
      (append! id {:type :start :id id :workflow wf-name
                   :cwd (str (fs/canonicalize (or cwd ".")))
                   :task task :repro repro :check check :phase (:start wf)})
      id)))

(defn status [id]
  (let [st (current id)]
    (assoc st :brief (get-in (load-workflow (:workflow st)) [:phases (:phase st) :brief])
              :evidence (evidence-dir id))))

(defn attest! [id claim]
  (let [st (current id)]
    (when-not (= :active (:status st))
      (throw (ex-info (str "run is " (name (:status st))) {:id id})))
    (let [wf (load-workflow (:workflow st))
          results (run-gates wf id st)
          result (outcome results)
          attested (append! id {:type :attest :phase (:phase st)
                                :visit (get-in st [:visits (:phase st)])
                                :claim claim :outcome result :gates results})]
      (doseq [e (decide wf (fold-event st attested) results)]
        (append! id e))
      {:outcome result :results results})))

(defn verdict! [id reviewer verdict note]
  (let [st (current id)]
    (when (str/blank? reviewer)
      (throw (ex-info "verdict needs --reviewer" {})))
    (when-not (#{:pass :fail} verdict)
      (throw (ex-info "verdict must be pass or fail" {:verdict verdict})))
    (append! id {:type :verdict :phase (:phase st) :visit (get-in st [:visits (:phase st)])
                 :reviewer reviewer :verdict verdict :note note})))

(defn latest-id []
  (when (fs/exists? (state-root))
    (->> (fs/list-dir (state-root)) (map fs/file-name) sort last)))

(defn- configured-session-dir [files]
  (reduce (fn [acc file]
            (if-not (fs/exists? file)
              acc
              (let [v (:sessionDir (json/parse-string (slurp (str file)) true))]
                (cond (nil? v) acc
                      (or (not (string? v)) (str/blank? v))
                      (throw (ex-info (str "Invalid sessionDir in " file) {:file (str file)}))
                      :else v))))
          nil files))

(defn session-dir
  "Pi's session directory for a workspace. Resolution order matches the Pi settings docs."
  ([workspace] (session-dir workspace nil))
  ([workspace explicit]
   (let [env (System/getenv)
         cwd (str (fs/normalize (fs/absolutize (or workspace "."))))
         home (or (get env "HOME") (System/getProperty "user.home"))
         resolve-path (fn [value]
                        (let [v (cond (= value "~") home
                                      (str/starts-with? value "~/") (str home (subs value 1))
                                      :else value)]
                          (str (fs/normalize (fs/path cwd v)))))
         agent (resolve-path (or (get env "PI_CODING_AGENT_DIR") (str home "/.pi/agent")))]
     (if-let [override (or explicit (get env "PI_CODING_AGENT_SESSION_DIR"))]
       (resolve-path override)
       (if-let [configured (configured-session-dir [(fs/path agent "settings.json")
                                                    (fs/path cwd ".pi" "settings.json")])]
         (resolve-path configured)
         (let [slug (-> cwd
                        (str/replace #"^[/\\]" "")
                        (str/replace #"[/\\:]" "-"))]
           (str (fs/path agent "sessions" (str "--" slug "--")))))))))

(defn- describe-gate [g]
  (case (:kind g)
    :command (cond (:skipped? g) "optional check skipped, none given at start"
                   (:detail g) (:detail g)
                   (:timed-out? g) (str "`" (:cmd g) "` timed out, output in " (:evidence g))
                   :else (str "`" (:cmd g) "` exited " (:exit g) ", output in " (:evidence g)))
    :artifact (str "artifact " (:path g))
    :verdict (if (:pending? g)
               (str "verdict pending: " (:criteria g))
               (str "verdict " (if (:pass? g) "pass" "fail") " by " (:reviewer g)))))

(defn- print-attest [{:keys [outcome results]}]
  (println (str "attest: " (name outcome)))
  (doseq [g results]
    (println (str "  [" (cond (:pending? g) "wait" (:pass? g) "ok" :else "FAIL") "] " (describe-gate g)))))

(defn- print-status [st]
  (println (str "run " (:id st)))
  (println (str "workflow " (name (:workflow st)) "  phase " (name (:phase st))
                "  visit " (get-in st [:visits (:phase st)]) "  status " (name (:status st))))
  (when (:block-reason st) (println (str "blocked: " (:block-reason st))))
  (println (str "brief: " (:brief st)))
  (println (str "evidence: " (:evidence st))))

(def ^:private usage
  "usage:
  wf start <workflow> [--task T] [--repro CMD] [--check CMD] [--cwd DIR]
  wf status [RUN]
  wf attest [RUN] --claim TEXT
  wf verdict [RUN] --reviewer NAME --verdict pass|fail [--note TEXT]
RUN defaults to the latest run.")

(defn- parse-args [args]
  (loop [xs args, pos [], flags {}]
    (cond
      (empty? xs) {:pos pos :flags flags}
      (str/starts-with? (first xs) "--")
      (recur (drop 2 xs) pos (assoc flags (keyword (subs (first xs) 2)) (second xs)))
      :else (recur (rest xs) (conj pos (first xs)) flags))))

(defn -main [& argv]
  (let [[cmd & more] argv
        {:keys [pos flags]} (parse-args more)
        run-id (fn [] (or (first pos) (latest-id) (throw (ex-info "no runs yet" {}))))]
    (try
      (case cmd
        "start" (print-status (status (start! (keyword (first pos))
                                              (select-keys flags [:cwd :task :repro :check]))))
        "status" (print-status (status (run-id)))
        "attest" (let [id (run-id)]
                   (print-attest (attest! id (or (:claim flags)
                                                 (throw (ex-info "attest needs --claim" {})))))
                   (print-status (status id)))
        "session-dir" (println (session-dir (first pos) (second pos)))
        "verdict" (let [id (run-id)]
                    (verdict! id (:reviewer flags) (keyword (:verdict flags)) (:note flags))
                    (print-status (status id)))
        (do (println usage) (System/exit 2)))
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*] (println (ex-message e)))
        (System/exit 1)))))
