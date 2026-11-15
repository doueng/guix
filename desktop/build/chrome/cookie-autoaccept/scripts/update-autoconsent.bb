#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :refer [shell]] '[cheshire.core :as json] '[clojure.string :as str])

(def version (or (first *command-line-args*) (do (println "usage: scripts/update-autoconsent.bb VERSION") (System/exit 2))))
(def repo (fs/parent (fs/parent (fs/absolutize *file*))))
(def pkg (str "@duckduckgo/autoconsent@" version))
(defn out [dir & cmd] (str/trim (:out (apply shell {:dir (str dir) :out :string} cmd))))
(defn digest [algo file] (.digest (java.security.MessageDigest/getInstance algo) (fs/read-all-bytes file)))
(defn b64 [bs] (.encodeToString (java.util.Base64/getEncoder) bs))
(defn hex [bs] (apply str (map #(format "%02x" %) bs)))

(fs/with-temp-dir [tmp {}]
  (let [want (out tmp "npm view" pkg "dist.integrity")
        tgz (fs/path tmp (out tmp "npm pack --silent" pkg))
        got (str "sha512-" (b64 (digest "SHA-512" tgz)))
        x (fs/path tmp "x")
        d (fs/path repo "vendor/autoconsent")
        files [["content.bundle.js" "dist/addon-mv3/content.bundle.js"] ["autoconsent.esm.js" "dist/autoconsent.esm.js"]
               ["rules.json" "rules/rules.json"] ["LICENSE" "LICENSE"]]]
    (when (not= want got) (binding [*out* *err*] (println "integrity mismatch:" want "vs" got)) (System/exit 1))
    (fs/create-dirs x)
    (shell "tar xzf" (str tgz) "-C" (str x))
    (doseq [[to from] files] (fs/copy (fs/path x "package" from) (fs/path d to) {:replace-existing true}))
    (spit (str (fs/path d "VERSION"))
          (str/join "\n"
                    (concat [(str "@duckduckgo/autoconsent " version " (" (:license (json/parse-string (slurp (str (fs/path x "package/package.json"))) true))
                                  ", see LICENSE in this directory)")
                             (str "source: " (out tmp "npm view" pkg "dist.tarball"))
                             (str "integrity: " got)
                             (str "git: https://github.com/duckduckgo/autoconsent tag v" version)
                             "files, unmodified:"
                             "  content.bundle.js    <- package/dist/addon-mv3/content.bundle.js"
                             "  autoconsent.esm.js   <- package/dist/autoconsent.esm.js (the service worker imports evalSnippets from it)"
                             "  rules.json           <- package/rules/rules.json"
                             "  LICENSE              <- package/LICENSE"
                             "sha256:"]
                            (for [f ["autoconsent.esm.js" "content.bundle.js" "LICENSE" "rules.json"]] (str (hex (digest "SHA-256" (fs/path d f))) "  " f))
                            [""])))
    (println "vendored autoconsent" version "; now run: bb test && bb e2e")))
