#!/usr/bin/env bb
;; Load a built Home's user Shepherd config the way shepherd does at login,
;; without starting anything, and fail if it or any service file throws.
;;
;; T216: a service that read %user-log-dir without importing (shepherd
;; support) made shepherd.conf die with "Unbound variable", so no user
;; service (dbus, pipewire) started and Hyprland exited back to the greeter.
;;
;; Usage: bb desktop/build/home/check-shepherd.bb PATH...
;; PATH is a Home (it has on-first-login) or a system, whose closure is
;; searched for the Home that guix-home-service activates.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def store-re #"/gnu/store/[a-z0-9]{32}-[^\s\"')]+")

(defn home? [dir] (fs/exists? (fs/path dir "on-first-login")))

(defn homes [path]
  (let [path (str (fs/canonicalize path))]
    (if (home? path)
      [path]
      (->> (str/split-lines (:out (p/shell {:out :string} "guix" "gc" "-R" path)))
           (filter #(and (str/ends-with? % "-home") (home? %)))))))

(defn shepherd-parts
  "The shepherd script and config that the Home's on-first-login runs."
  [home]
  (let [refs (re-seq store-re (slurp (str home "/on-first-login")))
        shepherd (first (filter #(str/ends-with? % "/bin/shepherd") refs))
        conf (first (filter #(str/ends-with? % "-shepherd.conf") refs))]
    (when-not (and shepherd conf)
      (throw (ex-info (str home "/on-first-login names no shepherd and config") {})))
    {:shepherd shepherd :conf conf}))

(defn guile-and-paths
  "The guile and the load-path forms of a shepherd launcher script."
  [shepherd]
  (let [text (slurp shepherd)
        guile (second (re-find #"^#!(\S+)" text))
        sets (re-seq #"(?s)\(set! %load(?:-compiled)?-path.*?%load(?:-compiled)?-path\)\)" text)]
    (when-not (and guile (= 2 (count sets)))
      (throw (ex-info (str "cannot read the load paths of " shepherd) {})))
    {:guile guile :paths (str/join "\n" sets)}))

;; Evaluate shepherd.conf in a module where registering, daemonizing and
;; starting only record what they were given.
(defn program [paths conf]
  (str paths "
(use-modules (shepherd service) (ice-9 match))
(define failed #f)
(define registered 0)
(define m (make-fresh-user-module))
(module-use! m (resolve-interface '(shepherd service)))
(module-define! m 'register-services
  (lambda (services)
    (for-each (lambda (s)
                (unless (service? s)
                  (set! failed #t)
                  (format #t \"not a service: ~s~%\" s)))
              services)
    (set! registered (+ registered (length services)))))
(module-define! m 'perform-service-action (lambda args #t))
(module-define! m 'start-in-the-background (lambda args #t))
(module-define! m 'redirect-port (lambda args #t))
(module-define! m 'root-service #f)
(catch #t
  (lambda ()
    (save-module-excursion
     (lambda ()
       (set-current-module m)
       (with-output-to-string (lambda () (load \"" conf "\"))))))
  (lambda (key . args)
    (set! failed #t)
    (format #t \"~a: ~a ~s~%\" \"" conf "\" key args)))
(format #t \"~a services~%\" registered)
(exit (if (or failed (zero? registered)) 1 0))
"))

(defn check-home [home]
  (let [{:keys [shepherd conf]} (shepherd-parts home)
        {:keys [guile paths]} (guile-and-paths shepherd)
        script (str (fs/create-temp-file {:prefix "check-shepherd" :suffix ".scm"}))]
    (spit script (program paths conf))
    (let [r (p/shell {:out :string :err :string :continue true
                      :extra-env {"GUILE_JIT_THRESHOLD" "-1"}}
                     guile "--no-auto-compile" script)]
      (fs/delete script)
      (println (str (if (zero? (:exit r)) "ok   " "FAIL ") conf " (" home "): "
                    (str/trim (:out r))))
      (when-not (zero? (:exit r))
        (binding [*out* *err*] (print (:err r)) (flush)))
      (zero? (:exit r)))))

(defn -main [& paths]
  (when (empty? paths)
    (binding [*out* *err*] (println "usage: check-shepherd.bb HOME-OR-SYSTEM..."))
    (System/exit 2))
  (let [hs (mapcat (fn [p]
                     (let [hs (homes p)]
                       (when (empty? hs)
                         (binding [*out* *err*] (println "no Home in" p))
                         (System/exit 1))
                       hs))
                   paths)]
    (System/exit (if (every? true? (doall (map check-home (distinct hs)))) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
