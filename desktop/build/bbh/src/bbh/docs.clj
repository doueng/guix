(ns bbh.docs
  "The HTML explainers the phone can read: .html files that carry
  <meta name=\"bbh-doc\" content=\"T12\"> under the doc roots. A root is a
  directory, searched for **.html, or [directory glob]. Only a file this finds
  is ever served, so the roots are the allowlist."
  (:require [babashka.fs :as fs]
            [bbh.super.client :as client]
            [bbh.sysenv :as sysenv]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- expand [dir]
  (str (fs/expand-home dir)))

(defn default-roots
  "Scratch explainers in /tmp and those Workers leave in wf evidence."
  []
  [["/tmp" "*.html"]
   [(str (fs/path (or (sysenv/getenv "XDG_STATE_HOME") (expand "~/.local/state")) "bbh" "workflows"))
    "*/evidence/**.html"]])

(defn roots
  "The doc roots: policy.edn :docs, else `default-roots`."
  []
  (let [f (client/policy-file)
        docs (when (fs/exists? f) (:docs (try (edn/read-string (slurp (str f))) (catch Exception _ nil))))]
    (or docs (default-roots))))

(defn- text
  "HTML fragment S as one line of plain text."
  [s]
  (when s
    (let [t (-> s
                (str/replace #"<[^>]*>" " ")
                (str/replace #"&#(\d+);" #(str (char (parse-long (second %)))))
                (str/replace #"&(amp|lt|gt|quot|apos|middot|nbsp|mdash|ndash|rarr);"
                             #(case (second %) "amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'"
                                    "middot" "·" "nbsp" " " "mdash" "—" "ndash" "–" "rarr" "→"))
                (str/replace #"\s+" " ")
                str/trim)]
      (when-not (str/blank? t) t))))

(defn- metas
  "Each <meta name=... content=...> in HTML, as name to content."
  [html]
  (into {} (for [tag (re-seq #"(?is)<meta\s[^>]*>" html)
                 :let [attr (fn [k] (some->> (re-find (re-pattern (str "(?is)\\s" k "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")) tag) rest (some identity)))
                       n (attr "name")]
                 :when n]
             [(str/lower-case n) (str (attr "content"))])))

(defn read-doc
  "The listing entry for the HTML text of file PATH, or nil when it is not
  tagged as a doc."
  [path html]
  (let [m (metas html)]
    (when-let [tag (m "bbh-doc")]
      (let [title (or (text (second (re-find #"(?is)<title[^>]*>(.*?)</title>" html)))
                      (str (fs/file-name path)))
            heading (some #(let [t (text %)] (when (and t (not= t title)) t))
                          (map second (re-seq #"(?is)<h[12][^>]*>(.*?)</h[12]>" html)))]
        {:path path
         :title title
         :description (or (text (m "description")) heading "")
         :thread (re-find #"T\d+" tag)
         :temporary (str/starts-with? path "/tmp/")}))))

(def ^:private cache
  "Each file's entry by path, current while its modified time is."
  (atom {}))

(defn- entry [path]
  (let [modified (fs/file-time->millis (fs/last-modified-time path))
        held (get @cache path)]
    (if (= modified (:modified held))
      (:doc held)
      (let [doc (some-> (try (read-doc path (slurp path)) (catch Exception _ nil))
                        (assoc :modified (str (java.time.Instant/ofEpochMilli modified))))]
        (swap! cache assoc path {:modified modified :doc doc})
        doc))))

(defn- under? [dir path]
  (str/starts-with? path (str dir "/")))

(defn listing
  "The docs under ROOTS, newest first. A path is the file's real path, so a
  link out of its root is left out."
  [roots]
  (let [found (for [root roots
                    :let [[dir glob] (if (string? root) [root "**.html"] root)
                          dir (expand dir)]
                    :when (fs/directory? dir)
                    :let [real-dir (str (fs/real-path dir))]
                    f (fs/glob dir glob)
                    :let [path (str (fs/real-path f))]
                    :when (and (str/ends-with? path ".html") (fs/regular-file? path) (under? real-dir path))]
                path)]
    (->> (distinct found)
         (keep entry)
         (sort-by :modified #(compare %2 %1))
         vec)))

(defn find-doc
  "The doc at exactly PATH among the docs under ROOTS, or nil."
  [roots path]
  (some #(when (= path (:path %)) %) (listing roots)))
