#!/usr/bin/env bb
;; Usage: bb desktop/build/keybindings/palette-bench.bb BASELINE-REV OUTPUT-DIR
;; Opens and cancels ten palettes. Never types into the focused application.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])
(load-file "desktop/lib/dusk/palette.clj")

(defn command [& argv]
  (let [r @(p/process (vec argv) {:out :string :err :string})]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "Command failed: " (str/join " " argv)) r)))
    (str/trim-newline (:out r))))

(defn wait-for! [pred message]
  (loop [attempts 200]
    (cond (pred) true
          (zero? attempts) (throw (ex-info message {}))
          :else (do (Thread/sleep 10) (recur (dec attempts))))))

(defn launcher? [] (str/includes? (command "hyprctl" "layers") "dusk-launcher"))
(defn evaluate [server expression] (command "emacsclient" "-s" server "--eval" expression))
(defn millis [start] (/ (- (System/nanoTime) start) 1e6))
(defn summary [xs]
  (let [xs (vec (sort xs))]
    {:median (nth xs (quot (count xs) 2)) :min (first xs) :max (last xs) :n (count xs)}))

(let [[revision directory] *command-line-args*
      _ (assert (and revision directory) "Pass BASELINE-REV OUTPUT-DIR")
      root (str (fs/cwd))
      out (str (fs/absolutize directory))
      id (str (System/currentTimeMillis))
      servers {"before" (str "eng-bench-before-" id) "after" (str "eng-bench-after-" id)}
      editor "desktop/configs/doom/palette.el"
      config "desktop/configs/dusk/config.clj"
      runtime-classpath (str root "/desktop/build/dusk/runtime")
      rows (atom [])
      children (atom [])]
  (assert (not (launcher?)) "Close the existing palette before benchmarking")
  (fs/create-dirs out)
  (spit (str out "/machine.txt") (str (command "uptime") "\ncores " (command "nproc") "\n"))
  (doseq [[kind path] [["emacs" editor] ["config" config]]]
    (spit (str out "/before-" kind (if (= kind "emacs") ".el" ".clj"))
          (command "jj" "file" "show" "-r" revision path)))
  (fs/copy editor (str out "/after-emacs.el") {:replace-existing true})
  (fs/copy config (str out "/after-config.clj") {:replace-existing true})
  (try
    (doseq [[side server] servers]
      (swap! children conj (p/process ["emacs" (str "--fg-daemon=" server)]
                                      {:out (str out "/" side "-daemon.log") :err :out}))
      (wait-for! #(try (= "t" (evaluate server "t")) (catch Exception _ false)) "Emacs did not start")
      (evaluate server (str "(progn (load-file " (pr-str (str out "/" side "-emacs.el")) ")"
                            " (load-file " (pr-str (str root "/desktop/build/keybindings/palette-bench.el")) ")"
                            " (setq savehist-mode nil))"))
      (evaluate server (str "(palette-bench-export " (pr-str out) " " (pr-str side) " -1)")))
    (command "dusk" "msg" "eval"
             "(dusk.core/on-request :palette-bench (fn [{:keys [entries]} respond] (respond (count entries))))")
    (doseq [run (range 5) side ["before" "after"]]
      (let [server (servers side)
            prefix (str out "/" side "-" run)]
        (evaluate server (str "(palette-bench-export " (pr-str out) " " (pr-str side) " " run ")"))
        (command "bb" "--classpath" runtime-classpath "desktop/build/dusk/palette-bench.bb"
                 (str out "/" side "-config.clj") (str prefix ".edn") (str prefix "-runtime.json"))
        ;; Load the matching config without restarting Dusk, alternating sides.
        (command "dusk" "msg" "eval" (str "(load-file " (pr-str (str out "/" side "-config.clj")) ")"))
        (evaluate server "(palette-bench-warm)")
        (let [request (assoc (edn/read-string (slurp (str prefix ".edn"))) :name :palette-bench)
              start-small (System/nanoTime)
              small-reply (dusk.palette/request! (assoc-in request [:args :entries] [["probe"]]))
              small-ms (millis start-small)
              start-large (System/nanoTime)
              large-reply (dusk.palette/request! request)
              large-ms (millis start-large)
              _ (assert (and (:ok small-reply) (= 1 (:value small-reply))
                             (:ok large-reply) (= (count (get-in request [:args :entries])) (:value large-reply))))
              start (System/nanoTime)]
          (evaluate server "(palette-bench-open)")
          (wait-for! launcher? "Palette failed to map")
          (let [mapped-ms (millis start)]
            (command "dusk" "msg" "eval" "(config/close-launcher! nil)")
            (wait-for! #(not (launcher?)) "Palette failed to close")
            (let [data (merge (json/parse-string (slurp (str prefix ".json")) true)
                              (json/parse-string (slurp (str prefix "-runtime.json")) true)
                              {:mapped_ms mapped-ms :cancelled true
                               :ipc_small_roundtrip_ms small-ms :ipc_catalogue_roundtrip_ms large-ms})]
              (swap! rows conj data)
              (spit (str out "/samples.json") (json/generate-string @rows {:pretty true}))
              (println side run "mapped-ms" (format "%.1f" mapped-ms)))))))
    (let [rust (str root "/desktop/build/dusk/.guix-profile/bin/rustc")
          bin (str out "/edn-bench")
          _ (command rust "-O" "--edition=2024" "-Awarnings" "desktop/build/dusk/edn-bench.rs" "-o" bin)
          paths (for [run (range 5) side ["before" "after"]] (str out "/" side "-" run ".edn"))]
      (spit (str out "/rust.tsv") (apply command bin paths)))
    (spit (str out "/summary.json")
          (json/generate-string
           (into {} (for [[side samples] (group-by :side @rows)]
                      [side (into {} (for [k (keys (first samples)) :when (number? (get (first samples) k))]
                                       [k (summary (map k samples))]))]))
           {:pretty true}))
    (spit (str out "/status.json") (json/generate-string {:failures 0 :samples (count @rows)}))
    (println "Results" (str out "/summary.json"))
    (catch Exception e
      (spit (str out "/status.json")
            (json/generate-string {:failures 1 :samples (count @rows) :error (ex-message e)}))
      (throw e))
    (finally
      (try
        (command "dusk" "msg" "eval" "(config/close-launcher! nil)")
        (command "dusk" "msg" "eval" "(swap! dusk.core/request-handlers dissoc :palette-bench)")
        (command "dusk" "msg" "eval" (str "(load-file " (pr-str (str root "/" config)) ")"))
        (catch Exception e (binding [*out* *err*] (println "Restore Dusk:" (ex-message e)))))
      (doseq [[_ server] servers]
        (try (evaluate server "(setq kill-emacs-hook nil)") (evaluate server "(kill-emacs)")
             (catch Exception _ nil)))
      (doseq [child @children] (p/destroy-tree child)))))
