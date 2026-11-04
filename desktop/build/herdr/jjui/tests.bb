(require '[clojure.test :refer [deftest is run-tests]]
         '[babashka.fs :as fs])
(load-file "jjui.bb")

(deftest asks-herdr-for-a-jjui-popup-in-the-directory
  (let [dir (str (fs/create-temp-dir))]
    (is (= {:id "1" :method "plugin.pane.open"
            :params {:plugin_id "engstrand.jjui" :entrypoint "jjui" :placement "popup"
                     :width "85%" :height "80%" :cwd dir :focus true
                     :env {"HERDR_JJUI_THEN" "[\"bbh\",\"inbox\"]"}}}
           (jjui/request dir ["bbh" "inbox"])))
    (is (= {} (:env (:params (jjui/request dir nil)))) "nothing to return to")
    (is (thrown-with-msg? Exception #"Not a directory" (jjui/request (str dir "/gone") nil)))))

(deftest the-requested-entrypoint-is-the-manifest-popup-running-the-launcher
  (let [manifest (slurp "manifest.toml")
        {:keys [plugin_id entrypoint]} (:params (jjui/request "." nil))]
    (is (.contains manifest (str "id = \"" plugin_id "\"")))
    (is (.contains manifest (str "id = \"" entrypoint "\"\ntitle = \"jjui\"\nplacement = \"popup\"\ncommand = [\"./bin/herdr-jjui\", \"--pane\"]")))))

(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
