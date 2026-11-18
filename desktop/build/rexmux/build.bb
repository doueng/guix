#!/usr/bin/env bb
;; Build rexmux in its Guix profile (manifest.scm): cargo build --release ARGS.
(require '[babashka.fs :as fs] '[babashka.process :as p])
(let [here (str (fs/parent (fs/absolutize *file*)))
      repo (str (fs/canonicalize (fs/path here ".." ".." "..")))
      profile (str here "/.guix-profile")
      zig-libc (str here "/target/zig-libc.txt")]
  (p/shell {:dir repo :out :string} "guix" "package" "-L" "modules" (str "--profile=" profile) (str "--manifest=" here "/manifest.scm"))
  (fs/create-dirs (str here "/target"))
  ;; Zig must find glibc in the profile, not guess musl (W230's spike).
  (let [crt (str (fs/parent (str/trim (:out (p/sh (str profile "/bin/gcc") "-print-file-name=crtbegin.o")))))]
    (spit zig-libc (format "include_dir=%s/include\nsys_include_dir=%s/include\ncrt_dir=%s/lib\nmsvc_lib_dir=\nkernel32_lib_dir=\ncc_dir=%s\ndarwin_sdk_dir=\n" profile profile profile crt)))
  ;; ghostty.scm patches zig-0.17 to run /tmp/zig-env for its build helpers.
  (when-not (fs/exists? "/tmp/zig-env")
    (fs/create-sym-link "/tmp/zig-env" (str profile "/bin/env")))
  (apply p/exec {:dir here
                 :extra-env {"RUSTFLAGS" (str "-C link-arg=-Wl,-rpath," profile "/lib")
                             "SSL_CERT_DIR" (str profile "/etc/ssl/certs")
                             "SSL_CERT_FILE" (str profile "/etc/ssl/certs/ca-certificates.crt")
                             "ZIG_LIBC" zig-libc
                             "CARGO_NET_GIT_FETCH_WITH_CLI" "true"}}
         "guix" "shell" "-p" profile "--" "cargo"
         (concat (or (seq *command-line-args*) ["build" "--release"])
                 ["--manifest-path" (str here "/Cargo.toml")])))
