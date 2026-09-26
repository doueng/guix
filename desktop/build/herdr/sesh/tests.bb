(require '[clojure.test :refer [deftest is run-tests]])
(load-file "sesh.bb")

(def workspaces [{:workspace_id "a" :label "old" :focused true}
                 {:workspace_id "b" :label "other"}])

(deftest config-and-manifest
  (let [cfg (sesh/settings (str "[[window]]\nname = \"emacs\"\nstartup_script = \"emacs-project-start\"\n"
                                "[[window]]\nname = \"pi\"\nstartup_script = \"pi\"\n"
                                "[[window]]\nname = \"jjui\"\nstartup_script = \"jjui\"\n"
                                "[[wildcard]]\npattern = \"/**\"\nwindows = [\"emacs\", \"pi\", \"jjui\"]\n"))]
    (is (= ["emacs" "pi" "jjui"] (mapv :name (sesh/startup-windows cfg "/tmp/project"))))
  (let [manifest (slurp "manifest.toml")]
    (is (.contains manifest "command = [\"./bin/herdr-sesh\", \"plugin\", \"open-picker\"]"))
    (is (.contains manifest "command = [\"./bin/herdr-sesh\", \"picker\"]")))))

(deftest sources
  (with-redefs [sesh/zoxide-paths (constantly ["/tmp/old" "/tmp/else"])
                babashka.fs/directory? (constantly true)]
    (is (= [:herdr :herdr :zoxide]
           (mapv :source (sesh/sessions workspaces))))))

(deftest focus-and-toggle
  (let [calls (atom []) prev (atom "b")]
    (with-redefs [sesh/workspaces (constantly workspaces)
                  sesh/current-id (fn [_] "a")
                  sesh/previous-id (fn [] @prev)
                  sesh/record-switch! (fn [from to] (swap! calls conj [:record from to]) (reset! prev from))
                  sesh/herdr-json (fn [& args] (swap! calls conj args))]
      (sesh/last!)
      (is (= [["workspace" "focus" "b"] [:record "a" "b"]] @calls))
      (reset! calls [])
      (sesh/last!)
      (is (empty? @calls)))))

(deftest existing-workspace
  (let [calls (atom [])]
    (with-redefs [sesh/workspaces (constantly workspaces)
                  sesh/zoxide-paths (constantly [])
                  sesh/current-id (fn [_] "a")
                  sesh/record-switch! (fn [from to] (swap! calls conj [:record from to]))
                  sesh/herdr-json (fn [& args] (swap! calls conj args))]
      (sesh/connect! "other")
      (is (= [["workspace" "focus" "b"] [:record "a" "b"]] @calls))
      (reset! calls [])
      (sesh/connect! "a")
      (is (empty? @calls)))))

(deftest create-project
  (let [calls (atom [])]
    (with-redefs [sesh/workspaces (constantly workspaces)
                  sesh/zoxide-paths (constantly [])
                  sesh/run (fn [args & _] (swap! calls conj args) "")
                  sesh/current-id (fn [_] "a")
                  sesh/config (fn [] (sesh/settings (str "[[window]]\nname = \"emacs\"\nstartup_script = \"emacs-project-start\"\n"
                                                       "[[window]]\nname = \"pi\"\nstartup_script = \"pi\"\n"
                                                       "[[window]]\nname = \"jjui\"\nstartup_script = \"jjui\"\n"
                                                       "[[wildcard]]\npattern = \"/**\"\nwindows = [\"emacs\", \"pi\", \"jjui\"]\n")))
                  sesh/record-switch! (fn [from to] (swap! calls conj [:record from to]))
                  sesh/herdr-json (fn [& args]
                                    (swap! calls conj (vec args))
                                    (case (first args)
                                      "workspace" (when (= "create" (second args)) {:workspace {:workspace_id "new"}})
                                      "tab" {:root_pane {:pane_id "p"}}
                                      "pane" nil))]
      (sesh/connect! "/tmp")
      (is (= ["workspace" "create" "--cwd" "/tmp" "--label" "tmp" "--no-focus"] (first @calls)))
      (is (= ["zoxide" "add" "/tmp"] (second @calls)))
      (is (= ["tab" "create" "--workspace" "new" "--cwd" "/tmp" "--label" "emacs" "--focus"] (nth @calls 2)))
      (is (= "--no-focus" (last (nth @calls 4))))
      (is (= ["pane" "run" "p" "emacs-project-start"] (nth @calls 3)))
      (is (= ["workspace" "focus" "new"] (nth @calls 8)))
      (is (= [:record "a" "new"] (last @calls))))))

(deftest picker-selection
  (let [rows [{:source :herdr :name "a" :id "w1"}
              {:source :zoxide :name "project" :path "/tmp/project"}]
        input (atom nil)]
    (with-redefs [babashka.process/process
                  (fn [_ options] (reset! input (:in options))
                    (delay {:exit 0 :out "1\tzoxide  project  /tmp/project\n"}))]
      (is (= (second rows) (sesh/picker-choice rows)))
      (is (.contains @input "1\tzoxide")))
    (with-redefs [babashka.process/process
                  (fn [_ _] (delay {:exit 130 :out ""}))]
      (is (nil? (sesh/picker-choice rows))))))

(deftest plugin-action
  (let [calls (atom [])]
    (with-redefs [sesh/herdr-json (fn [& args] (swap! calls conj (vec args)))]
      (sesh/main ["plugin" "open-picker"])
      (is (= [["plugin" "pane" "open" "--plugin" "fullerzz.sesh"
               "--entrypoint" "picker" "--placement" "overlay" "--focus"]] @calls)))))

(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
