(ns rexg.rex
  "Rex for the ghostty-rex client: the control connection (requests,
  responses and events as JSON over a WebSocket at /api/control) and each
  pane's data stream (/api/data). A port of rexmux's rex.rs and stream.rs;
  the stream's Ghostty snapshot is decoded by rexg-render, not here."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [rexg.ws :as ws])
  (:import [java.util Base64]))

(def api-revision 18)
(def api-min-revision 14)

(defn socket-path
  "The socket of a server ADDRESS: unix:///path or a bare path; nil means
  REX_SERVER, else rex/server.sock under XDG_RUNTIME_DIR."
  [address]
  (let [a (or address (System/getenv "REX_SERVER")
              (str (or (not-empty (System/getenv "XDG_RUNTIME_DIR"))
                       (str "/run/user/" (str/trim (:out (babashka.process/sh "id" "-u")))))
                   "/rex/server.sock"))]
    (str/replace a #"^unix://" "")))

(defn connect!
  "Connect to the server at SOCK as a client labelled LABEL. Calls
  (on-msg [:response id result-or-{:error ..}]), (on-msg [:event payload]) and
  (on-msg [:closed why]) from a reader thread. Returns {:ws :client-id
  :api-revision :send! (fn [method payload] id)}."
  [sock label on-msg]
  (let [c (ws/connect sock "/api/control" "rex.control.v1")
        _ (ws/send-text c (json/generate-string {:type "hello" :api_revision api-revision :api_min_revision api-min-revision
                                                  :client {:kind "app" :platform "linux" :name "ghostty-rex"
                                                           :version "0.1.0" :label label}}))
        welcome (loop [] (let [[k s] (ws/read-msg c)]
                           (case k :text (json/parse-string s true) :close (throw (ex-info "server closed" {})) (recur))))
        _ (when-not (= "welcome" (:type welcome)) (throw (ex-info (str "expected welcome, got " welcome) {})))
        ids (atom 0)]
    (future
      (try
        (loop []
          (let [[k s] (ws/read-msg c)]
            (case k
              :text (let [m (json/parse-string s true)]
                      (case (:type m)
                        "response" (on-msg [:response (:id m)
                                             (if (some? (:error m))
                                               {:error (str (get-in m [:error :message] "error") " (" (get-in m [:error :code]) ")")}
                                               {:ok (:payload m)})])
                        "event" (on-msg [:event (:payload m)])
                        nil)
                      (recur))
              :close (on-msg [:closed "server closed the connection"])
              (recur))))
        (catch Exception e (on-msg [:closed (str (ex-message e))]))))
    {:ws c
     :client-id (get-in welcome [:client :id] "")
     :api-revision (:api_revision welcome)
     :send! (fn [method payload]
              (let [id (str (swap! ids inc))]
                (ws/send-text c (json/generate-string {:type "request" :id id :method method :payload payload}))
                id))}))

;; ---- data streams ----

(def ^:private latin1 java.nio.charset.StandardCharsets/ISO_8859_1)

(defn splitter
  "A splitter for Rex's APC messages (ESC _ rex1;NONCE;JSON ESC \\) in a pane's
  bytes, across chunks: (feed! s bytes) returns the chunk's parts in stream
  order, each VT bytes or a message."
  [nonce]
  {:marker (str "\u001b_rex1;" nonce ";") :pending (atom "")})

(defn- partial-suffix [^String hay ^String marker]
  (or (first (for [k (range (min (dec (count marker)) (count hay)) 0 -1)
                   :when (str/ends-with? hay (subs marker 0 k))] k))
      0))

(defn feed!
  [{:keys [^String marker pending]} ^bytes input]
  (let [vt (fn [parts ^String t] (cond-> parts (seq t) (conj (.getBytes t latin1))))]
    (loop [s (str @pending (String. input latin1)) parts []]
      (let [i (str/index-of s marker)]
        (if i
          (let [body (+ i (count marker))
                j (str/index-of s "\u001b\\" body)]
            (if j
              (let [m (try (json/parse-string (String. (.getBytes (subs s body j) latin1) "UTF-8") true) (catch Exception _ nil))]
                (recur (subs s (+ j 2)) (cond-> (vt parts (subs s 0 i)) m (conj m))))
              (do (reset! pending (subs s i)) (vt parts (subs s 0 i)))))
          (let [cut (- (count s) (partial-suffix s marker))]
            (reset! pending (subs s cut))
            (vt parts (subs s 0 cut))))))))

(defn stream-out
  "Where pane BLOCK's reader thread sends PART, one of feed!'s parts, so
  that rexg-render gets the pane's bytes and sizes in stream order:
  [\\d bytes] the VT as a 'd' frame's payload, [\\r text] a resize as an 'r'
  frame's, [:msg m] any other message of Rex's for the main queue."
  [block part]
  (cond (not (map? part))
        (let [^bytes b part
              prefix (.getBytes (str block "\u0000") "UTF-8")
              out (byte-array (+ (alength prefix) (alength b)))]
          (System/arraycopy prefix 0 out 0 (alength prefix))
          (System/arraycopy b 0 out (alength prefix) (alength b))
          [\d out])
        (= "resize" (:type part)) [\r (str block " " (:cols part 80) " " (:rows part 24))]
        :else [:msg part]))

(defn open-stream!
  "Open the data stream for TICKET (from data.connect) on SOCK. Calls (on-data
  bytes) with the snapshot and the VT that follows, (on-msg m) for Rex's own
  messages, both from one reader thread in stream order, and (on-close why)
  once. Returns a function that closes it."
  [sock ticket on-data on-msg on-close]
  (let [raw (.decode (Base64/getUrlDecoder) ^String ticket)
        d (ws/connect sock "/api/data" "rex.data.terminal.v1")
        stopped (atom false)]
    (ws/send-binary d (byte-array (cons (byte 1) (seq raw))))
    (future
      (let [why (try
                  (let [welcome (loop [] (let [[k b] (ws/read-msg d)]
                                           (case k :binary b :close (throw (ex-info "closed before welcome" {})) (recur))))]
                    (when (or (not= 0x81 (bit-and 0xff (aget ^bytes welcome 0))) (< (alength ^bytes welcome) 32))
                      (throw (ex-info (str "data handshake rejected") {})))
                    (let [nonce (.getLong (doto (java.nio.ByteBuffer/wrap welcome 24 8) (.order java.nio.ByteOrder/LITTLE_ENDIAN)))
                          sp (splitter (Long/toUnsignedString nonce))]
                      (loop []
                        (let [[k b] (ws/read-msg d)]
                          (case k
                            :binary (do (doseq [part (feed! sp b)]
                                          (if (map? part) (on-msg part) (on-data part)))
                                        (recur))
                            :close "stream closed"
                            (recur))))))
                  (catch Exception e (if @stopped "stopped" (str (ex-message e)))))]
        (on-close why)))
    (fn [] (reset! stopped true) (ws/close! d))))
