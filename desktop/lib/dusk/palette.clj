(ns dusk.palette
  "Ask the running Dusk shell to choose a palette entry, and remember choices."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(defn socket-path []
  (str (or (System/getenv "XDG_RUNTIME_DIR") (System/getProperty "java.io.tmpdir"))
       "/dusk-" (or (System/getenv "WAYLAND_DISPLAY") "wayland-0") ".sock"))

(defn request!
  "Send REQ, an EDN map, as one line on Dusk's socket and return the reply map."
  [req]
  (with-open [ch (java.nio.channels.SocketChannel/open (java.net.UnixDomainSocketAddress/of (socket-path)))]
    (let [out (java.nio.channels.Channels/newOutputStream ch)
          in (java.io.BufferedReader. (java.io.InputStreamReader. (java.nio.channels.Channels/newInputStream ch) "UTF-8"))]
      (.write out (.getBytes (str (pr-str req) "\n") "UTF-8"))
      (.flush out)
      (edn/read-string (.readLine in)))))

(defn pick!
  "Show ENTRIES, maps of :label :keys :source :value, and return the chosen
  :value, or nil when cancelled."
  [prompt entries]
  (let [reply (request! {:op :request :name :palette
                         :args {:prompt prompt :entries (mapv #(select-keys % [:label :keys :source :value]) entries)}})]
    (when-not (:ok reply)
      (throw (ex-info (str "Dusk palette failed: " (:error reply)) reply)))
    (:value reply)))

;; History

(defn history-path [context]
  (str (or (System/getenv "XDG_STATE_HOME") (str (fs/home) "/.local/state")) "/palette/" (name context) ".edn"))

(defn history [context]
  (let [f (history-path context)]
    (if (fs/exists? f) (edn/read-string (slurp f)) [])))

(defn remember! [context value]
  (let [f (history-path context)]
    (fs/create-dirs (fs/parent f))
    (spit f (pr-str (vec (take 100 (distinct (cons value (history context)))))))))

(defn by-history
  "ENTRIES with the ones chosen most recently in CONTEXT first."
  [context entries]
  (let [rank (zipmap (history context) (range))]
    (sort-by #(get rank (:value %) Long/MAX_VALUE) entries)))
