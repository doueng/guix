(ns bbh.landing-property-test
  "Random interleavings of Workers committing in their jj workspaces, the
  person committing and editing in theirs, and Workers landing, run on a
  scratch repository through the controller's own land!, advance! and retire!.
  Landing must keep history free of merges, keep everything landed before on
  the line unrewritten, land every change a Worker committed under its thread
  prefix, and lose none of the person's work (T56, T57, T131)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.hermetic :as hermetic]
            [bbh.props :as props]
            [bbh.super.workspace :as workspace]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(use-fixtures :once hermetic/fixture)

(defn- jj [dir & args]
  (when-not (str/starts-with? (str dir) (System/getProperty "java.io.tmpdir"))
    (throw (ex-info (str "jj outside the temporary directory: " dir) {})))
  (let [{:keys [exit out err]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true} "jj" args)]
    (when-not (zero? exit) (throw (ex-info (str "jj " (str/join " " args) ": " err) {:jj args})))
    (str/trim out)))

(defn- lines [dir revset template]
  (remove str/blank? (str/split-lines (jj dir "log" "--no-graph" "--ignore-working-copy" "-r" revset "-T" (str template " ++ \"\\n\"")))))

(defn- file-at [dir rev file]
  (try (jj dir "file" "show" "--ignore-working-copy" "-r" rev file) (catch clojure.lang.ExceptionInfo _ nil)))

(defn- write! [dir file text] (spit (str (fs/path dir file)) (str text "\n")))

;; ---- the world ---------------------------------------------------------------

(defn- start [root]
  (let [repo (str (fs/create-dirs (fs/path root "repo")))]
    (write! repo "README" "hello")
    (jj repo "git" "init")
    (jj repo "commit" "-m" "start")
    {:root root :repo repo :next 1 :workers {} :landed [] :person {}}))

(defn- active [world] (vec (sort (keep (fn [[i w]] (when-not (:landed? w) i)) (:workers world)))))

(defn- pick [coll n] (when (seq coll) (nth coll (mod n (count coll)))))

(defn- problem [world & what] (update world :problems (fnil conj []) (vec what)))

(defn- resolve-conflicts!
  "Settle each conflicted commit of MINE, oldest first, as a Worker might:
  edit it and write the file whole, then start a new change on what MINE
  now reaches, which can leave the rest of its stack above the working copy."
  [ws mine]
  (loop [n 0]
    (when-let [c (last (lines ws (str "(" mine ") & conflicts()") "change_id"))]
      (when (> n 20) (throw (ex-info "conflicts do not settle" {})))
      (jj ws "edit" c)
      (write! ws "shared.txt" (str "resolved " c))
      (jj ws "status")
      (recur (inc n))))
  (jj ws "new" (str "heads(" mine ")")))

(defn- check-landing
  "What breaks the landing rules after Worker W landed at TIP, given WORLD before."
  [world w tip before-line]
  (let [repo (:repo world)
        line (set (lines repo (str "::" tip) "commit_id"))
        mine (lines repo (str "::" tip " ~ ::" (str/join " ~ ::" (or (seq (:landed world)) ["root()"]))) "description.first_line()")]
    (cond-> world
      (seq (lines repo "merges()" "commit_id")) (problem :merge-in-history)
      (seq (lines repo "description(regex:\"^.T[0-9]+. person\")" "commit_id")) (problem :person-commit-taken-as-a-workers)
      (seq (lines repo (str "::" tip " & conflicts()") "commit_id")) (problem :conflict-on-the-line)
      (not-every? line before-line) (problem :landed-history-rewritten)
      (not-every? #(seq (lines repo (str % " & ::" tip) "change_id")) (:landed world)) (problem :earlier-landing-left-the-line)
      ;; jj trims the space after a prefix on an empty description.
      (not-every? #(str/starts-with? % (str/trim (workspace/thread-prefix (:thread w)))) (remove #{"start" "person"} mine))
      (problem :unprefixed (vec mine))
      true (as-> wd (reduce (fn [wd [file text]] (if (= text (file-at repo tip file)) wd (problem wd :change-lost (:thread w) file)))
                            wd (:files w))))))

(defn- land
  "Land Worker I as the controller does on a done finish: land!, then
  advance! the person's working copy (MOVE as policy says), then retire!."
  [world i move]
  (let [{:keys [ws thread dirty?] :as w} (get-in world [:workers i])
        repo (:repo world)
        landed (:landed world)
        before-line (if (seq landed) (set (lines repo (str "::(" (str/join "|" landed) ")") "commit_id")) #{})
        onto (atom nil)
        attempt (fn [opts] (reset! onto (:onto opts))
                  (try (workspace/land! (merge {:workspace ws :landed landed :thread thread} opts))
                       (catch clojure.lang.ExceptionInfo e {:refused (:error (ex-data e))})))
        r (attempt {})
        world (cond-> world
                (and dirty? (not= :in-progress (:refused r))) (problem :dirty-copy-landed thread r)
                (and (not dirty?) (= :in-progress (:refused r))) (problem :clean-copy-refused thread))
        r (if (= :in-progress (:refused r)) (do (jj ws "commit" "-m" "last") (attempt {})) r)
        heads (lines repo (str "heads(::default@- | ::(" (str/join "|" (cons "root()" landed)) "))") "commit_id")
        world (cond-> world
                (and (= :unclear-tip (:refused r)) (= 1 (count heads))) (problem :one-tip-called-unclear)
                (and (not= :unclear-tip (:refused r)) (> (count heads) 1)) (problem :two-tips-not-refused r))
        r (if (= :unclear-tip (:refused r))
            (attempt {:onto (first (lines repo (str "latest(" (last landed) ")") "commit_id"))})
            r)
        ;; Settling a conflict by editing the conflicted commit leaves the
        ;; working copy below the rest of the stack, which must be refused
        ;; rather than left behind; the Worker then moves on top and lands.
        r (loop [r r n 0]
            (cond (> n 8) r
                  (:conflicts r) (do (resolve-conflicts! ws (str (:onto r) ".." thread "-1@ ~ ::default@-"))
                                     (recur (attempt {:onto (:onto r)}) (inc n)))
                  (= :off-stack (:refused r)) (let [o @onto]
                                                (jj ws "new" (str "latest(heads(roots(" (:onto r o) "..@ ~ ::default@-)::) ~ (empty() & description(exact:\"\")))"))
                                                (recur (attempt {:onto o}) (inc n)))
                  :else r))]
    (cond
      (:refused r) (problem world :refused thread r)
      (:conflicts r) (problem world :conflicts-after-resolving thread r)
      (nil? (:landed r)) (do (workspace/retire! {:repo repo :workspace ws}) (assoc-in world [:workers i :landed?] true))
      :else
      (let [tip (first (lines repo (:landed r) "commit_id"))
            ;; The shared file is settled by hand, so only each Worker's own files are compared.
            world (check-landing world (update w :files #(dissoc % "shared.txt")) tip before-line)
            moved (workspace/advance! {:repo repo :tip tip :move move})]
        (workspace/retire! {:repo repo :workspace ws})
        (cond-> (-> world
                    (assoc-in [:workers i :landed?] true)
                    (update :landed conj (:landed r)))
          (and (map? moved) (= :failed (:kept moved))) (problem :advance-failed moved))))))

(defn- step [world [kind a b]]
  (let [repo (:repo world)
        i (pick (active world) a)
        w (get-in world [:workers i])]
    (case kind
      :start (if (>= (count (active world)) 3)
               world
               (let [n (:next world)
                     ws (workspace/workspace! (:root world) {:repo repo :thread (str "T" n) :id (str "W" n) :title "t" :generation 1})]
                 (-> world (assoc-in [:workers n] {:ws ws :thread (str "T" n) :files {}}) (update :next inc))))
      :commit (if-not w
                world
                (let [shared? (zero? (mod b 4))
                      file (if shared? "shared.txt" (str (:thread w) "-" (mod b 3) ".txt"))
                      text (str (:thread w) " " b)]
                  (if shared?
                    (spit (str (fs/path (:ws w) file)) (str text "\n") :append true)
                    (write! (:ws w) file text))
                  (jj (:ws w) "commit" "-m" (if (even? b) (str "change " b) ""))
                  (-> world (assoc-in [:workers i :files file] (str/trim (slurp (str (fs/path (:ws w) file)))))
                      (assoc-in [:workers i :dirty?] false))))
      :edit (if-not w
              world
              (let [file (str (:thread w) "-" (mod b 3) ".txt") text (str (:thread w) " draft " b)]
                (write! (:ws w) file text)
                (-> world (assoc-in [:workers i :files file] text) (assoc-in [:workers i :dirty?] true))))
      :person-commit (let [file (str "p-" (mod b 3) ".txt")]
                       (write! repo file (str "person " b))
                       (jj repo "commit" "-m" "person")
                       (assoc-in world [:person file] (str "person " b)))
      :person-edit (let [file (str "p-" (mod b 3) ".txt")]
                     (write! repo file (str "person draft " b))
                     (assoc-in world [:person file] (str "person draft " b)))
      :land (if-not w world (land world i (if (odd? b) :always nil))))))

(defn- run-world
  "Run OPS on a scratch repository. Returns the problems found, or nil."
  [ops]
  (let [root (str (fs/create-temp-dir {:prefix "bbh-landing-property"}))]
    (try
      (let [world (reduce (fn [world op] (let [w (step world op)] (if (:problems w) (reduced w) w)))
                          (start root) ops)
            repo (:repo world)
            ;; The person's work is never lost: their working copy holds every file as they last wrote it.
            lost (for [[file text] (:person world)
                       :let [f (fs/path repo file)]
                       :when (not= text (when (fs/exists? f) (str/trim (slurp (str f)))))]
                   [:person-work-lost file])]
        (seq (concat (:problems world) lost)))
      (finally (fs/delete-tree root)))))

(def ^:private op
  (gen/tuple (gen/frequency [[2 (gen/return :start)] [5 (gen/return :commit)] [1 (gen/return :edit)]
                             [2 (gen/return :person-commit)] [2 (gen/return :person-edit)] [3 (gen/return :land)]])
             (gen/choose 0 100) (gen/choose 0 100)))

(deftest landing-regressions-the-property-found
  ;; Shrunk failures from T170, each a bug in land! or advance! before it.
  (doseq [[why ops] {;; Settling a conflict with jj edit then jj new left the
                     ;; working copy below the stack's last commit, which then
                     ;; never landed: land! now refuses :off-stack.
                     :stack-left-behind [[:commit 0 0] [:edit 0 0] [:start 0 0] [:commit 46 0] [:land 16 0] [:land 0 0]]
                     ;; The person committed off the line and the landing went
                     ;; :onto the Worker's tip: advance! moved their empty
                     ;; working copy off their own commit.
                     :checkout-left-the-persons-commit [[:commit 0 0] [:person-edit 0 0] [:start 0 0] [:commit 46 0] [:land 0 0] [:person-commit 0 0] [:land 0 0]]
                     ;; With :onto, the person's commits beside the tip were
                     ;; taken as the Worker's, rebased and prefixed.
                     :persons-commits-taken [[:person-edit 0 0] [:commit 3 0] [:land 31 0] [:person-commit 0 0] [:start 0 0] [:person-commit 0 0] [:land 7 0]]}]
    (is (nil? (run-world (concat [[:start 0 0] [:start 0 0]] ops))) (str why))))

(deftest property-landing-random-stacks-keeps-the-line-and-loses-nothing
  (props/check "landing" 4
               (prop/for-all [ops (gen/vector op 4 18)]
                 ;; Every run starts two Workers so short runs land something.
                 (nil? (run-world (concat [[:start 0 0] [:start 0 0]] ops))))
               :explain #(run-world (concat [[:start 0 0] [:start 0 0]] %))
               :max-size 30 :full 30))
