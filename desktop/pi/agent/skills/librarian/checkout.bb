#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])

(def usage "Usage: checkout.bb <repo> [options]

Ensure a cached checkout exists at:
  ~/.cache/checkouts/<host>/<org>/<repo>

Examples:
  checkout.bb mitsuhiko/minijinja
  checkout.bb github.com/mitsuhiko/minijinja
  checkout.bb https://github.com/mitsuhiko/minijinja
  checkout.bb git@github.com:mitsuhiko/minijinja.git

Options:
  --path-only                 Print only the checkout path.
  --force-update              Always fetch from origin and attempt fast-forward.
  --update-interval <secs>    Minimum seconds between updates (default: 300).

Environment:
  LIBRARIAN_CACHE_ROOT        Override cache root (default: ~/.cache/checkouts)
  LIBRARIAN_DEFAULT_HOST      Host for owner/repo shorthand (default: github.com)
  LIBRARIAN_UPDATE_INTERVAL   Default update interval in seconds")

(defn die [code msg] (binding [*out* *err*] (println msg)) (System/exit code))

(defn parse-args [args]
  (loop [opts {:interval (or (System/getenv "LIBRARIAN_UPDATE_INTERVAL") "300")} [a & more :as args] args]
    (cond
      (empty? args) opts
      (= a "--path-only") (recur (assoc opts :path-only true) more)
      (= a "--force-update") (recur (assoc opts :force true) more)
      (= a "--update-interval") (if (seq more)
                                    (recur (assoc opts :interval (first more)) (rest more))
                                    (die 2 "error: --update-interval expects a value"))
      (#{"-h" "--help"} a) (do (println usage) (System/exit 0))
      (:repo opts) (die 2 (str "error: unexpected argument: " a))
      :else (recur (assoc opts :repo a) more))))

(def deep-link-segments #{"tree" "blob" "pull" "issues" "commit" "actions" "releases" "compare" "wiki"})

(defn parse-repo
  "[host org repo] for a repo reference such as owner/repo, a URL or git@host:path."
  [input]
  (let [input (-> input str/trim (str/replace #"[?#].*" ""))
        [host path] (condp re-matches input
                      #"git@([^:]+):(.*)" :>> rest
                      #"ssh://([^/]+)/(.*)" :>> rest
                      #"https?://([^/]+)/(.*)" :>> rest
                      #"([^/]+)/(.*)" :>> (fn [[_ first more]]
                                             (if (or (str/includes? first ".") (= first "localhost"))
                                               [first more]
                                               [(or (System/getenv "LIBRARIAN_DEFAULT_HOST") "github.com") input]))
                      (die 1 (str "error: unsupported repository format: " input)))
        host (str/replace host #"^.*@" "")
        parts (remove str/blank? (str/split path #"/"))
        ;; For GitHub-like deep links, use owner/repo only.
        parts (if (deep-link-segments (nth parts 2 nil)) (take 2 parts) parts)
        parts (concat (butlast parts) [(str/replace (or (last parts) "") #"\.git$" "")])]
    (when (< (count parts) 2)
      (die 1 (str "error: repository path must contain at least org/repo: " (str/join "/" parts))))
    [host (str/join "/" (butlast parts)) (last parts)]))

(defn git [dir & args] (apply p/sh "git" "-C" dir args))
(defn git! [dir & args] (apply p/shell {:out :string} "git" "-C" dir args))
(defn git-out [dir & args] (str/trim (:out (apply git dir args))))

(let [{:keys [repo path-only force interval]} (parse-args *command-line-args*)
      _ (when-not repo (if (empty? *command-line-args*) (die 1 usage) (die 2 "error: repository is required")))
      _ (when-not (re-matches #"[0-9]+" interval) (die 2 "error: update interval must be a non-negative integer"))
      [host org name] (parse-repo repo)
      cache-root (or (System/getenv "LIBRARIAN_CACHE_ROOT") (str (fs/home) "/.cache/checkouts"))
      checkout (str cache-root "/" host "/" org "/" name)
      origin (str "https://" host "/" org "/" name ".git")
      _ (fs/create-dirs (fs/parent checkout))
      clone-state (if (fs/directory? (str checkout "/.git"))
                    "existing"
                    (do (p/shell {:out :string} "git" "clone" "--filter=blob:none" origin checkout) "cloned"))
      _ (when-not (fs/directory? (str checkout "/.git"))
          (die 3 (str "error: checkout path is not a git repository: " checkout)))
      ;; Normalize origin to the canonical HTTPS URL, adding it when missing.
      current (git checkout "remote" "get-url" "origin")
      _ (cond (not (zero? (:exit current))) (git! checkout "remote" "add" "origin" origin)
              (not= origin (str/trim (:out current))) (git! checkout "remote" "set-url" "origin" origin))
      stamp (str checkout "/.git/librarian-last-fetch")
      now (quot (System/currentTimeMillis) 1000)
      last-fetch (some-> (when (fs/exists? stamp) (re-matches #"[0-9]+" (str/trim (slurp stamp)))) parse-long)
      update? (or force (nil? last-fetch) (>= (- now last-fetch) (parse-long interval)))
      [update-state ff-state]
      (if-not update?
        ["skipped" "not-attempted"]
        (do (git! checkout "fetch" "--prune" "--tags" "origin")
            (spit stamp (str now "\n"))
            (let [branch (git-out checkout "symbolic-ref" "--short" "-q" "HEAD")
                  u (git checkout "rev-parse" "--abbrev-ref" "--symbolic-full-name" "@{u}")
                  upstream (if (zero? (:exit u)) (str/trim (:out u)) "")
                  dirty (git-out checkout "status" "--porcelain" "--untracked-files=no")]
              ["fetched"
               (cond (and (seq branch) (seq upstream) (empty? dirty))
                     (if (zero? (:exit (git checkout "merge" "--ff-only" upstream))) "fast-forwarded" "skipped-non-ff")
                     (seq dirty) "skipped-dirty"
                     :else "skipped-no-upstream")])))]
  (if path-only
    (println checkout)
    (println (str "repo: " host "/" org "/" name "\npath: " checkout "\nstate: " clone-state
                  "\nupdate: " update-state "\nfast_forward: " ff-state))))
