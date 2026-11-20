(ns rexg.ws
  "A WebSocket client over a Unix socket, enough for Rex: handshake with a
  subprotocol, masked client frames, fragmented reads, ping and pong. From
  W256's spike (rexws.clj)."
  (:require [clojure.string :as str])
  (:import [java.net UnixDomainSocketAddress StandardProtocolFamily]
           [java.nio.channels SocketChannel Channels]
           [java.io DataInputStream]
           [java.util Base64]))

(defn connect [^String sock path subprotocol]
  (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)
        _ (.connect ch (UnixDomainSocketAddress/of sock))
        in (DataInputStream. (java.io.BufferedInputStream. (Channels/newInputStream ch) 65536))
        out (java.io.BufferedOutputStream. (Channels/newOutputStream ch) 65536)
        key (.encodeToString (Base64/getEncoder) (byte-array (map unchecked-byte (repeatedly 16 #(rand-int 256)))))
        req (str "GET " path " HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                 "Sec-WebSocket-Key: " key "\r\nSec-WebSocket-Version: 13\r\n"
                 "Sec-WebSocket-Protocol: " subprotocol "\r\n\r\n")]
    (.write out (.getBytes req "UTF-8"))
    (.flush out)
    (let [sb (StringBuilder.)]
      (loop [] (.append sb (char (.readByte in))) (when-not (str/ends-with? (str sb) "\r\n\r\n") (recur)))
      (when-not (str/includes? (str sb) " 101 ")
        (.close ch)
        (throw (ex-info (str "websocket " path " refused") {:resp (str sb)}))))
    {:ch ch :in in :out out :lock (Object.)}))

(defn send-frame [{:keys [^java.io.OutputStream out lock]} opcode ^bytes payload]
  (locking lock
    (let [n (alength payload)
          mask (byte-array (map unchecked-byte (repeatedly 4 #(rand-int 256))))
          hdr (cond (< n 126) [(bit-or 0x80 opcode) (bit-or 0x80 n)]
                    (< n 65536) [(bit-or 0x80 opcode) (bit-or 0x80 126) (bit-shift-right n 8) (bit-and n 0xff)]
                    :else (into [(bit-or 0x80 opcode) (bit-or 0x80 127)] (for [i (range 7 -1 -1)] (bit-and (bit-shift-right n (* 8 i)) 0xff))))
          masked (byte-array n)]
      (dotimes [i n] (aset masked i (unchecked-byte (bit-xor (aget payload i) (aget mask (mod i 4))))))
      (.write out (byte-array (map unchecked-byte hdr)))
      (.write out mask)
      (.write out masked)
      (.flush out))))

(defn send-text [ws ^String s] (send-frame ws 1 (.getBytes s "UTF-8")))
(defn send-binary [ws ^bytes b] (send-frame ws 2 b))

(defn- read-raw [{:keys [^DataInputStream in]}]
  (let [b0 (.readUnsignedByte in) b1 (.readUnsignedByte in)
        len (bit-and b1 0x7f)
        len (case len 126 (.readUnsignedShort in) 127 (.readLong in) len)
        buf (byte-array len)]
    (.readFully in buf)
    [(bit-and b0 0x80) (bit-and b0 0x0f) buf]))

(defn read-msg
  "The next message as [:text s], [:binary bytes] or [:close bytes]."
  [ws]
  (loop [op nil acc (java.io.ByteArrayOutputStream.)]
    (let [[fin opcode buf] (read-raw ws)]
      (case (int opcode)
        9 (do (send-frame ws 10 buf) (recur op acc))
        10 (recur op acc)
        8 [:close buf]
        (let [op (if (zero? opcode) op opcode)]
          (.write acc ^bytes buf)
          (if (pos? fin)
            (if (= op 1) [:text (String. (.toByteArray acc) "UTF-8")] [:binary (.toByteArray acc)])
            (recur op acc)))))))

(defn close! [{:keys [^SocketChannel ch] :as ws}]
  (try (send-frame ws 8 (byte-array [3 (unchecked-byte 0xe8)])) (catch Exception _))
  (try (.close ch) (catch Exception _)))
