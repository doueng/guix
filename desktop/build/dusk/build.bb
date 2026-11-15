#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :as p])
(let [here (str (fs/parent (fs/absolutize *file*)))
      profile (str here "/.guix-profile")]
  (p/shell {:dir here :out :string} "guix" "package" (str "--profile=" profile) (str "--manifest=" here "/manifest.scm"))
  (apply p/exec {:dir here
                 :extra-env {"DUSK_PROFILE" profile
                             "RUSTFLAGS" (str "-C link-arg=-Wl,-rpath," profile "/lib")
                             "SSL_CERT_DIR" (str profile "/etc/ssl/certs")
                             "CARGO_NET_GIT_FETCH_WITH_CLI" "true"}}
         "guix" "shell" "-p" profile "--" "cargo" "build" "--release"
         ;; exec keeps the caller's directory despite :dir, so name the manifest.
         "--manifest-path" (str here "/Cargo.toml") *command-line-args*))
