#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]] '[org.httpkit.client :as http])

(def script (str (fs/canonicalize (fs/path (fs/parent *file*) "../../bin/view"))))
(def port (+ 20000 (rand-int 20000)))
(def origin (str "http://127.0.0.1:" port))

(defn run [env & args]
  (apply p/shell {:out :string :err :string :continue true :extra-env (merge {"VIEW_PORT" (str port)} env)}
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
          {:keys [out exit]} (run {} "--no-open" (str (fs/path dir "README.md")))]
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
      (run {} "--no-open" (str src))
      (let [before (:body (GET stamp-url))]
        (Thread/sleep 20)
        (spit (str src) "# Two\n")
        (is (not= before (:body (GET stamp-url))))
        (is (str/includes? (:body (GET (view src))) "Two"))))))

(deftest refuses-other-files-and-foreign-hosts
  (fs/with-temp-dir [d {}]
    (let [dir (fixture d)]
      (run {} "--no-open" (str (fs/path dir "README.md")))
      (is (= 404 (:status (GET (view (fs/path dir "pic.png"))))))
      (is (= 404 (:status (GET (view "/etc/passwd")))))
      (is (= 403 (:status (GET (view (fs/path dir "README.md")) {"Host" (str "evil.example:" port)})))))))

(deftest term-prints-plain-text-when-piped
  (fs/with-temp-dir [d {}]
    (let [{:keys [out exit]} (run {} "-t" (str (fs/path (fixture d) "sub" "notes.org")))]
      (is (zero? exit))
      (is (str/includes? out "Plans"))
      (is (str/includes? out "slanted"))
      (is (not (str/includes? out "<")))
      (is (not (str/includes? out "\u001b[")) "no escape codes in a pipe"))))

(deftest phone-writes-a-doc-the-bbh-docs-list-shows
  (fs/with-temp-dir [d {}]
    (let [dir (fixture d)
          {:keys [out exit]} (run {"VIEW_PHONE_DIR" (str dir)} "--phone" (str (fs/path dir "README.md")))
          html (slurp (str/trim out))]
      (is (zero? exit))
      (is (str/starts-with? (str/trim out) (str dir "/view-README-")))
      (is (str/includes? html "<meta name=\"bbh-doc\""))
      (is (str/includes? html "src=\"data:image/png;base64,"))
      (is (not (str/includes? html "<script>")) "a snapshot, no poller"))))

(defn html-fixture [dir]
  (let [dir (fixture dir)]
    (spit (str (fs/path dir "doc.html"))
          (str "<!doctype html><html><head><meta charset=utf-8><title>Doc</title>"
               "<style>.own{color:red}</style></head><BODY><h1>Own Doc</h1>"
               "<p>Some <b>bold</b> &amp; plain text.</p><ul><li>one</li><li>two</li></ul>"
               "<pre>(defn f []\n  1)</pre><svg><text>chart</text></svg><script>var hidden=1</script>"
               "<a href=\"README.md\">readme</a> <a href=\"sub/page.htm#x\">page</a> <img src=\"pic.png\"></BODY></html>\n"))
    (spit (str (fs/path dir "sub" "page.htm")) "<p>no head or body</p>")
    dir))

(deftest serves-html-as-it-is-with-live-reload-and-no-pandoc
  (fs/with-temp-dir [d {}]
    (let [dir (html-fixture d)
          src (fs/path dir "doc.html")
          {:keys [out exit]} (run {} "--no-open" (str src))]
      (is (zero? exit))
      (is (= (view src) (str/trim out)))
      (let [html (:body (GET (view src)))]
        (is (str/starts-with? html "<!doctype html><html><head><meta charset=utf-8><title>Doc</title>") "the file itself, not pandoc's rendering")
        (is (str/includes? html "<style>.own{color:red}</style>"))
        (is (not (str/includes? html "prefers-color-scheme")) "no view style")
        (is (re-find #"<script>\(\(\)=>.*</script></BODY></html>" (str/replace html "\n" " ")) "poller before </body>")
        (is (str/includes? html "src=\"data:image/png;base64,"))
        (is (str/includes? html (str "href=\"" (subs (view (fs/path dir "README.md")) (count origin)) "\"")))
        (is (str/includes? html (str "href=\"" (subs (view (fs/path dir "sub" "page.htm")) (count origin)) "#x\""))))
      (let [page (:body (GET (view (fs/path dir "sub" "page.htm"))))]
        (is (str/starts-with? page "<p>no head or body</p>"))
        (is (str/includes? page "<script>") "poller appended without </body>"))
      (let [stamp-url (str origin "/stamp?path=" (java.net.URLEncoder/encode (str src) "UTF-8"))
            before (:body (GET stamp-url))]
        (Thread/sleep 20)
        (spit (str src) "<h1>Changed</h1>")
        (is (not= before (:body (GET stamp-url))))
        (is (str/includes? (:body (GET (view src))) "Changed"))))))

(deftest term-shows-html-as-text-without-pandoc
  (fs/with-temp-dir [d {}]
    (let [{:keys [out exit]} (run {} "-t" (str (fs/path (html-fixture d) "doc.html")))]
      (is (zero? exit))
      (is (str/includes? out "# Own Doc") "view's own text, not pandoc's")
      (is (str/includes? out "Some bold & plain text."))
      (is (str/includes? out "- one\n- two"))
      (is (str/includes? out "(defn f []\n  1)") "pre keeps its lines")
      (is (not (str/includes? out "<")))
      (is (not (str/includes? out "hidden")) "drops scripts")
      (is (not (str/includes? out "chart")) "drops svg"))))

(deftest phone-copies-html-and-keeps-its-own-doc-meta
  (fs/with-temp-dir [d {}]
    (let [dir (html-fixture d)
          own (fs/path dir "own.html")
          _ (spit (str own) "<html><head><meta name=\"bbh-doc\" content=\"T9\"><meta name=\"description\" content=\"Mine\"></head><body>x</body></html>")
          phone #(slurp (str/trim (:out (run {"VIEW_PHONE_DIR" (str dir)} "--phone" (str %)))))
          html (phone (fs/path dir "doc.html"))
          kept (phone own)]
      (is (str/includes? html "<meta name=\"bbh-doc\" content=\"view\"><meta name=\"description\""))
      (is (str/includes? html "<style>.own{color:red}</style>"))
      (is (str/includes? html "src=\"data:image/png;base64,"))
      (is (not (str/includes? html "<script>(()=>")) "a snapshot, no poller")
      (is (= 1 (count (re-seq #"bbh-doc" kept))))
      (is (str/includes? kept "content=\"Mine\""))
      (is (= 1 (count (re-seq #"name=\"description\"" kept)))))))

(deftest replaces-a-server-left-running-from-an-older-view
  (fs/with-temp-dir [d {}]
    (let [stale-port (+ 40000 (rand-int 20000))
          stale-origin (str "http://127.0.0.1:" stale-port)
          ;; The server view 1 left behind: healthy, but no HTML.
          stale (p/process {:out :string :err :string} "bb" "-e"
                           (str "(require '[org.httpkit.server :as s])"
                                "(s/run-server (fn [{:keys [uri]}] (if (= uri \"/health\") {:status 200 :body \"view\"}"
                                " {:status 404 :body \"not a Markdown or Org file\"})) {:ip \"127.0.0.1\" :port " stale-port "})"
                                "@(promise)"))
          src (fs/path (html-fixture d) "doc.html")]
      (try
        (loop [n 0] (when (and (< n 100) (not= 200 (:status (GET (str stale-origin "/health"))))) (Thread/sleep 100) (recur (inc n))))
        (is (= 404 (:status (GET (str stale-origin "/view?path=" (java.net.URLEncoder/encode (str src) "UTF-8"))))))
        (let [{:keys [out exit]} (run {"VIEW_PORT" (str stale-port)} "--no-open" (str src))
              page (GET (str/trim out))]
          (is (zero? exit))
          (is (= 200 (:status page)) "the stale server is gone and a current one serves HTML")
          (is (str/includes? (:body page) "Own Doc")))
        (finally
          (p/destroy-tree stale)
          (some-> (p/shell {:out :string :continue true} "pgrep" "-f" (str script " serve " stale-port))
                  :out str/split-lines
                  (->> (remove str/blank?) (run! #(p/shell {:continue true} "kill" %)))))))))

(deftest rejects-files-it-cannot-render
  (fs/with-temp-dir [d {}]
    (spit (str (fs/path d "x.txt")) "x")
    (is (= 2 (:exit (run {} "--no-open" (str (fs/path d "x.txt"))))))))

(let [{:keys [fail error]} (try (run-tests)
                                (finally (some-> (p/shell {:out :string :continue true} "pgrep" "-f" (str script " serve " port))
                                                 :out str/split-lines
                                                 (->> (remove str/blank?) (run! #(p/shell {:continue true} "kill" %))))))]
  (System/exit (if (zero? (+ fail error)) 0 1)))
