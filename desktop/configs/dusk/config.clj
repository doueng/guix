(ns config
  (:require [clojure.string :as str]
            [dusk.core :as dusk]
            [dusk.ui :as ui]))

(def theme
  {:primary "#cba6f7"
   :on-primary "#11111b"
   :surface "#1e1e2e"
   :on-surface "#cdd6f4"
   :surface-variant "#313244"
   :on-surface-variant "#a3b4eb"
   :outline "#4c4f69"
   :error "#f38ba8"})

(def corner-radius-scale 1.3)
(def radius (* 9 corner-radius-scale))
(def popup-opacity 0.6)

(ui/defaults! {:scale 1.125 :font-family "sans-serif" :color (:on-surface theme)})

(defn card-style [opacity]
  {:bg (ui/alpha (:surface theme) opacity)
   :border 1
   :border-color (:outline theme)
   :radius radius
   :shadow :md})


(def wallpaper-path "~/Desktop/wallpaper.jpg")

(defn wallpaper []
  (ui/layer {:id :wallpaper
             :layer :background
             :anchor #{:top :bottom :left :right}
             :exclusive-zone -1
             :scale 1}
    (ui/image {:src wallpaper-path :fit :cover :width :full :height :full})))


(def notifications (dusk/signal :notifications))

(defn- default-action? [actions]
  (some (fn [[k]] (= k "default")) actions))

(defn toast [{:keys [id summary body urgency actions]}]
  (ui/column
    (merge (card-style popup-opacity)
           {:key id
            :width :full
            :padding [10 12]
            :gap 3
            :border-color (if (= urgency :critical) (:error theme) (:outline theme))
            :on-click #(if (default-action? actions)
                         (dusk/call! :notifications/invoke id "default")
                         (dusk/call! :notifications/dismiss id))})
    (when-not (str/blank? summary)
      (ui/text {:font-size 15 :font-weight :semibold :line-clamp 6} summary))
    (when-not (str/blank? body)
      (ui/text {:font-size 13 :line-clamp 6 :color (:on-surface-variant theme)} body))))

(defn notification-stack []
  (when-let [items (seq (take 5 @notifications))]
    (ui/layer {:id :notifications
               :layer :overlay
               :anchor #{:top :right}
               :margin [0 6 0 0]
               :width 360
               :height :auto
               :padding 8
               :gap 8}
      (map toast items))))


(defonce osd (dusk/state nil))

(defn show-osd! [kind level]
  (reset! osd {:kind kind :level level})
  (dusk/after! ::osd-hide 1400 #(reset! osd nil)))

(doseq [kind [:volume :microphone :brightness]]
  (dusk/changes [::osd kind] (dusk/signal kind)
                (fn [old new]
                  (when (and old new)
                    (show-osd! kind new)))))

(defn- osd-icon [kind {:keys [percent muted?]}]
  (case kind
    :volume (cond muted? "audio-volume-muted-symbolic"
                  (< percent 34) "audio-volume-low-symbolic"
                  (< percent 67) "audio-volume-medium-symbolic"
                  :else "audio-volume-high-symbolic")
    :microphone (if muted?
                  "microphone-sensitivity-muted-symbolic"
                  "microphone-sensitivity-high-symbolic")
    :brightness "display-brightness-symbolic"))

(defn osd-popup []
  (when-let [{:keys [kind level]} @osd]
    (let [{:keys [percent muted?]} level]
      (ui/layer {:id :osd
                 :layer :overlay
                 :anchor #{:top :right}
                 :margin [0 6 0 0]
                 :width 270
                 :height :auto
                 :padding 8}
        (ui/row (merge (card-style popup-opacity)
                       {:height 46 :padding [0 14] :gap 12})
          (ui/icon (osd-icon kind level) {:size 20 :color (:primary theme)})
          (ui/progress {:value (if muted? 0 (/ (min percent 100) 100.0))
                        :grow true
                        :height 6
                        :fill (:primary theme)
                        :track (:surface-variant theme)})
          (ui/text {:width 38 :text-align :right :font-size 13}
                   (if muted? "Off" (str percent "%"))))))))


(defonce launcher (dusk/state nil))

(defn- subsequence? [item query]
  (loop [[c & cs] (seq query) from 0]
    (if-not c
      true
      (let [i (str/index-of item c from)]
        (and i (recur cs (inc i)))))))

(defn- score [item query]
  (let [item (str/lower-case item)
        query (str/lower-case query)]
    (cond
      (str/blank? query) 0
      (str/starts-with? item query) 3
      (str/includes? item query) 2
      (subsequence? item query) 1
      :else nil)))

;; A launcher entry is {:label :keys :source :value}. dmenu sends plain strings,
;; which choose themselves.
(defn- entry [item]
  (if (string? item) {:label item :value item} item))

(defn- entry-score
  "Match the label first. A query that only matches the keys, such as SPC c,
  still finds the entry, below every label match."
  [{:keys [label keys]} query]
  (or (score label query)
      (when (and keys (str/includes? (str/lower-case keys) (str/lower-case query))) 0.5)))

(defn- ranked-items [items query]
  (->> items
       (keep-indexed (fn [i item] (let [e (entry item)]
                                    (when-let [s (entry-score e query)] [s i e]))))
       ;; Ties keep the caller's order, so callers rank recent entries first.
       (sort-by (fn [[s i]] [(- s) i]))
       ;; Identity comes from the original request, not the filtered position
       ;; or display text (duplicate dmenu lines are valid).
       (mapv (fn [[_ i e]] (assoc e :id i :text (:label e))))))

(defn matches [items query]
  (mapv :text (ranked-items items query)))

(defn- visible-results [items selected]
  (let [start (min (max 0 (- (count items) 8)) (max 0 (- selected 7)))]
    (map-indexed (fn [i item] [(+ start i) item])
                 (subvec items start (min (count items) (+ start 8))))))

(defn close-launcher! [choice]
  (when-let [{:keys [respond]} @launcher]
    (reset! launcher nil)
    (respond choice)))

(defn- open-launcher! [items prompt respond]
  (close-launcher! nil)
  (reset! launcher {:items (vec items) :prompt prompt :query "" :selected 0
                    :respond respond}))

(dusk/on-request :dmenu
  (fn [{:keys [items prompt]} respond]
    (open-launcher! items prompt respond)))

;; `palette` sends {:prompt P :entries [{:label :keys :source :value} ...]} and
;; receives the chosen entry's :value, or nil when cancelled.
(dusk/on-request :palette
  (fn [{:keys [entries prompt]} respond]
    (open-launcher! entries prompt respond)))

(defn- results [] (ranked-items (:items @launcher) (:query @launcher)))

(defn- move! [delta]
  (let [n (count (results))]
    (when (pos? n)
      (swap! launcher update :selected #(mod (+ % delta) n)))))

(defn launcher-key [{:keys [key ctrl? shift?]}]
  (cond
    (= key "escape") (close-launcher! nil)
    (= key "enter") (close-launcher! (:value (get (results) (:selected @launcher))))
    (or (= key "down") (and (= key "tab") (not shift?)) (and ctrl? (#{"n" "j"} key))) (move! 1)
    (or (= key "up") (and (= key "tab") shift?) (and ctrl? (#{"p" "k"} key))) (move! -1)))

(defn- dismiss-backdrop []
  (ui/box {:position :absolute :top 0 :left 0 :width :full :height :full
           :on-click #(close-launcher! nil)}))

(defn launcher-panel []
  (when-let [{:keys [query selected prompt]} @launcher]
    (ui/layer {:id :launcher
               :layer :overlay
               :anchor #{:top :bottom :left :right}
               :keyboard :exclusive
               :exclusive-zone -1
               :on-key launcher-key
               :align :center
               :justify :end
               :padding [0 0 24 0]}
      (dismiss-backdrop)
      (ui/column (merge (card-style 0.85)
                        {:width 760 :padding 12 :gap 8 :occlude true :shadow :lg})
        (ui/input {:value query
                   :placeholder (if (str/blank? prompt) "Search" prompt)
                   :placeholder-color (:on-surface-variant theme)
                   :caret-color (:primary theme)
                   :font-size 16
                   :height 38
                   :padding [0 12]
                   :gap 1
                   :radius (* 6 1.3)
                   :bg (ui/alpha (:surface-variant theme) 0.8)
                   :on-change #(swap! launcher assoc :query % :selected 0)})
        (for [[i {:keys [id text keys source value]}] (visible-results (results) selected)
              :let [chosen? (= i selected)
                    dim (if chosen? (:on-primary theme) (:on-surface-variant theme))]]
          (ui/row {:key id
                   :height 36
                   :padding [0 12]
                   :gap 12
                   :radius (* 6 1.3)
                   :font-size 14
                   :bg (when chosen? (:primary theme))
                   :color (when chosen? (:on-primary theme))
                   :hover (when-not chosen? {:bg (ui/alpha (:surface-variant theme) 0.8)})
                   :on-click #(close-launcher! value)}
            (ui/text {:grow true :line-clamp 1} text)
            (when keys (ui/text {:font-size 13 :color dim} keys))
            (when source (ui/text {:width 72 :text-align :right :font-size 12 :color dim} source))))))))


(defonce leader-hint (dusk/state nil))

;; keyleader listen sends {:title "Cmd+Space w" :rows [["h" "Focus left"] ...]}
;; on each leader submap change, and {} when the submap ends.
(dusk/on-request :leader-hint
  (fn [{:keys [rows] :as panel} respond]
    (reset! leader-hint (when (seq rows) panel))
    (respond true)))

(defn leader-panel []
  (when-let [{:keys [title rows]} @leader-hint]
    (ui/layer {:id :leader-hint
               :layer :overlay
               :anchor #{:bottom}
               :margin [0 0 24 0]
               :keyboard :none
               :width 420
               :height :auto
               :padding 8}
      (ui/column (merge (card-style 0.9) {:padding [10 14] :gap 4 :shadow :lg})
        (ui/text {:font-size 13 :color (:on-surface-variant theme)} title)
        (for [[k label] rows]
          (ui/row {:key k :gap 12 :height 22}
            (ui/text {:width 40 :font-size 14 :font-weight :semibold :color (:primary theme)} k)
            (ui/text {:font-size 14} label)))
        (ui/text {:font-size 12 :color (:on-surface-variant theme)} "Esc  Cancel")))))

(defn shell []
  [(wallpaper)
   (notification-stack)
   (osd-popup)
   (launcher-panel)
   (leader-panel)])

(dusk/shell! #'shell)
