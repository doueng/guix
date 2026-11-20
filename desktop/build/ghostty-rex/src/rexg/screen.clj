(ns rexg.screen
  "What the ghostty-rex client draws around the panes, as rexmux's screen.rs
  lays it out, so the same Bombadil specs read it: row 0 is the tab bar, then
  the panes, each with a title row (▶ on the focused one) and │ dividers,
  then the status line and the echo or minibuffer line. Which-key, the
  minibuffer, help and tiny-fingers hints overlay the panes. rexg-render
  draws pane cells into the content rects and these spans over them."
  (:require [clojure.string :as str]
            [rexg.model :as model]))

(def colors
  {:bar "1f2430" :bar-fg "c8ccd4" :tab "3b4252" :tab-fg "ffffff" :title "6c7086" :focus "89b4fa"
   :status "181825" :status-fg "a6adc8" :blocked "f38ba8" :hint-fg "000000" :hint "f5c211"
   :menu "262a36" :menu-fg "d8dee9" :sel "45475a"})

(defn short [id] (let [s (str id)] (subs s (max 0 (- (count s) 6)))))

(defn pane-area [w h] [w (max 1 (- h 3))])

(defn tile-cells
  "Where tile RECT goes in a PW x PH pane area: [x y w h] of its title row
  and content, below the tab bar. A tile not at the left edge gives its first
  column to a divider."
  [[rx ry rw rh] pw ph]
  (let [left (Math/round (double (* rx pw)))
        right (Math/round (double (* (+ rx rw) pw)))
        x0 (if (pos? left) (inc left) left)
        y0 (inc (Math/round (double (* ry ph))))
        th (max 1 (Math/round (double (* rh ph))))]
    [x0 y0 (max 1 (- right x0)) th]))

(defn tiles
  "The visible tiles with cells: [{:block :x :y :w :h :floating}], y the
  title row. A floating tile's cells include its frame: a right border and a
  bottom rule (see content-rect)."
  [m w h]
  (let [[pw ph] (pane-area w h)]
    (vec (for [t (some-> (model/view m) model/visible)]
           (let [[x y tw th] (tile-cells (:rect t) pw ph)] (assoc t :x x :y y :w tw :h th))))))

(defn content-rect
  "[x y w h] of tile T's pane cells: below its title row, and inside the
  frame of a floating one."
  [{:keys [x y floating] tw :w th :h}]
  (if floating
    [x (inc y) (max 1 (dec tw)) (max 1 (- th 2))]
    [x (inc y) tw (max 0 (dec th))]))

(defn- box
  "The cells floating tile T covers, its frame included: [c0 c1 r0 r1), the
  pane area ending at row LAST."
  [{:keys [x y] tw :w th :h} last]
  [(if (pos? x) (dec x) x) (+ x tw) y (min (+ y th) last)])

(defn- clip
  "SPAN without the cells under BOXES, as zero or more spans."
  [boxes {:keys [row col text] :as span}]
  (reduce (fn [pieces [c0 c1 r0 r1]]
            (if-not (and (<= r0 row) (< row r1))
              pieces
              (mapcat (fn [{:keys [col text] :as p}]
                        (let [end (+ col (count text))]
                          (if (or (>= col c1) (<= end c0))
                            [p]
                            (cond-> []
                              (< col c0) (conj (assoc p :text (subs text 0 (- c0 col))))
                              (> end c1) (conj (assoc p :col c1 :text (subs text (- c1 col))))))))
                      pieces)))
          [span] boxes))

(defn pane-at
  "The visible pane under screen cell COL ROW: [block pane-col pane-row], the
  row nil on its title line."
  [m w h col row]
  (some (fn [{:keys [block x y] tw :w th :h}]
          (when (and (<= x col) (< col (+ x tw)) (<= y row) (< row (+ y th)))
            [block (- col x) (when (> row y) (- row y 1))]))
        (reverse (tiles m w h))))

(defn content-sizes
  "{block [cols rows]} of each visible pane, for Rex's resize."
  [m w h]
  (into {} (for [t (tiles m w h) :let [[_ _ cw ch] (content-rect t)]] [(:block t) [(max 2 cw) (max 1 ch)]])))

(defn- span [row col fg bg flags text] {:row row :col col :fg fg :bg bg :flags flags :text text})
(defn- fit [s n] (let [s (str s)] (if (> (count s) n) (subs s 0 (max 0 n)) s)))
(defn- pad [s n] (let [s (fit s n)] (str s (apply str (repeat (- n (count s)) \space)))))

(defn render
  "{:layout [{:block :x :y :w :h}] :spans [..] :cursor [:focus b]|[:at x y]|[:hide]
  :title str} for model M on a W x H screen; PANES holds what rexg-render
  told about each pane: {block {:mouse bool :scrolled bool}}."
  [m w h panes]
  (let [{:keys [bar bar-fg tab tab-fg title focus status status-fg blocked hint-fg hint menu menu-fg sel]} colors
        v (model/view m)
        focused (model/focused-block m)
        ts (tiles m w h)
        spans (transient [])
        add! (fn [s] (conj! spans s))
        [_ ph] (pane-area w h)]
    ;; Tabs: the session's windows.
    (add! (span 0 0 bar-fg bar 0 (pad "" w)))
    (if v
      (loop [ws (map-indexed vector (:windows v))
             col (let [lbl (str " " (if (str/blank? (:label v)) (short (:session v)) (:label v)) " │")]
                   (add! (span 0 0 bar-fg bar 1 lbl)) (count lbl))]
        (when-let [[i win] (first ws)]
          (let [active (or (= (:id win) (:active v)) (and (nil? (:active v)) (zero? i)))
                nm (if (str/blank? (:label win)) (str (inc i)) (str (inc i) " " (:label win)))
                txt (if active (str " [" nm "]") (str "  " nm " "))]
            (when (< col w) (add! (span 0 col (if active tab-fg bar-fg) (if active tab bar) (if active 1 0) (fit txt (- w col)))))
            (recur (rest ws) (+ col (count txt))))))
      (add! (span 0 0 bar-fg bar 0 " no session")))
    ;; Pane titles and dividers. A floating pane is framed, and the tiled
    ;; panes' chrome under it is cut away, so nothing crosses it.
    (let [boxes (mapv #(box % (- h 2)) (filter :floating ts))]
      (doseq [{:keys [block x y floating] tw :w th :h :as t} ts]
        (let [last (min (+ y th) (- h 2))
              agent (some #(when (= block (:block %)) %) (:agents m))
              f? (= block focused)
              st (when agent (str " " (model/icon (:state agent)) " " (:app agent)))
              hist (when (get-in panes [block :scrolled]) " ↑ history (type to return)")
              ttl (str (if f? "▶" "─") " " (short block) st hist " ")
              own (cond-> []
                    (pos? x) (into (for [yy (range y last)] (span yy (dec x) title nil 0 "│")))
                    true (conj (span y x (if f? focus title) nil (if f? 1 0) (str (fit ttl tw) (apply str (repeat (max 0 (- tw (count ttl))) "─")))))
                    (and agent (= "blocked" (:state agent)))
                    (conj (span y (+ x 2 (count (short block))) blocked nil 1 (fit (str " " (model/icon "blocked")) (max 0 (- tw 8)))))
                    floating (into (for [yy (range (inc y) (dec last))] (span yy (+ x tw -1) title nil 0 "│")))
                    floating (conj (span (dec last) (if (pos? x) (dec x) x) title nil 0
                                         (str (when (pos? x) "└") (apply str (repeat (dec tw) "─")) "┘"))
                                   (span y (+ x tw -1) title nil 0 "┐"))
                    (and floating (pos? x)) (conj (span y (dec x) title nil 0 "┌")))]
          (doseq [s (if floating own (mapcat #(clip boxes %) own))] (add! s)))))
    ;; A mouse selection, reversed in place.
    (when-let [[b [c0 r0] [c1 r1]] (:selection m)]
      (when-let [{:keys [x y] tw :w th :h} (some #(when (= b (:block %)) %) ts)]
        (let [[[c0 r0] [c1 r1]] (sort-by (fn [[c r]] [r c]) [[c0 r0] [c1 r1]])]
          (doseq [r (range r0 (inc r1)) :when (< (inc r) th)
                  c (range (if (= r r0) c0 0) (min tw (if (= r r1) (inc c1) tw)))]
            (add! (span (+ y 1 r) (+ x c) nil nil 128 "x"))))))
    ;; Tiny fingers, black on yellow over each target's start.
    (when-let [hs (:hints m)]
      (when-let [{:keys [x y] tw :w th :h} (some #(when (= (:block hs) (:block %)) %) ts)]
        (doseq [t (:targets hs) :when (and (str/starts-with? (:hint t) (:input hs)) (< (inc (:row t)) th) (< (:col t) tw))]
          (add! (span (+ y 1 (:row t)) (+ x (:col t)) hint-fg hint 1 (fit (:hint t) (- tw (:col t))))))))
    ;; Status line: server, session, attention.
    (let [rows (:agents m)
          nb (count (filter #(= "blocked" (:state %)) rows))
          s (str " " (:label (model/server m)) " │ focus " (if focused (short focused) "-")
                 " │ agents " (count rows) " │ blocked " nb " │ attention " (count (:attention m))
                 (when-not (:sidecar-up m) " │ sidecar down"))]
      (add! (span (- h 2) 0 (if (pos? nb) blocked status-fg) status 0 (pad s w))))
    ;; Help text over the panes.
    (when-let [[t text] (:doc m)]
      (add! (span 1 0 menu-fg menu 1 (pad (str "── " t " (q closes) ──") w)))
      (doseq [[i l] (map-indexed vector (take (max 0 (dec ph)) (str/split-lines text)))]
        (add! (span (+ 2 i) 0 menu-fg menu 0 (pad l w)))))
    (let [cursor
          (cond
            (:minibuffer m)
            (let [cands (model/candidates m)
                  mb (:minibuffer m)
                  room (min ph 10 (count cands))
                  first-i (max 0 (- (:selected mb) (dec room)))
                  p (str (model/prompt-text m) (:input mb))]
              (doseq [[i [label]] (map-indexed vector (take room (drop first-i cands)))]
                (let [selected (= (+ first-i i) (:selected mb))
                      yy (- h 3 (- room 1 i))]
                  (add! (span yy 0 menu-fg (if selected sel menu) (if selected 1 0) (pad (str (if selected "> " "  ") label) w)))))
              (add! (span (- h 1) 0 nil nil 0 (pad p w)))
              [:at (min (dec w) (count p)) (dec h)])
            (seq (:prefix m))
            (let [conts (model/continuations m)
                  room (min ph (count conts))
                  ;; Which-key in columns, as Herdr's and Emacs's show it.
                  cw (min w (+ 4 (apply max 10 (map (fn [[k t]] (+ (count k) (count t) 4)) conts))))
                  ncol (max 1 (quot w cw))
                  nrow (min room (int (Math/ceil (/ (count conts) (double ncol)))))]
              (doseq [[i [k t]] (map-indexed vector (take (* nrow ncol) conts))]
                (let [r (mod i nrow) c (quot i nrow)]
                  (add! (span (- h 2 nrow (- r)) (* c cw) menu-fg menu 0 (pad (str "  " k "  " t) cw)))))
              (when (pos? nrow)
                (doseq [r (range nrow)]
                  (let [used (* cw (min ncol (int (Math/ceil (/ (- (count conts) r) (double nrow))))))]
                    (when (< used w) (add! (span (- h 2 nrow (- r)) used menu-fg menu 0 (pad "" (- w used))))))))
              (add! (span (- h 1) 0 nil nil 1 (pad (str (str/join " " (:prefix m)) "-") w)))
              [:hide])
            :else
            (do (add! (span (- h 1) 0 nil nil 0 (pad (:echo m) w)))
                (if (and focused (not (:hints m)) (not (:doc m))) [:focus focused] [:hide])))]
      {:layout (vec (for [t ts :let [[x y cw ch] (content-rect t)]] {:block (:block t) :x x :y y :w cw :h ch}))
       :spans (persistent! spans)
       :cursor cursor
       :title (str (if v (if (str/blank? (:label v)) (short (:session v)) (:label v)) "rex")
                   (let [n (count (filter #(= "blocked" (:state %)) (:agents m)))] (when (pos? n) (str " ! " n " blocked"))))})))
