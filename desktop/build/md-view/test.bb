#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]] '[org.httpkit.client :as http])

(def script (str (fs/canonicalize (fs/path (fs/parent *file*) "../../bin/md-view"))))
(def port (+ 20000 (rand-int 20000)))
(def origin (str "http://127.0.0.1:" port))

(defn md-view [env & args]
  (apply p/shell {:out :string :err :string :continue true :extra-env (merge {"MD_VIEW_PORT" (str port)} env)}
         script args))

(defn GET
  ([url] (GET url {}))
  ([url headers] @(http/get url {:headers headers :as :text})))

(defn fixture [dir]
  (fs/create-dirs (fs/path dir "sub"))
  (fs/write-bytes (fs/path dir "pic.png") (.getBytes "png"))
  (spit (str (fs/path dir "README.md"))
        (str "# Doc\n\n[other](sub/other.md#part) [notes](sub/notes.org) [script](run.sh) [web](https://example.com)\n\n"
             "| a | b |\n|---|---|\n| 1 | 2 |\n\n- [x] done\n- [ ] todo\n\n> [!WARNING]\n> Careful\n\n"
             "```clojure\n(defn f [] \"s\")\n```\n\n![pic](pic.png)\n"))
  (spit (str (fs/path dir "sub" "other.md")) "# Other\n\n[back](../README.md)\n")
  (spit (str (fs/path dir "sub" "notes.org")) "#+title: Notes\n\n* Plans\n\nSome /slanted/ words.\n")
  (fs/canonicalize dir))

(defn view [path] (str origin "/view?path=" (java.net.URLEncoder/encode (str path) "UTF-8")))

(deftest serves-gfm-and-org-with-links-that-stay-in-the-viewer
  (fs/with-temp-dir [d {}]
    (let [dir (fixture d)
          {:keys [out exit]} (md-view {} "--no-open" (str (fs/path dir "README.md")))]
      (is (zero? exit))
      (is (= (view (fs/path dir "README.md")) (str/trim out)) "prints the page URL and starts the server")
      (let [html (:body (GET (str/trim out)))]
        (is (str/includes? html "<table>"))
        (is (str/includes? html "<div class=\"warning\">") "GitHub alerts become callouts")
        (is (str/includes? html "type=\"checkbox\" checked"))
        (is (str/includes? html "class=\"sourceCode clojure\""))
        (is (str/includes? html "src=\"data:image/png;base64,"))
        (is (str/includes? html (str "href=\"" (subs (view (fs/path dir "sub" "other.md")) (count origin)) "#part\"")))
        (is (str/includes? html (str "href=\"" (subs (view (fs/path dir "sub" "notes.org")) (count origin)) "\"")))
        (is (str/includes? html (str "href=\"" (.toUri (fs/path dir "run.sh")) "\"")))
        (is (str/includes? html "href=\"https://example.com\""))
        (is (str/includes? html "prefers-color-scheme:dark")))
      (let [org (:body (GET (view (fs/path dir "sub" "notes.org"))))]
        (is (str/includes? org "<h1 class=title>Notes</h1>") "shows the Org title")
        (is (str/includes? org "Plans</h1>"))
        (is (str/includes? org "<em>slanted</em>"))))))

(deftest stamp-changes-when-the-source-is-saved
  (fs/with-temp-dir [d {}]
    (let [src (fs/path (fixture d) "README.md")
          stamp-url (str origin "/stamp?path=" (java.net.URLEncoder/encode (str src) "UTF-8"))]
      (md-view {} "--no-open" (str src))
      (let [before (:body (GET stamp-url))]
        (Thread/sleep 20)
        (spit (str src) "# Two\n")
        (is (not= before (:body (GET stamp-url))))
        (is (str/includes? (:body (GET (view src))) "Two"))))))

(deftest refuses-other-files-and-foreign-hosts
  (fs/with-temp-dir [d {}]
    (let [dir (fixture d)]
      (md-view {} "--no-open" (str (fs/path dir "README.md")))
      (is (= 404 (:status (GET (view (fs/path dir "pic.png"))))))
      (is (= 404 (:status (GET (view "/etc/passwd")))))
      (is (= 403 (:status (GET (view (fs/path dir "README.md")) {"Host" (str "evil.example:" port)})))))))

(deftest term-prints-plain-text-when-piped
  (fs/with-temp-dir [d {}]
    (let [{:keys [out exit]} (md-view {} "-t" (str (fs/path (fixture d) "sub" "notes.org")))]
      (is (zero? exit))
      (is (str/includes? out "Plans"))
      (is (str/includes? out "slanted"))
      (is (not (str/includes? out "<")))
      (is (not (str/includes? out "\u001b[")) "no escape codes in a pipe"))))

(deftest phone-writes-a-doc-the-bbh-docs-list-shows
  (fs/with-temp-dir [d {}]
    (let [dir (fixture d)
          {:keys [out exit]} (md-view {"MD_VIEW_PHONE_DIR" (str dir)} "--phone" (str (fs/path dir "README.md")))
          html (slurp (str/trim out))]
      (is (zero? exit))
      (is (str/starts-with? (str/trim out) (str dir "/md-view-README-")))
      (is (str/includes? html "<meta name=\"bbh-doc\""))
      (is (str/includes? html "src=\"data:image/png;base64,"))
      (is (not (str/includes? html "<script>")) "a snapshot, no poller"))))

(deftest rejects-files-it-cannot-render
  (fs/with-temp-dir [d {}]
    (spit (str (fs/path d "x.txt")) "x")
    (is (= 2 (:exit (md-view {} "--no-open" (str (fs/path d "x.txt"))))))))

(let [{:keys [fail error]} (try (run-tests)
                                (finally (some-> (p/shell {:out :string :continue true} "pgrep" "-f" (str script " serve " port))
                                                 :out str/split-lines
                                                 (->> (remove str/blank?) (run! #(p/shell {:continue true} "kill" %))))))]
  (System/exit (if (zero? (+ fail error)) 0 1)))
