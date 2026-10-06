#!/usr/bin/env bb
;; usage: verify.bb [check ...]    (default: all checks, in table order)

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def here (str (fs/parent (fs/absolutize *file*))))
(def profile-bin (str here "/.guix-profile/bin/"))
(def dusk (str here "/target/release/dusk"))
(def config-dir (str (fs/normalize (fs/path here "../../configs/dusk"))))
(def shot-dir (str (fs/create-temp-dir {:prefix "dusk-verify"})))

(defn tool [name]
  (let [local (str profile-bin name)]
    (if (fs/executable? local) local name)))

(defn sh [& args]
  (let [opts (if (map? (last args)) (last args) {})
        argv (if (map? (last args)) (butlast args) args)]
    @(p/process (vec argv) (merge {:out :string :err :string :continue true} opts))))

(defn msg [& args]
  (let [r (apply sh dusk "msg" args)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn signals [] (some-> (msg "signals") edn/read-string))
(defn surfaces [] (set (some-> (msg "surfaces") edn/read-string)))

(defn wait-for
  "Poll `f` every 25 ms until it returns truthy or `ms` elapse. Returns
  [value elapsed-ms]."
  [ms f]
  (let [start (System/nanoTime)]
    (loop []
      (let [v (f)
            elapsed (/ (- (System/nanoTime) start) 1e6)]
        (cond
          v [v elapsed]
          (> elapsed ms) [nil elapsed]
          :else (do (Thread/sleep 25) (recur)))))))

(def output-size [2560 1600])

(defn screenshot []
  (let [f (str shot-dir "/" (System/nanoTime) ".png")]
    (sh "grim" "-o" "eDP-1" f)
    f))

(defn patch-rgb
  "Mean [r g b] (0-255) of the w*h patch at x,y of an image file."
  [file x y w h]
  (let [r (sh (tool "convert") file "-crop" (format "%dx%d+%d+%d" w h x y) "+repage"
              "-resize" "1x1!" "-format" "%[fx:255*r] %[fx:255*g] %[fx:255*b]" "info:")]
    (mapv #(Double/parseDouble %) (str/split (str/trim (:out r)) #"\s+"))))

(defn hex-rgb [hex]
  (mapv #(Integer/parseInt (subs hex % (+ % 2)) 16) [1 3 5]))

(defn blend [fg alpha bg]
  (mapv #(+ (* alpha %1) (* (- 1 alpha) %2)) fg bg))

(defn distance [a b]
  (apply max (map #(Math/abs (double (- %1 %2))) a b)))

(defn rgb-str [c] (str/join "," (map #(Math/round (double %)) c)))

(defn close-to [label actual expected tolerance]
  {:ok (<= (distance actual expected) tolerance)
   :detail (format "%s got %s want %s (max channel diff %.1f, tolerance %d)"
                   label (rgb-str actual) (rgb-str expected) (distance actual expected) tolerance)})

(def scale 1.8)
(def surface-color (hex-rgb "#1e1e2e"))
(def popup-opacity 0.6)
(def layer-margin-right 6)
(def layer-padding 8)

(defn popup-probe
  [inset-right down]
  [(- (first output-size) (Math/round (* scale (+ layer-margin-right layer-padding inset-right))))
   (Math/round (* scale (+ layer-padding down)))])

(defn on-empty-workspace
  "Call `f` while an empty workspace is shown, so windows don't cover the
  background layer, then return to the previous workspace."
  [f]
  (let [current (-> (sh "hyprctl" "activeworkspace" "-j") :out (json/parse-string true) :id)]
    (sh "hyprctl" "dispatch" "workspace" "empty")
    (Thread/sleep 300)
    (try (f)
         (finally (sh "hyprctl" "dispatch" "workspace" (str current))))))

(defn check-wallpaper []
  (let [[w h] output-size
        shot (on-empty-workspace screenshot)
        expected-file (str shot-dir "/wallpaper-expected.png")
        _ (sh (tool "convert") (str (fs/expand-home "~/Desktop/wallpaper.jpg"))
              "-resize" (format "%dx%d^" w h) "-gravity" "center" "-extent" (format "%dx%d" w h)
              expected-file)
        probes [[1260 780] [300 1300] [2200 1300]]]
    (cons {:ok (contains? (surfaces) "wallpaper") :detail "surface \"wallpaper\" exists"}
          (for [[x y] probes]
            (close-to (format "wallpaper patch at %d,%d" x y)
                      (patch-rgb shot x y 40 40) (patch-rgb expected-file x y 40 40) 12)))))

(defn notification-ids []
  (set (map :id (:notifications (signals)))))

(defn check-notifications []
  (let [baseline (screenshot)
        sent (sh "notify-send" "-p" "-t" "2500" "dusk verify" "toast body")
        id (parse-long (str/trim (:out sent)))
        [shown shown-ms] (wait-for 2000 #(and (contains? (notification-ids) id)
                                             (contains? (surfaces) "notifications")))
        _ (Thread/sleep 150)
        shot (screenshot)
        [x y] (popup-probe 40 3)
        pixel (close-to (format "toast card at %d,%d" x y) (patch-rgb shot x y 6 4)
                        (blend surface-color popup-opacity (patch-rgb baseline x y 6 4)) 10)
        [expired expired-ms] (wait-for 4500 #(not (contains? (notification-ids) id)))
        replace-a (parse-long (str/trim (:out (sh "notify-send" "-p" "-t" "0" "replace" "first"))))
        replace-b (parse-long (str/trim (:out (sh "notify-send" "-p" "-r" (str replace-a) "-t" "0" "replace" "second"))))
        [replaced] (wait-for 1000 #(let [ns (filter (fn [n] (= replace-a (:id n))) (:notifications (signals)))]
                                     (and (= 1 (count ns)) (= "second" (:body (first ns))))))
        _ (sh "busctl" "--user" "call" "org.freedesktop.Notifications" "/org/freedesktop/Notifications"
              "org.freedesktop.Notifications" "CloseNotification" "u" (str replace-a))
        [closed] (wait-for 1000 #(not (contains? (notification-ids) replace-a)))
        [gone] (wait-for 1000 #(not (contains? (surfaces) "notifications")))
        owner (sh "busctl" "--user" "call" "org.freedesktop.Notifications" "/org/freedesktop/Notifications"
                  "org.freedesktop.Notifications" "GetServerInformation")]
    [{:ok (str/includes? (:out owner) "\"dusk\"") :detail (str "server information: " (str/trim (:out owner)))}
     {:ok (some? id) :detail (str "notify-send returned id " id)}
     {:ok (boolean shown) :detail (format "toast listed and surface open after %.0f ms" shown-ms)}
     pixel
     {:ok (boolean expired) :detail (format "2500 ms toast expired %.0f ms after the first poll" expired-ms)}
     {:ok (and (boolean replaced) (= replace-a replace-b)) :detail (format "replace kept id %s -> %s with new body" replace-a replace-b)}
     {:ok (boolean closed) :detail "CloseNotification removed it"}
     {:ok (boolean gone) :detail "notifications surface closed when empty"}]))

(defn wpctl-volume []
  (let [out (:out (sh "wpctl" "get-volume" "@DEFAULT_AUDIO_SINK@"))]
    (Double/parseDouble (second (str/split (str/trim out) #"\s+")))))

(defn osd-pixel [baseline shot label]
  (let [osd-card-height 46
        [x y] (popup-probe 6 (/ osd-card-height 2))]
    (close-to (format "%s card at %d,%d" label x y) (patch-rgb shot x y 4 4)
              (blend surface-color popup-opacity (patch-rgb baseline x y 4 4)) 10)))

(defn check-osd []
  (let [baseline (screenshot)
        before (wpctl-volume)
        target (if (> before 0.5) (- before 0.01) (+ before 0.01))
        _ (sh "wpctl" "set-volume" "@DEFAULT_AUDIO_SINK@" (format "%.2f" target))
        set-at (System/nanoTime)
        want (Math/round (* 100 target))
        [shown shown-ms] (wait-for 1500 #(and (contains? (surfaces) "osd")
                                             (= want (get-in (signals) [:volume :percent]))))
        _ (Thread/sleep 150)
        pixel (osd-pixel baseline (screenshot) "volume osd")
        [hidden] (wait-for 3000 #(not (contains? (surfaces) "osd")))
        hidden-ms (/ (- (System/nanoTime) set-at) 1e6)
        _ (sh "wpctl" "set-volume" "@DEFAULT_AUDIO_SINK@" (format "%.2f" before))
        _ (wait-for 1500 #(contains? (surfaces) "osd"))
        [hidden-again] (wait-for 3000 #(not (contains? (surfaces) "osd")))
        bright (str/trim (:out (sh "brightnessctl" "get")))
        _ (sh "brightnessctl" "set" "+1%")
        [bshown] (wait-for 1500 #(contains? (surfaces) "osd"))
        _ (sh "brightnessctl" "set" bright)
        [bhidden] (wait-for 3000 #(not (contains? (surfaces) "osd")))]
    [{:ok (boolean shown) :detail (format "volume %d%% osd shown %.0f ms after wpctl returned" want shown-ms)}
     pixel
     {:ok (and (boolean hidden) (< 1400 hidden-ms 2200)) :detail (format "osd hid %.0f ms after the volume change (1400 ms delay)" hidden-ms)}
     {:ok (boolean hidden-again) :detail (format "restored volume to %.2f" before)}
     {:ok (boolean bshown) :detail "brightness change showed the osd"}
     {:ok (boolean bhidden) :detail (str "restored brightness to " bright)}]))

(defn dmenu-run [keys]
  (let [proc (p/process [dusk "dmenu" "-p" "verify"]
                        {:in "ghostty\nchrome-incognito\nchrome\nthunar\n" :out :string :err :string})
        [opened] (wait-for 2000 #(contains? (surfaces) "launcher"))]
    ;; Give the compositor a moment to hand the exclusive surface focus.
    (Thread/sleep 250)
    (doseq [k keys]
      (if (str/starts-with? k "key:")
        (sh (tool "wtype") "-k" (subs k 4))
        (sh (tool "wtype") k))
      (Thread/sleep 60))
    (let [r (deref proc 3000 nil)]
      (when-not r (p/destroy proc))
      {:opened (boolean opened) :exit (:exit r) :out (some-> r :out str/trim)})))

(defn check-launcher []
  (let [pick (dmenu-run ["thu" "key:Return"])
        fuzzy (dmenu-run ["chi" "key:Return"])
        arrows (dmenu-run ["key:Down" "key:Down" "key:Return"])
        cancel (dmenu-run ["gh" "key:Escape"])
        [closed] (wait-for 1000 #(not (contains? (surfaces) "launcher")))]
    [{:ok (:opened pick) :detail "launcher surface opened"}
     {:ok (= [0 "thunar"] [(:exit pick) (:out pick)]) :detail (str "typing thu + Return printed " (pr-str (:out pick)))}
     {:ok (= "chrome-incognito" (:out fuzzy)) :detail (str "subsequence chi picked " (pr-str (:out fuzzy)))}
     {:ok (= "chrome" (:out arrows)) :detail (str "Down Down Return picked " (pr-str (:out arrows)))}
     {:ok (= 1 (:exit cancel)) :detail (str "Escape exited " (:exit cancel) " with output " (pr-str (:out cancel)))}
     {:ok (boolean closed) :detail "launcher surface closed"}]))

(defn check-reload []
  (let [probe (str config-dir "/zz-verify-probe.clj")
        stamp (System/currentTimeMillis)
        start (System/nanoTime)]
    (try
      (spit probe (format "(ns verify-probe)\n(def stamp %d)\n" stamp))
      (let [[seen] (wait-for 3000 #(= (str stamp) (msg "eval" "(some-> (resolve 'verify-probe/stamp) deref)")))
            elapsed (/ (- (System/nanoTime) start) 1e6)]
        [{:ok (boolean seen)
          :detail (format "saved file visible in the runtime after %.0f ms (25 ms polling, 60 ms debounce)" elapsed)}
         {:ok (= #{"wallpaper"} (set/intersection #{"wallpaper"} (surfaces)))
          :detail "wallpaper surface survived the reload"}])
      (finally
        (fs/delete-if-exists probe)))))

(defn check-errors []
  (let [probe (str config-dir "/zz-verify-broken.clj")]
    (try
      (spit probe "(ns verify-broken)\n(/ 1 0)\n")
      (let [[shown] (wait-for 3000 #(contains? (surfaces) "dusk/error"))
            kept (contains? (surfaces) "wallpaper")]
        (fs/delete-if-exists probe)
        (let [[cleared] (wait-for 3000 #(not (contains? (surfaces) "dusk/error")))]
          [{:ok (boolean shown) :detail "a config file that throws opens the error surface"}
           {:ok kept :detail "previous UI kept while the config is broken"}
           {:ok (boolean cleared) :detail "removing the broken file reloads and clears the error"}]))
      (finally
        (fs/delete-if-exists probe)))))

(defn check-lifecycle []
  (msg "eval" "(do (dusk.core/send! {:op :error :message \"probe\"}) (dusk.core/send! {:op :reloaded}) nil)")
  (dotimes [_ 10]
    (let [id (str/trim (:out (sh "notify-send" "-p" "-t" "0" "lifecycle" "probe")))]
      (sh "busctl" "--user" "call" "org.freedesktop.Notifications" "/org/freedesktop/Notifications"
          "org.freedesktop.Notifications" "CloseNotification" "u" id)))
  (Thread/sleep 500)
  [{:ok (= "running" (msg "status"))
    :detail "dusk survives auto-sized surfaces closing in the frame they open"}])

(def checks
  (array-map
   "wallpaper" check-wallpaper
   "notifications" check-notifications
   "osd" check-osd
   "launcher" check-launcher
   "reload" check-reload
   "errors" check-errors
   "lifecycle" check-lifecycle))

(defn -main [& names]
  (when (zero? (:exit (sh "pgrep" "-x" "noctalia")))
    (println "noctalia is running; stop it first (it owns the notification name and wallpaper)")
    (System/exit 2))
  (let [started (when-not (msg "status")
                  (let [proc (p/process [dusk "--config" (str config-dir "/config.clj")]
                                        {:out (str shot-dir "/dusk.log") :err (str shot-dir "/dusk.log")})]
                    (wait-for 5000 #(contains? (surfaces) "wallpaper"))
                    proc))
        names (or (seq names) (keys checks))
        results (doall
                 (for [n names]
                   (let [f (or (get checks n) (throw (ex-info (str "unknown check " n) {})))
                         rs (try (doall (f))
                                 (catch Exception e [{:ok false :detail (str "threw " (ex-message e))}]))]
                     (println (str "## " n))
                     (doseq [{:keys [ok detail]} rs]
                       (println (if ok "  PASS" "  FAIL") detail))
                     (every? :ok rs))))]
    (when started
      (msg "quit")
      (deref started 3000 nil))
    (println (if (every? true? results) "VERIFIED" "NOT VERIFIED") (str "(screenshots in " shot-dir ")"))
    (System/exit (if (every? true? results) 0 1))))

(apply -main *command-line-args*)
