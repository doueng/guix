(ns rexmux.launch-test
  "Super+Return, E, D, G and B run ~/.local/bin/mux-terminal from Hyprland,
  whose PATH holds the Guix Home profile but not ~/.local/bin. Under that PATH
  mux-terminal must name each launcher by a path that exists (T207: a bare
  rexmux was not found, so the keys opened nothing)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(def script (str (fs/real-path "../../bin/mux-terminal")))

(defn launch-argv
  "The argv mux-terminal would exec for BACKEND (the mux file's word), run as
  Hyprland runs it: through a ~/.local/bin-style symlink, with only bb's
  directory on PATH."
  [backend]
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [bin (fs/create-dirs (fs/path tmp "local-bin"))
            link (fs/create-sym-link (fs/path bin "mux-terminal") script)
            config (fs/create-dirs (fs/path tmp "config" "engstrand"))]
        (spit (str (fs/path config "mux")) (str backend "\n"))
        (let [{:keys [exit out err]} @(p/process [(str link)]
                                                 {:out :string :err :string
                                                  :env {"PATH" (str (fs/parent (fs/which "bb")))
                                                        "HOME" (str tmp)
                                                        "XDG_CONFIG_HOME" (str (fs/path tmp "config"))
                                                        "MUX_TERMINAL_DRY_RUN" "1"}})]
          (is (zero? exit) err)
          (edn/read-string out)))
      (finally (fs/delete-tree tmp)))))

(deftest launchers-resolve-without-local-bin-on-path
  (doseq [[backend argv0 args] [["rex" "rexmux" ["gui"]] ["herdr" "herdr-terminal" []]]]
    (testing backend
      (let [[cmd & more] (launch-argv backend)]
        (is (fs/absolute? cmd) (str cmd " must be a path, not looked up on PATH"))
        (is (= argv0 (fs/file-name cmd)))
        (is (fs/executable? cmd))
        (is (= args (vec more)))))))
