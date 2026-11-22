(ns hyprkan-test
  "hyprkan reload from a shell whose HYPRLAND_INSTANCE_SIGNATURE is missing or
  names a dead Hyprland, as in make switch run from an Emacs that loaded doom
  env's snapshot (T216). A fake hyprctl answers only the live instance, and a
  fake kanata records what hyprkan sends."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

(def hyprkan (str (fs/absolutize "desktop/bin/hyprkan")))

(def fake-hyprctl
  "#!/usr/bin/env bb
(let [sig (System/getenv \"HYPRLAND_INSTANCE_SIGNATURE\")]
  (cond
    (System/getenv \"FAKE_HYPRCTL_GARBAGE\") (println \"ok, but not JSON\")
    (nil? sig) (do (println \"HYPRLAND_INSTANCE_SIGNATURE not set! (is hyprland running?)\") (System/exit 1))
    (not= \"live\" sig) (do (println (str \"Couldn't connect to \" sig \" (4)\")) (System/exit 4))
    :else (println \"{\\\"class\\\": \\\"foot\\\"}\")))
")

(defn dead-pid []
  (let [proc (p/process ["true"])]
    @proc
    (.pid (:proc proc))))

(defn instance! [runtime sig pid]
  (let [dir (fs/path runtime "hypr" sig)]
    (fs/create-dirs dir)
    (spit (str (fs/path dir "hyprland.lock")) (str pid "\nwayland-1\n"))
    (spit (str (fs/path dir ".socket.sock")) "")))

(defn fake-kanata!
  "A server on a free port that records each message and answers a reload as
  kanata does. Returns [port messages-atom]."
  []
  (let [server (java.net.ServerSocket. 0 50 (java.net.InetAddress/getLoopbackAddress))
        seen (atom [])]
    (future
      (while true
        (with-open [s (.accept server)]
          (let [line (.readLine (io/reader (.getInputStream s)))
                msg (json/parse-string line true)
                out (.getOutputStream s)]
            (swap! seen conj msg)
            (when (:ReloadFile msg)
              (.write out (.getBytes (str (json/generate-string {:ConfigFileReload {}}) "\n"
                                          (json/generate-string {:LayerChange {:new "base"}}) "\n")))
              (.flush out))))))
    [(.getLocalPort server) seen]))

(defn run-reload
  "Run hyprkan reload in a fake session; SIG nil leaves the variable unset."
  [{:keys [runtime sig port extra]}]
  (let [env (-> (into {} (System/getenv))
                (dissoc "HYPRLAND_INSTANCE_SIGNATURE")
                (merge {"XDG_RUNTIME_DIR" (str runtime)
                        "XDG_CONFIG_HOME" (str (fs/path runtime "config"))
                        "PATH" (str (fs/path runtime "bin") ":" (System/getenv "PATH"))
                        "HYPRKAN_KANATA" (str "127.0.0.1:" port)}
                       extra)
                (cond-> sig (assoc "HYPRLAND_INSTANCE_SIGNATURE" sig)))]
    (p/sh {:env env} "bb" hyprkan "reload")))

(defn session! []
  (let [runtime (fs/create-temp-dir {:prefix "hyprkan-test"})]
    (fs/create-dirs (fs/path runtime "bin"))
    (spit (str (fs/path runtime "bin" "hyprctl")) fake-hyprctl)
    (fs/set-posix-file-permissions (fs/path runtime "bin" "hyprctl") "rwx------")
    (fs/create-dirs (fs/path runtime "config" "kanata"))
    (spit (str (fs/path runtime "config" "kanata" "apps.edn")) (pr-str {"foot" "terminal"}))
    (instance! runtime "dead" (dead-pid))
    runtime))

(defn no-trace? [err] (not (re-find #"Exception|----- Error|\tat " err)))

(def reloaded [{:ReloadFile {:path "/etc/kanata/kanata.kbd"}} {:ChangeLayer {:new "terminal"}}])

(deftest finds-the-live-instance
  (let [runtime (session!)
        [port seen] (fake-kanata!)]
    (instance! runtime "live" (.pid (java.lang.ProcessHandle/current)))
    (doseq [sig [nil "dead" "gone"]]
      (testing (str "signature " (pr-str sig))
        (reset! seen [])
        (let [{:keys [exit err]} (run-reload {:runtime runtime :sig sig :port port})]
          (is (= 0 exit) err)
          (is (= "" (str/trim err)))
          (is (= reloaded @seen)))))))

(deftest warns-without-hyprland
  (let [runtime (session!)
        [port seen] (fake-kanata!)]
    (testing "no running instance"
      (let [{:keys [exit err]} (run-reload {:runtime runtime :sig "dead" :port port})]
        (is (= 0 exit))
        (is (str/includes? err "no running Hyprland"))
        (is (no-trace? err) err)
        (is (= [(first reloaded)] @seen))))
    (testing "hyprctl prints no JSON"
      (instance! runtime "live" (.pid (java.lang.ProcessHandle/current)))
      (reset! seen [])
      (let [{:keys [exit err]} (run-reload {:runtime runtime :port port :extra {"FAKE_HYPRCTL_GARBAGE" "1"}})]
        (is (= 0 exit))
        (is (str/includes? err "printed no JSON: ok, but not JSON"))
        (is (no-trace? err) err)
        (is (= [(first reloaded)] @seen))))
    (testing "kanata unreachable"
      (let [closed (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
            {:keys [exit err]} (run-reload {:runtime runtime :port closed})]
        (is (= 1 exit))
        (is (str/includes? err "cannot reload kanata"))
        (is (no-trace? err) err)))))

(let [{:keys [fail error]} (run-tests 'hyprkan-test)]
  (shutdown-agents)
  (System/exit (if (pos? (+ fail error)) 1 0)))
