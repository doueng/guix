(ns pi.prepare
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def patches
  [["(let [ret (->> body-exprs\n                 (map #(transform-async-body ctx locals %))\n                 ensure-promise-result)]"
    "(let [ret (ensure-promise-result (list (transform-do ctx locals body-exprs)))]"]
   ["(defn transform-async-body\n"
    "(defn transform-async-body*\n"]
   ["(defn- ensure-promise-result"
    "(defn transform-async-body [ctx locals body]\n  (let [result (transform-async-body* ctx locals body)]\n    (if (and (seq? result) (meta body))\n      (vary-meta result merge (meta body))\n      result)))\n\n(defn- ensure-promise-result"]])

(defn -main [& _]
  (let [source (slurp (io/resource "sci/impl/async_macro.cljc"))
        patched (reduce (fn [source [before after]]
                          (when-not (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote before)) source)))
                            (throw (ex-info "Pinned SCI patch no longer matches" {:before before})))
                          (str/replace source before after))
                        source patches)
        output (io/file "dist/overrides/sci/impl/async_macro.cljc")]
    (io/make-parents output)
    (spit output patched)))
