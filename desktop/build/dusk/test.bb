#!/usr/bin/env bb
;; Unit and headless GPUI tests; never contact or alter the running shell.
(require '[babashka.fs :as fs] '[babashka.process :as p])
(let [here (str (fs/parent (fs/absolutize *file*)))
      profile (str here "/.guix-profile")
      opts {:dir here
            :extra-env {"DUSK_PROFILE" profile
                        "RUSTFLAGS" (str "-C link-arg=-Wl,-rpath," profile "/lib")
                        "SSL_CERT_DIR" (str profile "/etc/ssl/certs")
                        "CARGO_NET_GIT_FETCH_WITH_CLI" "true"}}
      in-profile #(apply p/shell opts "guix" "shell" "-p" profile "--" %&)]
  (when-not (fs/readable? (str profile "/etc/profile"))
    (binding [*out* *err*] (println "dusk: build profile missing; run make dusk first"))
    (System/exit 1))
  (in-profile "bb" "--classpath" (str here "/runtime:" here "/../../configs/dusk") (str here "/runtime_test.bb"))
  (apply in-profile "cargo" "test" "--release" "--locked" "--features" "ui-tests" *command-line-args*))
