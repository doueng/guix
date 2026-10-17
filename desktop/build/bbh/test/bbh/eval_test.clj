(ns bbh.eval-test
  (:require [babashka.fs :as fs]
            [bbh.compare :as compare]
            [clojure.test :refer [deftest is]]))

(deftest fingerprints-cover-root-level-and-nested-sci-sources
  (let [desktop (str (fs/create-temp-dir {:prefix "bbh-fingerprint-test"}))
        root (str (fs/path desktop "build/bbh"))
        direct (str (fs/path desktop "build/pi-sci/src/extension.ts"))
        nested (str (fs/path desktop "build/pi-sci/src/nested/helper.ts"))
        empty-sha "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"]
    (doseq [path [direct nested]]
      (fs/create-dirs (fs/parent path))
      (spit path ""))
    (with-redefs [compare/desktop desktop compare/root root]
      (is (= {direct empty-sha nested empty-sha} (compare/fingerprint))))))
