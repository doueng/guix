(require '[clojure.test :refer [deftest is run-tests]])
(load-file "finger.bb")

(defn matches [text]
  (finger/targets [text] 120 finger/patterns))

(defn by-text [text targets]
  (first (filter #(= text (:text %)) targets)))

(deftest only-requested-patterns
  (is (= ["url" "uuid" "sha" "path" "jj-change" "jj-bookmark"]
         (mapv :name finger/patterns)))
  (let [ts (matches "https://example.org 1234 127.0.0.1 0xFACE /tmp/file")]
    (is (by-text "https://example.org" ts))
    (is (by-text "/tmp/file" ts))
    (is (nil? (by-text "1234" ts)))
    (is (nil? (by-text "127.0.0.1" ts)))
    (is (nil? (by-text "0xFACE" ts)))))

(deftest jj-ids-and-bookmarks
  (let [ts (matches "uytnmswt [feature/hello] [main] cbb719c5")
        change (by-text "uytnmswt" ts)
        bookmark (by-text "feature/hello" ts)]
    (is (= [0 0] (:start change)))
    (is (= [0 10] (:start bookmark)))
    (is (= "main" (:text (by-text "main" ts))))
    (is (= "cbb719c5" (:text (by-text "cbb719c5" ts)))))
  (is (nil? (by-text "uytnmswt" (matches "xuytnmswtz"))))
  (is (nil? (by-text "sevenzz" (matches "sevenzz"))))
  (is (nil? (by-text "hello" (matches "[hello")))))

(deftest copy-exactly-one-hint
  (let [ts (matches "https://example.org /tmp/file")
        state {:input "" :targets ts}
        url (by-text "https://example.org" ts)
        path (by-text "/tmp/file" ts)]
    (is (= "https://example.org" (:done (reduce finger/step state (:hint url)))))
    (is (= "/tmp/file" (:done (reduce finger/step state (:hint path)))))
    (is (= state (finger/step state \tab)))
    (is (= :cancel (:done (finger/step state \u001b))))
    (is (= :cancel (:done (finger/step state \u0003))))
    (is (= "" (:input (finger/step (assoc state :input "a") \backspace))))))

(deftest matches-uuid-and-wrapped-url
  (is (by-text "550e8400-e29b-41d4-a716-446655440000"
               (matches "550e8400-e29b-41d4-a716-446655440000")))
  (is (= "https://example.org/a"
         (:text (first (finger/targets ["https://example." "org/a"] 16 finger/patterns)))))
  (is (empty? (finger/targets ["hello" "world"] 20 finger/patterns))))

(deftest geometry-is-relative-and-borderless
  (let [g (finger/geometry {:area {:x 10 :y 2 :width 90 :height 30}
                            :panes [{:pane_id "right" :rect {:x 52 :y 7 :width 48 :height 25}}]}
                           "right")
        line "visit https://example.com"
        out (finger/render [line] g [{:start [0 6] :hint "a"}] {:input ""})]
    (is (= {:x 42 :y 5 :width 48 :height 25} (:pane g)))
    (is (.contains out (str (finger/cursor 5 42) line)))
    (is (.contains out (str (finger/cursor 5 48) "\u001b[43;30ma")))
    (is (not (.contains out "┌")))
    (is (= {:x 0 :y 0 :width 90 :height 30}
           (:pane (finger/geometry {:area (:area g) :zoomed true} "right"))))))

(deftest configuration
  (let [config (finger/parse-config (str "direct_paste = true\ncopy_toast = true\n"
                                          "[[patterns]]\nname = \"extra\"\nregex = 'extra'\n"))]
    (is (= {:direct-paste true :copy-toast true} config))))

(deftest manifest-opens-through-launcher
  (let [manifest (slurp "manifest.toml")]
    (is (.contains manifest "command = [\"./bin/herdr-tiny-fingers\", \"open\"]"))
    (is (.contains manifest "command = [\"./bin/herdr-tiny-fingers\"]"))))

(deftest hint-overflow
  (is (= 2 (count (finger/hint 28 2)))))

(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
