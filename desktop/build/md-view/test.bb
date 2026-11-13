#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]])

(def script (str (fs/canonicalize (fs/path (fs/parent *file*) "../../bin/md-view"))))

(defn render [dir file]
  (let [out (str (fs/path dir "out"))
        {:keys [out exit]} (p/shell {:out :string :extra-env {"MD_VIEW_DIR" out}}
                                    script "--no-open" (str file))]
    (is (zero? exit))
    (str/trim out)))

(deftest renders-gfm-and-resolves-relative-paths
  (fs/with-temp-dir [dir {}]
    (fs/create-dirs (fs/path dir "sub"))
    (fs/write-bytes (fs/path dir "pic.png") (.getBytes "png"))
    (spit (str (fs/path dir "README.md"))
          (str "# Doc\n\n[other](sub/other.md#part) [script](run.sh) [web](https://example.com)\n\n"
               "| a | b |\n|---|---|\n| 1 | 2 |\n\n- [x] done\n- [ ] todo\n\n"
               "```clojure\n(defn f [] \"s\")\n```\n\n![pic](pic.png)\n"))
    (spit (str (fs/path dir "sub" "other.md")) "# Other\n\n[back](../README.md)\n")
    (let [page (render dir (fs/path dir "README.md"))
          html (slurp page)
          [_ other] (re-find #"href=\"([0-9a-f]{40}\.html)#part\"" html)]
      (is (str/includes? html "<table>"))
      (is (str/includes? html "type=\"checkbox\" checked"))
      (is (str/includes? html "class=\"sourceCode clojure\""))
      (is (str/includes? html "src=\"data:image/png;base64,"))
      (is (str/includes? html (str "href=\"" (.toUri (fs/path dir "run.sh")) "\"")))
      (is (str/includes? html "href=\"https://example.com\""))
      (is (str/includes? html "prefers-color-scheme:dark"))
      (is other "a linked .md points at its rendered page")
      (when other
        (is (str/includes? (slurp (str (fs/path (fs/parent page) other)))
                           (str "href=\"" (fs/file-name page) "\"")))))))

(deftest reopening-skips-pandoc-until-the-source-changes
  (fs/with-temp-dir [dir {}]
    (let [src (fs/path dir "a.md")]
      (spit (str src) "# One\n")
      (let [page (render dir src)
            t0 (fs/last-modified-time page)]
        (Thread/sleep 20)
        (render dir src)
        (is (= t0 (fs/last-modified-time page)))
        (Thread/sleep 20)
        (spit (str src) "# Two\n")
        (render dir src)
        (is (str/includes? (slurp page) "Two"))))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
