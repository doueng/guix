(ns dusk.ui
  "Pure constructors for the UI tree. Every element is a map
  {:type kw :props {...} :children [...]}; the first argument may be a props
  map, the rest are children (strings, elements, seqs of either, nils).

  Surfaces: layer. Layout: row column box stack spacer. Elements: text image
  icon button input progress. Style props are the keys handled in
  src/style.rs (apply).")

(defonce ^:private defaults (atom {}))

(defn defaults!
  "Props merged into every layer, e.g. {:scale 1.8 :font-family \"sans-serif\"}."
  [m]
  (swap! defaults merge m))

(defn element? [x]
  (and (map? x) (contains? x :type)))

(defn- flat-children [xs]
  (reduce (fn step [acc x]
            (cond
              (nil? x) acc
              (element? x) (conj acc x)
              (or (sequential? x) (seq? x)) (reduce step acc x)
              (string? x) (conj acc x)
              :else (conj acc (str x))))
          [] xs))

(defn- node [type args]
  (let [[props children] (if (and (map? (first args)) (not (element? (first args))))
                           [(first args) (rest args)]
                           [{} args])]
    {:type type :props props :children (flat-children children)}))

(defn layer
  "A layer-shell surface. Props: :id (required) :layer (:background :bottom
  :top :overlay) :anchor #{:top :bottom :left :right} :margin :width :height
  (number, :auto or :full) :exclusive-zone :keyboard (:none :exclusive
  :on-demand) :namespace :scale :on-key, plus style props for the root."
  [props & children]
  (assert (:id props) "ui/layer needs an :id")
  (node :layer (cons (merge @defaults props) children)))

(defn row [& args] (node :row args))
(defn column [& args] (node :column args))
(defn box [& args] (node :box args))
(defn stack [& args] (node :stack args))
(defn button [& args] (node :button args))
(defn text [& args] (node :text args))
(defn spacer ([] (node :spacer [])) ([props] (node :spacer [props])))

(defn image
  "(image {:src \"~/pic.jpg\" :fit :cover :width :full :height :full})"
  [props]
  (node :image [props]))

(defn icon
  "An SVG icon from the icon theme (or a path), tinted with :color."
  ([name-or-props]
   (if (map? name-or-props)
     (node :icon [name-or-props])
     (node :icon [{:name name-or-props}])))
  ([name props] (node :icon [(assoc props :name name)])))

(defn progress
  "A horizontal bar: {:value 0..1 :fill color :track color :height px}."
  [props]
  (node :progress [props]))

(defn input
  "Single-line platform text input with its own focus, cursor, selection and
  clipboard/IME support. The first input is focused on mount; click or Tab
  switches focus when the layer has multiple inputs. A single-input layer
  keeps Tab available to :on-key (e.g. launcher navigation).
  {:value s :placeholder s :on-change (fn [text]) ...style}"
  [props]
  (node :input [props]))


(defn alpha
  "Set the alpha (0..1) of a #rrggbb colour."
  [hex a]
  (let [base (subs hex 0 (min 7 (count hex)))
        byte (-> a (max 0) (min 1) (* 255) Math/round int)]
    (format "%s%02x" base byte)))
