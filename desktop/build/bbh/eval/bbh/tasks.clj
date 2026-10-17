(ns bbh.tasks
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def tasks
  [{:id "navigate-service"
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

(defn grade [task ws]
  (if-let [expected (:expected task)]
    (let [got (some-> (read-file ws "answer.txt") str/trim)]
      {:passed (= got expected) :expected expected :got got})
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

(defn verify-graders! []
  (doseq [task tasks]
    (let [ws (str (fs/create-temp-dir {:prefix "bbh-eval-grader"}))]
      (write-files! ws (:files task))
      (when (:passed (grade task ws)) (throw (ex-info "Untouched fixture passed" {:task (:id task)})))
      (write-files! ws
                    (cond (:expected task) {"answer.txt" (str (:expected task) "\n")}
                          (:reference task) (:reference task)
                          :else (update-vals (:files task) #(str/replace % "parseConfig" "loadConfig"))))
      (when-not (:passed (grade task ws)) (throw (ex-info "Reference solution failed" {:task (:id task)})))
      (println "grader verified" (:id task)))))
