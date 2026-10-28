#!/usr/bin/env bb
;; The binary's RUNPATH points at .guix-profile/lib, so dlopen'ed libraries
;; (libwayland-client, libvulkan) and the Vulkan drivers come from the same
;; profile at runtime without LD_LIBRARY_PATH leaking into launched programs.
(require '[babashka.fs :as fs] '[babashka.process :as p])
(let [here (str (fs/parent (fs/absolutize *file*)))
      profile (str here "/.guix-profile")]
  (p/shell {:dir here :out :string} "guix" "package" (str "--profile=" profile) (str "--manifest=" here "/manifest.scm"))
  (apply p/exec {:dir here
                 :extra-env {"DUSK_PROFILE" profile
                             "RUSTFLAGS" (str "-C link-arg=-Wl,-rpath," profile "/lib")
                             "SSL_CERT_DIR" (str profile "/etc/ssl/certs")
                             "CARGO_NET_GIT_FETCH_WITH_CLI" "true"}}
         "guix" "shell" "-p" profile "--" "cargo" "build" "--release" *command-line-args*))
