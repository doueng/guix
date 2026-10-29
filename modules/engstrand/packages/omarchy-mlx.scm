;; The Parakeet speech-to-text runtime from omarchy-mlx: MLX's Vulkan backend
;; for the mel frontend and decoder, and the ANE worker for the encoder.
(define-module (engstrand packages omarchy-mlx)
  #:use-module (engstrand nixpkgs)
  #:use-module (gnu packages base)
  #:use-module (gnu packages bash)
  #:use-module (gnu packages compression)
  #:use-module (gnu packages elf)
  #:use-module (gnu packages gcc)
  #:use-module (gnu packages vulkan)
  #:use-module (guix build-system trivial)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:)
  #:export (omarchy-mlx-parakeet))

(define omarchy-mlx-wheel
  (origin
    (method url-fetch)
    (uri "https://github.com/joshuaswarren/omarchy-mlx/releases/download/v0.7.34/omarchy-mlx-wheel-cp314-aarch64.whl")
    (file-name "mlx_omarchy-0.32.4-cp314-cp314-linux_aarch64.whl")
    (sha256 (base32 "1p0jny28fc6r3n28k05klzmdy09jlxmgv4c538f4ca1fmlpd3dlq"))))

;; The CLI's runtime imports, as the wheels uv resolved for CPython 3.14.
(define python-wheels
  (list
   (origin (method url-fetch)
           (uri "https://files.pythonhosted.org/packages/67/b8/b42132ca113dc567d37684437b46ca1dafc885902b02a110a02d5b511857/cffi-2.1.1-cp314-cp314-manylinux2014_aarch64.manylinux_2_17_aarch64.whl")
           (file-name "cffi-2.1.1-cp314-cp314-manylinux2014_aarch64.manylinux_2_17_aarch64.whl")
           (sha256 (base32 "1hg5gz8jjp1jyhiklc53y8g528fg2fxqr21gl5g6qpi9ismvib2q")))
   (origin (method url-fetch)
           (uri "https://files.pythonhosted.org/packages/63/d6/34b0a2b0741386a63025a65a2c09caaaaaad6d0ca95b66cd65c30dd7fcb5/numpy-2.5.4-cp314-cp314-manylinux_2_27_aarch64.manylinux_2_28_aarch64.whl")
           (file-name "numpy-2.5.4-cp314-cp314-manylinux_2_27_aarch64.manylinux_2_28_aarch64.whl")
           (sha256 (base32 "05w6d8jrkqb1mpymfb3nq30qxdpg1fyg7wakc1wmi1nd0hv1fm20")))
   (origin (method url-fetch)
           (uri "https://files.pythonhosted.org/packages/e4/04/d52c7016b04b6c5108f26691f9d33ec82a9b65d041f1a9c771137693d618/protobuf-7.36.2-py3-none-any.whl")
           (file-name "protobuf-7.36.2-py3-none-any.whl")
           (sha256 (base32 "07lz0g6ndj29fm18hh7jchfrik5hpq4fa60zrvk5ifcdsi2s7cxx")))
   (origin (method url-fetch)
           (uri "https://files.pythonhosted.org/packages/b6/ae/315bbeee126a769cbde43cf4908acb4b40408d8bf8bbf334dd7368d8dadf/pycparser-3.12-py3-none-any.whl")
           (file-name "pycparser-3.12-py3-none-any.whl")
           (sha256 (base32 "0pn8lkfzqmswxia42a9fxidl8ir4wjpisfjn2gnl76xki7sg6xm0")))
   (origin (method url-fetch)
           (uri "https://files.pythonhosted.org/packages/4a/f8/fc39fad6f879633461d27394cd1ddaf1f769ffa0597dca35872f51b16461/soundfile-0.14.0-py2.py3-none-manylinux_2_28_aarch64.whl")
           (file-name "soundfile-0.14.0-py2.py3-none-manylinux_2_28_aarch64.whl")
           (sha256 (base32 "0xzkj97gqb4xbs3xmm5gf0xjlfzm4pvxvd60ca06x6n91flj8mz8")))
   (origin (method url-fetch)
           (uri "https://files.pythonhosted.org/packages/49/d3/b8441a820a491ddfc024b0b0cf0393375b75ea13866d9c66727e54c2fc80/typing_extensions-4.16.0-py3-none-any.whl")
           (file-name "typing_extensions-4.16.0-py3-none-any.whl")
           (sha256 (base32 "1s62f1iqvmshyfrzx76f752pmxpijx7a3bbnn70i7s3l2d4al728")))))

;; The wheel is built on Arch and needs GLIBC_2.43, newer than Guix's glibc.
(define omarchy-mlx-parakeet
  (let ((python (nixpkgs-output "python314")))
    (package
      (name "omarchy-mlx-parakeet")
      (version "0.7.34")
      (source #f)
      (build-system trivial-build-system)
      (arguments
       (list
        #:modules '((guix build utils))
        #:builder
        #~(begin
            (use-modules (guix build utils) (ice-9 popen) (ice-9 textual-ports))
            (define (patchelf . args)
              (let* ((port (apply open-pipe* OPEN_READ "patchelf" args))
                     (text (get-string-all port)))
                (unless (zero? (status:exit-val (close-pipe port)))
                  (error "patchelf failed" args))
                (string-trim-both text)))
            (setenv "PATH" (string-append #$unzip "/bin:" #$patchelf "/bin:" #$coreutils "/bin"))
            (let* ((site (string-append #$output "/lib/python3.14/site-packages"))
                   (mlx-bin (string-append site "/mlx/bin"))
                   ;; nixpkgs Python's loader is its glibc 2.44.
                   (ld (patchelf "--print-interpreter"
                             (string-append #$python "/bin/python3.14")))
                   (libs (string-join
                          (list (dirname ld)
                                (string-append #$(file-append (gexp-input gcc "lib")) "/lib")
                                (string-append #$(file-append (gexp-input gfortran "lib")) "/lib")
                                (string-append #$zlib "/lib")
                                (string-append #$vulkan-loader "/lib"))
                          ":"))
                   (seal-protected? (lambda (file) (string-contains file "/share/mlx-omarchy/"))))
              (mkdir-p site)
              (for-each (lambda (wheel) (invoke "unzip" "-q" "-o" wheel "-d" site))
                        (list #$omarchy-mlx-wheel #$@python-wheels))
              (for-each
               (lambda (file)
                 (unless (or (symbolic-link? file) (seal-protected? file) (not (elf-file? file)))
                   (chmod file #o755)
                   (let ((old (patchelf "--print-rpath" file)))
                     (patchelf "--set-rpath"
                             (if (string-null? old) libs (string-append old ":" libs))
                             file))
                   (when (string-prefix? mlx-bin file)
                     (patchelf "--set-interpreter" ld file))))
               (find-files site))
              (mkdir-p (string-append #$output "/bin"))
              (symlink (string-append mlx-bin "/mlx-omarchy-ane-worker")
                       (string-append #$output "/bin/mlx-omarchy-ane-worker"))
              (call-with-output-file (string-append #$output "/bin/mlx-omarchy-parakeet")
                (lambda (port)
                  (format port "#!~a/bin/bash
export PYTHONPATH=~a${PYTHONPATH:+:$PYTHONPATH}
export VK_DRIVER_FILES=${VK_DRIVER_FILES:-/run/opengl-driver/share/vulkan/icd.d/asahi_icd.aarch64.json}
export PATH=~a/bin${PATH:+:$PATH}
exec ~a/bin/python3.14 ~a/mlx-omarchy-parakeet \"$@\"~%"
                          #$bash-minimal site #$glslang #$python mlx-bin)))
              (chmod (string-append #$output "/bin/mlx-omarchy-parakeet") #o555)))))
      (supported-systems '("aarch64-linux"))
      (home-page "https://github.com/joshuaswarren/omarchy-mlx")
      (synopsis "Parakeet speech-to-text on the Apple Neural Engine")
      (description "The omarchy-mlx Parakeet TDT 0.6b v3 runtime: the encoder runs on
the M1 Neural Engine through @file{/dev/accel/accel0}; mel features and decoding
run on the GPU through MLX's Vulkan backend.")
      (license (list license:expat license:asl2.0 license:bsd-3)))))
