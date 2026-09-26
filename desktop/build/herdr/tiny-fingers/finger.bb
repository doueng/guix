#!/usr/bin/env bb
(ns finger
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.net UnixDomainSocketAddress]
           [java.nio.channels SocketChannel]
           [java.nio.charset StandardCharsets]
           [java.io BufferedReader InputStreamReader OutputStreamWriter]))

(def plugin-id "hotchpotch.herdr-tiny-fingers")
(def alphabet "asdfqwerzxcvjklmiuopghtybn")
(def patterns
  [{:name "url" :re #"(?:https?://|git@|git://|ssh://|ftp://|file:///)[^\s()\"']+" :wrap true}
   {:name "uuid" :re #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" :wrap true}
   {:name "sha" :re #"[0-9a-f]{7,128}" :wrap true}
   {:name "path" :re #"(?:[.\w~$@-]+)?(?:/[.\w@-]+)+/?" :wrap true}
   ;; Jujutsu change IDs use the hexadecimal alphabet k–z, unlike commit SHAs.
   {:name "jj-change" :re #"(?<![a-zA-Z0-9])[k-z]{8,32}(?![a-zA-Z0-9])"}
   {:name "jj-bookmark" :re #"\[(?<match>[^\[\]\r\n]+)\]"}])

(defn call! [method params]
  (let [address (UnixDomainSocketAddress/of (System/getenv "HERDR_SOCKET_PATH"))]
    (with-open [socket (SocketChannel/open address)
                writer (OutputStreamWriter. (java.nio.channels.Channels/newOutputStream socket) StandardCharsets/UTF_8)
                reader (BufferedReader. (InputStreamReader. (java.nio.channels.Channels/newInputStream socket) StandardCharsets/UTF_8))]
      (.write writer (str (json/generate-string {:id "1" :method method :params params}) "\n"))
      (.flush writer)
      (let [reply (json/parse-string (or (.readLine reader) (throw (ex-info "Herdr closed the socket" {:method method}))) true)]
        (when-let [error (:error reply)] (throw (ex-info (str "Herdr " (:code error) ": " (:message error)) error)))
        (when-not (= "1" (:id reply)) (throw (ex-info "Herdr response ID mismatch" reply)))
        (:result reply)))))

(defn geometry [layout pane-id]
  (let [area (:area layout)
        pane (if (:zoomed layout) area (->> (:panes layout) (filter #(= pane-id (:pane_id %))) first :rect))]
    (when-not (and area pane (pos? (:width pane)) (pos? (:height pane))
                   (<= (:x area) (:x pane)) (<= (:y area) (:y pane))
                   (<= (+ (:x pane) (:width pane)) (+ (:x area) (:width area)))
                   (<= (+ (:y pane) (:height pane)) (+ (:y area) (:height area))))
      (throw (ex-info "Invalid source pane geometry" {:pane-id pane-id})))
    {:pane_id pane-id :area area
     :pane (assoc pane :x (- (:x pane) (:x area)) :y (- (:y pane) (:y area)))}))

(defn open! [pane-id]
  (let [layout (:layout (call! "pane.layout" {:pane_id pane-id}))
        g (geometry layout pane-id)
        opened (call! "plugin.pane.open" {:plugin_id plugin-id :entrypoint "finger"
                                           :focus true
                                           :env {"HERDR_TINY_FINGERS_GEOMETRY" (json/generate-string g)}})
        overlay-id (get-in opened [:plugin_pane :pane :pane_id])]
    (when-not overlay-id (throw (ex-info "No overlay pane ID" opened)))
    ;; Herdr 0.9 initially gives overlay panes split-sized PTYs without zoom.
    (call! "pane.zoom" {:pane_id overlay-id :mode "on"})))

(defn flat-lines [lines width]
  ;; Each character carries its original screen position; join only lines which
  ;; fill the visible wrap width. Never put an artificial newline in copied text.
  (reduce (fn [{:keys [text positions] :as acc} [row line]]
            (let [join? (and (pos? row) (>= (count (nth lines (dec row))) width))
                  separator (if (or (zero? row) join?) "" "\n")
                  chars (map-indexed (fn [col _] [row col]) line)]
              (assoc acc :text (str text separator line)
                     :positions (into (cond-> positions (not (empty? separator)) (conj nil)) chars))))
          {:text "" :positions []} (map-indexed vector lines)))

(defn raw-matches [lines width selected-patterns]
  (let [joined (flat-lines lines width)]
    (for [{:keys [re wrap]} selected-patterns
          :let [sources (if wrap [joined]
                            (map-indexed (fn [row line]
                                           {:text line :positions (mapv (fn [col] [row col]) (range (count line)))}) lines))]
          {:keys [text positions]} sources
          :let [matcher (re-matcher re text)
                matches (loop [out []]
                          (if (.find matcher)
                            (let [start (.start matcher) end (.end matcher)
                                  captured (try [(.start matcher "match") (.end matcher "match")]
                                                (catch IllegalArgumentException _ [start end]))
                                  [copy-start copy-end] (if (neg? (first captured)) [start end] captured)]
                              (recur (conj out [start end copy-start copy-end])))
                            out))]
          [start end copy-start copy-end] matches
          :when (and (< start end) (some? (nth positions start nil))
                     (some? (nth positions (dec end) nil)))]
      {:start (nth positions copy-start) :end (nth positions (dec copy-end))
       :index (let [[row col] (nth positions start)]
                (+ (reduce + (map #(inc (count %)) (take row lines))) col))
       :length (- end start)
       :text (str/replace (subs text copy-start copy-end) "\n" "")})))

(defn hint [n width]
  (loop [n n w width result ""]
    (if (zero? w) result
        (recur (quot n (count alphabet)) (dec w) (str (.charAt alphabet (mod n (count alphabet))) result)))))

(defn targets [lines width selected-patterns]
  (let [hits (sort-by (juxt :index (comp - :length)) (raw-matches lines width selected-patterns))
        hits (reduce (fn [out hit]
                       (if (some #(and (<= (:index %) (:index hit))
                                       (< (:index hit) (+ (:index %) (:length %)))) out)
                         out (conj out hit))) [] hits)
        n (count hits)
        digits (loop [w 1 capacity (count alphabet)]
                 (if (>= capacity n) w (recur (inc w) (* capacity (count alphabet)))))]
    (mapv (fn [i hit] (assoc hit :hint (hint i digits))) (range n) hits)))

(defn step [{:keys [input targets] :as state} key]
  (cond
    (#{\u001b \u0003} key) (assoc state :done :cancel)
    (#{\backspace \u007f} key) (assoc state :input (subs input 0 (max 0 (dec (count input)))))
    (not (<= (int \a) (int (Character/toLowerCase ^char key)) (int \z))) state
    :else (let [input (str input (Character/toLowerCase ^char key))
                matching (filter #(str/starts-with? (:hint %) input) targets)
                exact (first (filter #(= input (:hint %)) matching))]
            (cond
              (empty? matching) (assoc state :input "")
              (and exact (= 1 (count matching))) (assoc state :done (:text exact))
              :else (assoc state :input input)))))

(defn cursor [row col] (str "\u001b[" (inc row) ";" (inc col) "H"))
(defn render [lines {:keys [pane area]} targets state]
  ;; No border, padding, or status bar: screen cells have the same coordinates as
  ;; the original pane. ANSI moves paint hints over text without shifting it.
  (let [{:keys [x y width height]} pane
        visible (take (min height (- (:height area) y)) lines)
        text (apply str (map-indexed (fn [row line]
                                       (str (cursor (+ y row) x)
                                            ;; Do not write into the wrap-pending rightmost cell.
                                            (subs line 0 (min (count line) width
                                                              (max 0 (- (:width area) x 1)))))) visible))
        hints (apply str (for [{:keys [start hint]} targets
                               :let [[row col] start]
                               :when (and (< row height) (< (+ x col (count hint)) (inc (:width area)))
                                          (or (empty? (:input state)) (str/starts-with? hint (:input state))))]
                           (str (cursor (+ y row) (+ x col))
                                "\u001b[43;30m" hint "\u001b[0m")))]
    (str "\u001b[?1049h\u001b[?25l\u001b[2J" text hints)))

(defn parse-config [text]
  {:direct-paste (boolean (re-find #"(?m)^direct_paste\s*=\s*true" text))
   :copy-toast (boolean (re-find #"(?m)^copy_toast\s*=\s*true" text))})

(defn config []
  (let [file (some-> (System/getenv "HERDR_PLUGIN_CONFIG_DIR") (str "/config.toml"))]
    (parse-config (if (and file (.exists (java.io.File. file))) (slurp file) ""))))

(defn shell! [command]
  (let [p (.start (ProcessBuilder. (into-array String ["sh" "-c" command])))]
    (when-not (zero? (.waitFor p)) (throw (ex-info "stty failed" {:command command})))
    (str/trim (slurp (.getInputStream p)))))

(defn copy! [text]
  ;; OSC 52 is handled by the host terminal, not by the shell in the source pane.
  (print (str "\u001b]52;c;" (.encodeToString (java.util.Base64/getEncoder) (.getBytes text StandardCharsets/UTF_8)) "\u0007"))
  (flush))

(defn run! []
  (let [context (json/parse-string (or (System/getenv "HERDR_PLUGIN_CONTEXT_JSON") "{}") true)
        pane-id (:focused_pane_id context)]
    (when-not (and pane-id (System/getenv "HERDR_SOCKET_PATH"))
      (throw (ex-info "Open via the Herdr plugin action (missing pane or socket)" {})))
    (if (= "open" (first *command-line-args*)) (open! pane-id)
        (let [g (json/parse-string (or (System/getenv "HERDR_TINY_FINGERS_GEOMETRY")
                                       (throw (ex-info "Missing overlay geometry; open via the plugin action" {}))) true)
              pane-id (:pane_id g)
              text (get-in (call! "pane.read" {:pane_id pane-id :source "visible" :format "text" :strip_ansi true}) [:read :text])
              lines (str/split (or text "") #"\n" -1)
              width (max 1 (dec (get-in g [:pane :width])))
              config (config)
              ts (targets lines width patterns)
              tty-state (shell! "stty -g < /dev/tty")]
          (try
            (shell! "stty raw -echo < /dev/tty")
            (loop [state {:input "" :targets ts}]
              (print (render lines g ts state)) (flush)
              (let [key (char (.read System/in))
                    next-state (step state key)]
                (if (:done next-state)
                  (when-not (= :cancel (:done next-state))
                    (print "\u001b[?1049l\u001b[?25h") (flush)
                    (let [value (:done next-state)]
                      (if (:direct-paste config)
                        (call! "pane.send_text" {:pane_id pane-id :text value})
                        (do (copy! value)
                            (when (:copy-toast config)
                              (try (call! "notification.show" {:title (str "Copied: " (subs value 0 (min 15 (count value))))})
                                   (catch Exception _ nil)))))))
                  (recur next-state))))
            (finally
              (print "\u001b[?1049l\u001b[?25h") (flush)
              (shell! (str "stty " tty-state " < /dev/tty"))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (try (run!) (catch Exception e
                (binding [*out* *err*] (println "tiny-fingers:" (.getMessage e)))
                (System/exit 1))))
