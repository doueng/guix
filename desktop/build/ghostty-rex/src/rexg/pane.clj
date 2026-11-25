(ns rexg.pane
  "ghostty-rex pane: one Rex pane in the terminal it runs in, a Ghostty
  window of its own (docs/ghostty-rex-one-parser.html). Two
  parsers, not three: Rex's server and this Ghostty. The relay

  - takes the block and sizes it to this terminal, so the stream's snapshot
    is at the window's size, and again on SIGWINCH;
  - prints the snapshot once through rexg-render --vt (libghostty's
    formatter, history first), then passes the live bytes on untouched;
  - sends what Ghostty writes to its stdin to the pane, less Ghostty's
    answers to the program's questions, which Rex already gave
    (rexg.replies);
  - holds the pane's size: the main window skips a pane it popped out, and
    a resize by a client that took the block is put back within 2 s;
  - reconnects when the stream ends, and exits when the pane is gone."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [rexg.replies :as replies]
            [rexg.rex :as rex]))

(defn- log [& xs]
  (when-let [f (System/getenv "REXG_PANE_LOG")]
    (spit f (str (str/join " " xs) "\n") :append true)))

(defn- tty-size
  "[cols rows] of the terminal on stdin."
  []
  (let [{:keys [out]} (p/sh {:in :inherit} "stty" "size")
        [r c] (map parse-long (str/split (str/trim out) #" "))]
    (when (and c r) [c r])))

(defn- stty! [& args] (apply p/sh {:in :inherit} "stty" args))

(defn utf8-cut
  "How many of BYTES' first N make whole UTF-8 characters: a character cut
  at the end waits for the next read."
  [^bytes bytes n]
  (let [lead (fn [b] (let [b (bit-and 0xff b)] (cond (< b 0x80) 1 (< b 0xc0) 0 (< b 0xe0) 2 (< b 0xf0) 3 :else 4)))]
    (loop [i (dec n) back 1]
      (cond (or (neg? i) (> back 4)) n
            :else (let [l (lead (aget bytes i))]
                    (cond (zero? l) (recur (dec i) (inc back))
                          (< back l) i
                          :else n))))))

(defn prints-vt?
  "Whether RENDER has the --vt mode: an older rexg-render would read the
  stream as its own frames. With no input, --vt says the snapshot did not
  decode; an old one draws its screen and exits quietly."
  [render]
  (try (str/includes? (:err (p/sh {:in ""} render "--vt")) "rexg-render --vt")
       (catch Exception _ false)))

(defn -main [& args]
  (let [opts (apply hash-map args)
        sock (rex/socket-path (get opts "--address"))
        sid (get opts "--session")
        bid (get opts "--block")
        render (or (get opts "--render") (System/getenv "REXG_RENDER") "rexg-render")
        resp (atom {})
        conn (atom nil)
        filt (atom (replies/new-state))
        vt (atom nil)
        size (atom nil)
        now #(quot (System/nanoTime) 1000000)
        call (fn [method payload]
               (when-let [c @conn]
                 (let [id ((:send! c) method payload)]
                   (loop [i 0]
                     (if-let [r (get @resp id)]
                       (do (swap! resp dissoc id) r)
                       (when (< i 5000) (Thread/sleep 1) (recur (inc i))))))))
        connect! (fn []
                   (reset! conn (rex/connect! sock "ghostty-rex-pane"
                                              (fn [[k a b]]
                                                (case k
                                                  :response (swap! resp assoc a b)
                                                  :closed (reset! conn nil)
                                                  nil))))
                   (call "session.attach" {:session_id sid}))
        size! (fn []
                (when-let [[c r] (tty-size)]
                  (reset! size [c r])
                  (call "session.own_block" {:session_id sid :block_ids [bid] :mode "take"})
                  (call "com.superlogical.terminal.resize" {:session_id sid :block_id bid :args {:columns c :rows r}})))
        ;; Whether the session no longer has the block anywhere, in any
        ;; layer or state; a session that is gone has none.
        gone? (fn [] (let [r (call "session.view" {:session_id sid})]
                       (or (some? (:error r)) (and (:ok r) (not (str/includes? (pr-str (:ok r)) (str "\"" bid "\"")))))))
        done (atom (promise))
        stream! (fn stream! []
                  (let [ticket (get-in (call "data.connect" {:session_id sid :block_id bid :data_ref_id "terminal"
                                                              :subprotocol "rex.data.terminal.v1"})
                                       [:ok :ticket])
                        _ (when-not ticket (throw (ex-info "no ticket" {})))
                        proc (p/process [render "--vt"] {:in :stream :out :inherit :err :inherit})
                        ^java.io.OutputStream pin (:in proc)]
                    (reset! vt proc)
                    (rex/open-stream! sock ticket
                                      (fn [part]
                                        (when-not (map? part)
                                          (swap! filt replies/out part (now))
                                          (.write pin ^bytes part) (.flush pin)))
                                      (fn [_])
                                      (fn [why]
                                        (log "stream closed" why)
                                        (try (.close pin) (catch Exception _))
                                        (deliver @done why)))))]
    (when-not (prints-vt? render)
      (println (str "ghostty-rex pane: " render " has no --vt mode; make home (MUX=ghostty-rex) or make build NAME=ghostty-rex installs one."))
      (Thread/sleep 10000)
      (System/exit 1))
    (stty! "raw" "-echo")
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(do (some-> @vt p/destroy) (stty! "sane"))))
    (sun.misc.Signal/handle (sun.misc.Signal. "WINCH") (reify sun.misc.SignalHandler (handle [_ _] (future (size!)))))
    ;; The pane's size is this window's. The main window leaves a pane it
    ;; popped out alone, and a client that is not the owner cannot resize
    ;; it; one that takes the block can, so look every 2 s and put it back.
    (future
      (loop []
        (Thread/sleep 2000)
        (try (let [r (:ok (call "block.method" {:session_id sid :block_id bid :method "size"}))]
               (when (and r @size (not= @size [(:columns r) (:rows r)]))
                 (log "size from elsewhere" (:columns r) (:rows r))
                 (size!)))
             (catch Exception _))
        (recur)))
    ;; Keys: Ghostty's bytes to the pane, whole UTF-8 characters at a time.
    (future
      (let [in System/in buf (byte-array 65536)]
        (loop [have 0]
          (let [n (.read in buf have (- (alength buf) have))]
            (if (neg? n)
              (System/exit 0)
              (let [total (+ have n)
                    cut (utf8-cut buf total)
                    [st out] (replies/in @filt (java.util.Arrays/copyOfRange buf 0 cut) (now))]
                (reset! filt st)
                (when (pos? (alength ^bytes out))
                  (call "com.superlogical.terminal.write" {:session_id sid :block_id bid :args {:data (String. ^bytes out "UTF-8")}}))
                (System/arraycopy buf cut buf 0 (- total cut))
                (recur (- total cut))))))))
    ;; The stream, again after each loss until the pane is gone.
    (loop [wait 0]
      (Thread/sleep wait)
      (reset! done (promise))
      (let [ok (try (when-not @conn (connect!)) (size!) (stream!) true
                    (catch Exception e
                      (log "connect" (ex-message e))
                      (some-> @vt p/destroy)
                      (reset! conn nil)
                      false))]
        (if-not ok
          (recur (min 2000 (+ wait 250)))
          (let [why @@done]
            (some-> @vt p/destroy)
            (if (try (gone?) (catch Exception _ false))
              (do (log "pane gone" why) (System/exit 0))
              (recur 250))))))))
