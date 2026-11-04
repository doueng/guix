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

(defn fake-herdr
  "A Herdr socket at PATH that answers like Herdr: popup.close fails with
  popup_not_open unless OPEN?, and every other call succeeds. Returns an atom
  of the methods it received."
  [path open?]
  (let [seen (atom [])
        server (doto (java.nio.channels.ServerSocketChannel/open java.net.StandardProtocolFamily/UNIX)
                 (.bind (java.net.UnixDomainSocketAddress/of path)))]
    (future
      (loop []
        (with-open [ch (.accept server)]
          (let [r (java.io.BufferedReader. (java.io.InputStreamReader. (java.nio.channels.Channels/newInputStream ch) "UTF-8"))
                w (java.io.OutputStreamWriter. (java.nio.channels.Channels/newOutputStream ch) "UTF-8")
                {:keys [id method params]} (cheshire.core/parse-string (.readLine r) true)]
            (swap! seen conj [method (:cwd params)])
            (.write w (str (cheshire.core/generate-string
                            (if (and (= method "popup.close") (not open?))
                              {:id id :error {:code "popup_not_open" :message "no popup is open"}}
                              {:id id :result {}}))
                           "\n"))
            (.flush w)))
        (recur)))
    seen))

(deftest opens-jjui-in-the-directory-with-or-without-a-popup-open
  (doseq [open? [false true]]
    (let [tmp (fs/create-temp-dir)
          sock (str tmp "/herdr.sock")
          dir (str (fs/create-dirs (fs/path tmp "repo")))
          seen (fake-herdr sock open?)
          {:keys [exit err]} (babashka.process/shell {:continue true :out :string :err :string
                                                      :extra-env {"HERDR_SOCKET_PATH" sock}}
                                                     "bb" "jjui.bb" dir)]
      (is (= 0 exit) (str "popup open: " open? "; " err))
      (is (= ["plugin.pane.open" dir] (last @seen)) (str "popup open: " open?)))))

(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
