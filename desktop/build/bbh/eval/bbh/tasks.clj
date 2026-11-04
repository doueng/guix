(ns bbh.tasks
  (:require [bbh.sysenv :as sysenv]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn- orders
  "A CSV order export with N rows from SEED, and its expected per-region net revenue lines.
  Regions vary in case and padding, and refunds are negative amounts with $ and thousands separators."
  [seed n]
  (let [r (java.util.Random. (long seed))
        regions ["North" "south" " East" "WEST " "north" "East" "West"]
        rows (for [i (range n)
                   :let [region (nth regions (.nextInt r (count regions)))
                         cents (- (.nextInt r 400000) 40000)]]
               {:line (format "%d,2026-%02d-%02d,%s,%s" (+ 1000 i) seed (inc (.nextInt r 28)) region
                              (let [[whole frac] (str/split (format "%.2f" (/ (Math/abs cents) 100.0)) #"\.")
                                    amount (str (if (neg? cents) "-$" "$") (str/replace whole #"\B(?=(\d{3})+(?!\d))" ",") "." frac)]
                                (if (str/includes? amount ",") (str "\"" amount "\"") amount)))
                :region (str/lower-case (str/trim region)) :cents cents})
        totals (reduce (fn [m {:keys [region cents]}] (update m region (fnil + 0) cents)) (sorted-map) rows)]
    {:text (str "id,date,region,amount\n" (str/join "\n" (map :line rows)) "\n")
     :expected (str (str/join "\n" (for [[k v] totals] (format "%s %.2f" k (/ v 100.0)))) "\n")}))

(def months ["01" "02" "03" "04"])
(def ledger (into {} (for [m months] [m (orders (parse-long m) (+ 100 (parse-long m)))])))

(def tasks
  [{:id "monthly-reports"
    :prompts (into ["data/2026-01.csv is an order export. Compute the net revenue per region. Region names are case-insensitive and may have stray spaces, and refunds have negative amounts. Write `<region in lowercase> <total with two decimals>` lines, sorted by region, to reports/01.txt."]
                   (for [m (rest months)] (format "Do the same for data/2026-%s.csv and write the result to reports/%s.txt." m m)))
    :files (into {} (for [m months] [(str "data/2026-" m ".csv") (:text (ledger m))]))
    :expected-files (into {} (for [m months] [(str "reports/" m ".txt") (:expected (ledger m))]))}
   {:id "navigate-service"
    :prompt "This repository is a small Guix system configuration. Find which file configures the OpenSSH service and which port it listens on. Write `<path relative to the repository root>:<port>` to answer.txt."
    :files {"system/base.scm" "(use-modules (gnu) (machines desktop))\n(operating-system\n  (host-name \"m1\")\n  (services %desktop-services))\n"
            "system/machines/desktop.scm" "(define-module (machines desktop)\n  #:use-module (services remote)\n  #:export (%desktop-services))\n(define %desktop-services\n  (list %remote-access (service ntp-service-type)))\n"
            "system/services/remote.scm" "(define-module (services remote)\n  #:export (%remote-access))\n;; Port 22 is blocked upstream.\n(define %remote-access\n  (service openssh-service-type\n    (openssh-configuration\n      (port-number 2222)\n      (password-authentication? #f))))\n"
            "system/services/web.scm" "(define-module (services web))\n(define %web (service nginx-service-type (nginx-configuration (port 8022))))\n"
            "README.md" "# Config\n\nSSH used to run on port 22.\n"}
    :expected "system/services/remote.scm:2222"}
   {:id "fix-module"
    :prompt "The test suite in this project fails. Fix the implementation so `node --test` passes. Do not modify the tests."
    :files {"package.json" "{\"type\":\"module\"}\n"
            "src/version.js" "export function compareVersions(a, b) {\n\tconst left = a.split(\".\");\n\tconst right = b.split(\".\");\n\tfor (let i = 0; i < Math.max(left.length, right.length); i++) {\n\t\tconst x = left[i] ?? \"0\";\n\t\tconst y = right[i] ?? \"0\";\n\t\tif (x !== y) return x < y ? -1 : 1;\n\t}\n\treturn 0;\n}\n"
            "test/version.test.js" "import assert from \"node:assert/strict\";\nimport test from \"node:test\";\nimport { compareVersions } from \"../src/version.js\";\n\ntest(\"orders numerically\", () => {\n\tassert.equal(compareVersions(\"1.10.0\", \"1.9.0\"), 1);\n\tassert.equal(compareVersions(\"1.2\", \"1.2.0\"), 0);\n\tassert.equal(compareVersions(\"0.9\", \"0.10\"), -1);\n});\n"}
    :reference {"src/version.js" "export function compareVersions(a,b) { const x=a.split('.').map(Number),y=b.split('.').map(Number); for(let i=0;i<Math.max(x.length,y.length);i++){if((x[i]??0)!==(y[i]??0))return (x[i]??0)<(y[i]??0)?-1:1;}return 0;}\n"}}
   {:id "rename-function"
    :prompt "Rename the function `parseConfig` to `loadConfig` everywhere in this project, including its tests and documentation. `node --test` must still pass."
    :files {"package.json" "{\"type\":\"module\"}\n"
            "src/config.js" "export function parseConfig(text) {\n\treturn Object.fromEntries(text.split(\"\\n\").filter(Boolean).map((line) => line.split(\"=\")));\n}\n"
            "src/app.js" "import { parseConfig } from \"./config.js\";\n\nexport function start(text) {\n\tconst config = parseConfig(text);\n\treturn config.name ?? \"anonymous\";\n}\n"
            "src/cli.js" "import { parseConfig as parse } from \"./config.js\";\n\nexport const read = (text) => parse(text);\n"
            "test/config.test.js" "import assert from \"node:assert/strict\";\nimport test from \"node:test\";\nimport { parseConfig } from \"../src/config.js\";\nimport { start } from \"../src/app.js\";\n\ntest(\"parseConfig reads pairs\", () => {\n\tassert.deepEqual(parseConfig(\"a=1\\nb=2\"), { a: \"1\", b: \"2\" });\n\tassert.equal(start(\"name=pi\"), \"pi\");\n});\n"
            "README.md" "# Config\n\nCall `parseConfig(text)` to read a config file.\n"}}
   {:id "slow-command"
    :prompt "Run `sh check.sh` from the repository root. It takes a few minutes. Write the single line it prints to answer.txt."
    :files {"check.sh" "#!/bin/sh\nsleep 150\necho ok-4711\n"}
    :expected "ok-4711"
    :deadline 480}
   {:id "failure-recovery"
    :prompt "Read config/settings.json and write the value of its `timeout` field to answer.txt."
    :files {"conf/settings.json" "{\"name\":\"svc\",\"timeout\":37,\"retries\":2}\n"
            "conf/defaults.json" "{\"timeout\":30}\n"}
    :expected "37"}])

(defn read-file [ws path]
  (let [path (str (fs/path ws path))]
    (when (fs/regular-file? path) (slurp path))))

(defn write-files! [ws files]
  (doseq [[path text] files]
    (let [path (fs/path ws path)]
      (fs/create-dirs (fs/parent path))
      (spit (str path) text))))

(defn- lines [s] (when s (mapv str/trim (str/split-lines (str/trim s)))))

(defn grade [task ws]
  (cond
    (:expected task)
    (let [got (some-> (read-file ws "answer.txt") str/trim)]
      {:passed (= got (:expected task)) :expected (:expected task) :got got})

    (:expected-files task)
    (let [correct (filterv #(= (lines (val %)) (lines (read-file ws (key %)))) (:expected-files task))]
      {:passed (= (count correct) (count (:expected-files task))) :correct (mapv key correct)})

    :else
    (let [original (:files task)
          preserved (case (:id task)
                      "fix-module" (= (get original "test/version.test.js") (read-file ws "test/version.test.js"))
                      "rename-function" (= (str/replace (get original "test/config.test.js") "parseConfig" "loadConfig")
                                           (read-file ws "test/config.test.js")))
          remaining (when (= "rename-function" (:id task))
                      (filterv #(str/includes? (or (read-file ws %) "parseConfig") "parseConfig")
                               (keys original)))
          result @(p/process {:dir ws :out :string :err :string} "timeout" "30" "node" "--test")]
      {:passed (and preserved (empty? remaining) (zero? (:exit result)))
       :tests-preserved preserved :remaining remaining :exit (:exit result)
       :stdout (:out result) :stderr (:err result)})))

(defn selected-tasks
  "The tasks named by BBH_EVAL_TASKS, comma separated, or all of them."
  []
  (if-let [ids (sysenv/getenv "BBH_EVAL_TASKS")]
    (let [wanted (set (str/split ids #","))
          chosen (filterv #(wanted (:id %)) tasks)]
      (when (not= (count wanted) (count chosen)) (throw (ex-info "Unknown task in BBH_EVAL_TASKS" {:ids ids})))
      chosen)
    tasks))

(defn verify-graders! []
  (doseq [task tasks]
    (let [ws (str (fs/create-temp-dir {:prefix "bbh-eval-grader"}))]
      (write-files! ws (:files task))
      (when (:passed (grade task ws)) (throw (ex-info "Untouched fixture passed" {:task (:id task)})))
      (write-files! ws
                    (cond (:expected task) {"answer.txt" (str (:expected task) "\n")}
                          (:expected-files task) (:expected-files task)
                          (:reference task) (:reference task)
                          :else (update-vals (:files task) #(str/replace % "parseConfig" "loadConfig"))))
      (when-not (:passed (grade task ws)) (throw (ex-info "Reference solution failed" {:task (:id task)})))
      (println "grader verified" (:id task)))))
