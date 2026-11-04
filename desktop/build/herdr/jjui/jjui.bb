#!/usr/bin/env bb
(ns jjui
  "herdr-jjui DIR [THEN...]: open jjui on DIR in a Herdr popup. Herdr shows one
  popup at a time, so this one replaces any open popup, such as the bbh Inbox.
  When jjui quits the popup runs THEN, a command such as `bbh inbox` that
  brings back what it replaced, or closes when there is none. The popup lands
  on the active pane, the only target Herdr allows."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json])
  (:import [java.net UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]
           [java.io BufferedReader InputStreamReader OutputStreamWriter]))

(defn request [dir then]
  (when-not (fs/directory? dir)
    (throw (ex-info (str "Not a directory: " dir) {})))
  {:id "1" :method "plugin.pane.open"
   :params {:plugin_id "engstrand.jjui" :entrypoint "jjui" :placement "popup"
            :width "85%" :height "80%" :cwd (str (fs/absolutize dir)) :focus true
            :env (if (seq then) {"HERDR_JJUI_THEN" (json/generate-string then)} {})}})

(defn socket
  "Herdr's socket. Callers outside Herdr, such as the bbh-super Emacs daemon,
  lack HERDR_SOCKET_PATH, so fall back to the default path."
  []
  (or (System/getenv "HERDR_SOCKET_PATH")
      (str (fs/home) "/.config/herdr/herdr.sock")))

(defn call! [req]
  (with-open [ch (SocketChannel/open (UnixDomainSocketAddress/of (socket)))
              w (OutputStreamWriter. (Channels/newOutputStream ch) "UTF-8")
              r (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))]
    (.write w (str (json/generate-string req) "\n"))
    (.flush w)
    (let [reply (json/parse-string (or (.readLine r) (throw (ex-info "Herdr closed the socket" {}))) true)]
      (when-let [e (:error reply)] (throw (ex-info (str "Herdr " (:code e) ": " (:message e)) e)))
      (:result reply))))

(defn pane!
  "The popup's command: jjui, then THEN in the same terminal."
  []
  (p/shell {:continue true} "jjui")
  (when-let [then (System/getenv "HERDR_JJUI_THEN")]
    (apply p/exec (json/parse-string then))))

(when (= *file* (System/getProperty "babashka.file"))
  (try
    (let [[dir & then] *command-line-args*]
      (cond
        (= "--pane" dir) (pane!)
        (nil? dir) (throw (ex-info "usage: herdr-jjui DIR [THEN...]" {}))
        :else (let [req (request dir then)]
                (call! {:id "1" :method "popup.close" :params {}})
                (call! req))))
    (catch Exception e
      (binding [*out* *err*] (println "herdr-jjui:" (ex-message e)))
      (System/exit 1))))
