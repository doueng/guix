(ns rexg.names-test
  "Tabs name themselves from what runs in them, and the screen shows no
  Rex IDs (T210): Rex events go into the model, and the drawn rows are
  read back as the Bombadil specs read them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rexg.model :as m]
            [rexg.names :as names]
            [rexg.screen :as s]))

(def sid "session:01a20e5c-c401-75a8-871f-fd591d3058e1")
(def b1 "block:01a20e5c-c401-7768-959a-e5c052d06461")
(def b2 "block:01a20e5c-c401-7768-959a-e5c0aaaa0002")
(def b3 "block:01a20e5c-c401-7768-959a-e5c0bbbb0003")

(defn- window [id label block] {:window_id id :label label :focused_block_id block
                                  :layers [{:kind "tiled" :layout {:rect [0 0 1 1] :block_id block}}]})

(defn- rex-view [label windows] {:session_id sid :label label :revision 1 :active_window_id (:window_id (first windows)) :windows windows})

(defn- shown
  "A model on one server (or SERVERS) showing VIEW, every pane's process
  told as a login fish in /home/u."
  [view & {:keys [servers startup]}]
  (let [[mm outs] (m/set-view (m/new-model (or servers [{:label "local"}]) {:env {"HOME" "/home/u"} :startup startup})
                              0 (m/view-of view))]
    (reduce (fn [mm [_ _ _ {:keys [block_id method]} tag]]
              (first (m/rex-response mm tag {:ok (case method
                                                   "process" {:foreground {:name "fish" :argv0 "-fish" :pid 10 :cwd "/home/u"} :home "/home/u"}
                                                   "title" {:title "~" :owner {:pid 10 :name "fish"}}
                                                   "pwd" {})})))
            (-> mm (assoc :sidecar-up true) (assoc-in [:servers 0 :conn] :up))
            (filter #(= :proc (first (nth % 4 nil))) outs))))

(defn- event [mm block name payload]
  (first (m/rex-event mm 0 {:type "session_event"
                            :payload {:session_id sid :event {:type "block_event"
                                                              :payload {:block_id block :name name :payload payload}}}})))

(defn- runs [mm block prog cwd & [pid]]
  (event mm block "process_changed" {:foreground {:name prog :argv0 prog :pid (or pid 20) :cwd cwd}}))

(defn- rows [mm]
  (let [{:keys [spans]} (s/render mm 100 20 {})
        grid (vec (repeatedly 20 #(char-array (repeat 100 \space))))]
    (doseq [{r :row c :col t :text f :flags} spans :when (not= 128 f) [i ch] (map-indexed vector t) :when (< (+ c i) 100)]
      (aset ^chars (grid r) (+ c i) ch))
    (mapv #(str/trimr (String. ^chars %)) grid)))

(defn- tab-bar [mm] (first (rows mm)))
(defn- status [mm] ((rows mm) 18))

(deftest a-tab-is-named-by-the-program-and-directory-of-its-pane
  (let [mm (shown (rex-view "main" [(window "window:1" "Window 6b79c2d1" b1) (window "window:2" "Window 0000aaaa" b2)]))]
    (is (str/includes? (tab-bar mm) "[1 ~]") "fish at home is ~")
    (testing "cd, reported by OSC 7, renames it"
      (let [mm (event mm b1 "pwd_changed" {:pwd "file://box/home/u/guix" :owner {:pid 10 :name "fish"}})]
        (is (str/includes? (tab-bar mm) "[1 guix]"))
        (testing "and so does a program starting and ending"
          (let [mm (runs mm b1 "jjui" "/home/u/guix")]
            (is (str/includes? (tab-bar mm) "[1 jjui]"))
            (is (str/includes? ((rows mm) 1) "▶ jjui ~/guix") "the title row has its program and directory")
            (is (str/includes? (status mm) "jjui ~/guix") "the status line names the focused pane")
            (is (str/includes? (tab-bar (runs mm b1 "fish" "/home/u/guix" 10)) "[1 guix]"))))))
    (is (str/includes? (tab-bar (runs mm b2 ".emacs-31.1-real" "/home/u")) "2 emacs") "Guix's wrapper name is cut to emacs")))

(deftest a-name-the-user-gave-stays-until-cleared
  (let [mm (shown (rex-view "main" [(window "window:1" "notes" b1)]))
        mm (runs mm b1 "nvim" "/home/u")]
    (is (str/includes? (tab-bar mm) "[1 notes]"))
    (let [cleared (first (m/rex-response mm [:view 0] {:ok (assoc (rex-view "main" [(window "window:1" "" b1)]) :revision 2)}))]
      (is (str/includes? (tab-bar cleared) "[1 nvim]") "a blank label names it again"))))

(deftest a-startup-tab-keeps-its-label-while-its-program-runs
  (let [startup [{:label "shell"} {:label "pi" :command ["pi"]}]
        mm (shown (rex-view "main" [(window "window:1" "shell" b1) (window "window:2" "pi" b2)]) :startup {:windows startup})
        mm (runs (runs mm b2 "bb" "/home/u/guix") b1 "fish" "/home/u/guix" 10)]
    (is (str/includes? (tab-bar mm) "[1 guix]") "the shell tab names itself")
    (is (str/includes? (tab-bar mm) "2 pi") "pi's tab, whatever its interpreter")
    (is (str/includes? (tab-bar (runs mm b2 "fish" "/home/u/src" 10)) "2 src") "back at the shell, its directory")))

(deftest ssh-is-named-by-its-host
  (let [mm (-> (shown (rex-view "main" [(window "window:1" "" b1)]))
               (runs b1 "ssh" "/home/u")
               (event b1 "title_changed" {:title "me@build-box: ~/src" :owner {:pid 20 :name "ssh"}}))]
    (is (str/includes? (tab-bar mm) "[1 build-box]"))))

(deftest no-rex-id-reaches-the-screen
  (let [mm (shown (rex-view "" [(window "window:1" "Window 6b79c2d1" b1) (window "window:2" "Window 0000aaaa" b2)]))
        mm (assoc-in mm [:servers 0 :sessions] [["session:other" "work"] [sid ""]])
        text (str/join "\n" (rows mm))]
    (is (not (re-find #"[0-9a-f]{6}" text)) text)
    (is (str/includes? (tab-bar mm) "session 2 │") "an unlabelled session by its place")
    (is (= "session 2" (:title (s/render mm 100 20 {}))))
    (testing "even before Rex told what runs in a pane"
      (let [bare (first (m/set-view (m/new-model [{:label "local"}]) 0 (m/view-of (rex-view "x" [(window "window:1" "Window 6b79c2d1" b3)]))))]
        (is (not (re-find #"[0-9a-f]{6}" (str/join "\n" (rows bare)))))))))

(deftest the-server-is-named-only-when-there-is-a-choice
  (let [v (rex-view "main" [(window "window:1" "" b1)])]
    (is (not (str/includes? (status (shown v)) "local")))
    (is (str/starts-with? (str/trim (status (shown v :servers [{:label "local"} {:label "box"}]))) "local │"))))

(deftest program-names-read-as-a-person-calls-them
  (is (= "fish" (names/program {:name "fish" :argv0 "-fish"})))
  (is (= "flatpak" (names/program {:name ".flatpak-real" :argv0 "/run/current-system/profile/bin/flatpak"})))
  (is (= "emacs" (names/program {:name "emacsclient" :argv0 "emacsclient"})))
  (is (= "/srv/a b" (names/path-of "file://h/srv/a%20b")))
  (is (= "…/b/c" (names/short-dir "/home/u/a/b/c" "/home/u"))))
