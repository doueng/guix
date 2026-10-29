#!/usr/bin/env bb
;; Build the system and its bootloader and check that the boot trees carry the
;; ANE node, the system's kernel tree can autoload ane.ko, and udev opens
;; /dev/accel to the video group.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(defn out [& args]
  (str/trim (:out (apply shell {:out :string :err :inherit} args))))

(defn last-line [s] (last (str/split-lines s)))

(def failures (atom []))
(defn check! [ok? msg]
  (println (if ok? "ok  " "FAIL") msg)
  (when-not ok? (swap! failures conj msg)))

(def system (last-line (out "make" "-s" "build")))
(def bootloader
  (last-line (out "guix" "build" "-L" "modules" "-e"
                  "((@ (gnu bootloader) bootloader-package) (@ (engstrand system bootloader) m1n1-u-boot-grub-bootloader-os-prepare))")))
(def dtc-bin (str (last-line (out "guix" "build" "dtc")) "/bin/"))

(check! (= "apple,t8103-ane"
           (out (str dtc-bin "fdtget") (str bootloader "/lib/dtbs/apple/t8103-j313.dtb")
                "/soc/ane@26bc04000" "compatible"))
        "bootloader profile's j313 tree has the ANE node")

(def modules-dir (first (fs/list-dir (str system "/kernel/lib/modules"))))
(check! (seq (fs/glob modules-dir "updates/ane.ko*"))
        (str "system kernel tree has updates/ane.ko in " (fs/file-name modules-dir)))
(check! (some #(and (str/includes? % "apple,t8103-ane") (str/ends-with? % " ane"))
              (str/split-lines (slurp (str modules-dir "/modules.alias"))))
        "modules.alias maps apple,t8103-ane to ane")

(def rule (->> (str/split-lines (out "guix" "gc" "-R" system))
               (keep #(let [f (fs/file % "lib/udev/rules.d/70-ane-accel.rules")]
                        (when (fs/exists? f) f)))
               first))
(check! (and rule (str/includes? (slurp rule) "SUBSYSTEM==\"accel\", GROUP=\"video\", MODE=\"0660\""))
        "system closure has the accel udev rule")

(when (seq @failures)
  (println (count @failures) "check(s) failed")
  (System/exit 1))
