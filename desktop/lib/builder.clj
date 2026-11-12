(ns builder
  "Where the remote builder is and how scripts reach it over ssh: its tailnet
  name, sshd port and the deploy key.  modules/engstrand/system/builder-keys.scm
  holds the same host and port for the Guix side (machines.scm and deploy)."
  (:require [clojure.string :as str]))

(def home (System/getProperty "user.home"))

(def host (or (System/getenv "BUILDER") "guix-builder.tailbf68ad.ts.net"))

;; sshd listens on 2222; 22 on the tailnet address is Tailscale SSH, for people.
(def ssh-port (or (System/getenv "BUILDER_SSH_PORT") "2222"))

;; Unattended: guile-ssh and these scripts cannot use a passphrase-protected key.
(def deploy-key (or (System/getenv "BUILDER_SSH_KEY") (str home "/.config/guix-builder/deploy_ed25519")))

(def publish (or (System/getenv "BUILDER_PUBLISH") (str "http://" host ":8080")))

(defn ssh
  "The ssh command, as a vector, that logs in to the builder as USER with KEY
  and runs COMMAND."
  [key user & command]
  (into ["ssh" "-i" key "-p" ssh-port "-o" (str "HostKeyAlias=" host)
         "-o" "BatchMode=yes" "-o" "ConnectTimeout=10" "-o" "IdentitiesOnly=yes"
         (str user "@" host)]
        command))

(defn ssh-command
  "ssh with KEY's options as one string, for GIT_SSH_COMMAND."
  [key]
  (str/join " " (butlast (ssh key "x"))))
