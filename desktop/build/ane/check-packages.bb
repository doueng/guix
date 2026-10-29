#!/usr/bin/env bb
;; Build the ANE module and DTB packages for asahi-linux-keyd and check that the
;; module matches the kernel and only the T8103 trees gain the ANE nodes.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(defn out [& args]
  (str/trim (:out (apply shell {:out :string :err :inherit} args))))

(defn build [sym]
  (last (str/split-lines
         (out "guix" "build" "-L" "modules" "-e"
              (str "(@ (engstrand system bootloader) " sym ")")))))

(def failures (atom []))
(defn check! [ok? msg]
  (println (if ok? "ok  " "FAIL") msg)
  (when-not ok? (swap! failures conj msg)))

(def kernel (build "asahi-linux-keyd"))
(def module (build "asahi-linux-keyd-ane"))
(def dtbs (build "asahi-dtbs-ane"))
(def dtc-bin (str (last (str/split-lines (out "guix" "build" "dtc"))) "/bin/"))

(def kernel-release (str (fs/file-name (first (fs/list-dir (str kernel "/lib/modules"))))))
(def ko (str (first (fs/glob module "lib/modules/*/updates/ane.ko*"))))
(check! (str/starts-with? (out "modinfo" "-F" "vermagic" ko) (str kernel-release " "))
        (str "ane.ko vermagic matches kernel " kernel-release))
(check! (str/includes? (out "modinfo" "-F" "alias" ko) "of:N*T*Capple,t8103-ane")
        "ane.ko binds apple,t8103-ane")

(def board "apple/t8103-j313.dtb")
(check! (= "apple,t8103-ane"
           (out (str dtc-bin "fdtget") (str dtbs "/lib/dtbs/" board)
                "/soc/ane@26bc04000" "compatible"))
        "j313 tree has the ANE node")

(let [rels (map #(str (fs/relativize (str kernel "/lib/dtbs") %))
                (fs/glob (str kernel "/lib/dtbs") "**.dtb"))
      changed (remove #(= (slurp (fs/file kernel "lib/dtbs" %) :encoding "ISO-8859-1")
                          (slurp (fs/file dtbs "lib/dtbs" %) :encoding "ISO-8859-1"))
                      rels)]
  (check! (and (seq rels) (every? #(re-find #"^apple/t8103-.*\.dtb$" %) changed)
               (= 5 (count changed)))
          (str "only the five T8103 board trees differ: " (vec changed)))
  (check! (= (count rels) (count (fs/glob (str dtbs "/lib/dtbs") "**.dtb")))
          "the DTB package carries every kernel tree"))

(let [dts #(out (str dtc-bin "dtc") "-q" "-I" "dtb" "-O" "dts" (str % "/lib/dtbs/" board))
      before (set (str/split-lines (dts kernel)))
      after (set (str/split-lines (dts dtbs)))
      removed (remove after before)]
  (check! (every? #(re-find #"(interrupt-controller@23b100000|power-controller@(470|c000)) \{" %) removed)
          (str "j313 diff drops only label-less headers: " (count removed) " lines")))

(when (seq @failures)
  (println (count @failures) "check(s) failed")
  (System/exit 1))
