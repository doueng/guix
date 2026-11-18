;; Midjourney MCP server and CLI, from the npm release, whose dist matches
;; tsc output of tag v2.0.1. It drives a dedicated, signed-in Chrome profile
;; over the DevTools protocol; see docs/midjourney-mcp.html.
(define-module (engstrand packages midjourney-mcp-cli)
  #:use-module (gnu packages node)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:)
  #:use-module (ice-9 match))

(define (npm-tarball name version)
  (string-append "https://registry.npmjs.org/" name "/-/"
                 (match (string-split name #\/)
                   ((_ base) base)
                   ((base) base))
                 "-" version ".tgz"))

(define (npm-dependency name version hash)
  (list name
        (origin
          (method url-fetch)
          (uri (npm-tarball name version))
          (sha256 (base32 hash)))))

;; The runtime closure of package-lock.json at v2.0.1.
(define %midjourney-dependencies
  (list
   (npm-dependency "fast-uri" "3.1.8"
                   "0v08mbps4fcmriw98l2ydg5w0yjww7iqzp0yab03fxva80xh7gl6")
   (npm-dependency "json-schema-traverse" "1.0.0"
                   "08cvg5wysj4r0ax2lvhx7j74l7da8w75klz5pmsc57zj5mi24ch2")
   (npm-dependency "ajv" "8.20.0"
                   "1cz7yr42yf4kb0znhwyxslqrxqcr2j5z0538zdgcrf5vjflb7w5j")
   (npm-dependency "fast-deep-equal" "3.1.3"
                   "13vvwib6za4zh7054n3fg86y127ig3jb0djqz31qsqr71yca06dh")
   (npm-dependency "@thenavidm/slipway" "0.1.17"
                   "1nkwj2akpzsp3wgjamrmi3kyvmr6a80c38rn5g3hqgbly7zqfkh0")
   (npm-dependency "require-from-string" "2.0.2"
                   "10ldp2bzb86czf47kmvirn9x2976yh6g0my7l1spg3whcm4llsfb")
   (npm-dependency "@modelcontextprotocol/server" "2.3.0"
                   "0dsdhjmxx22hxq992m74kvwawhcxrcr1ls3rr4m83k8w7aygxkb8")
   (npm-dependency "@modelcontextprotocol/core" "2.3.0"
                   "0babqrlcvfyizznss1fm130kwinc8gi1qq9clhyqhh4skhhslvsl")
   (npm-dependency "zod" "4.6.5"
                   "1z1n1fsh2lrmmqpf7lm81p5r1qq6mi1sqnf2mz2c23g37m9hr357")))

;; Chrome is a Flatpak here, and the server takes one executable to launch it.
;; The wrappers move DevTools off Chrome's usual 9222 so the server never
;; attaches to another Chrome, and the CLI and the MCP server share the port.
(define midjourney-chrome
  (program-file "midjourney-chrome"
    #~(apply execl "/run/current-system/profile/bin/flatpak" "flatpak" "run"
             (string-append "--filesystem=" (getenv "HOME") "/.midjourney-mcp:create")
             "com.google.Chrome" (cdr (command-line)))))

(define-public midjourney-mcp-cli
  (package
    (name "midjourney-mcp-cli")
    (version "2.0.1")
    (source
     (origin
       (method url-fetch)
       (uri (npm-tarball "@thenavidm/midjourney-mcp-cli" version))
       (sha256 (base32 "1xa1p5vfm09wh7h9255kzmwiimgy765dv8d6bikm3jfgvvwrnhl4"))))
    (build-system copy-build-system)
    (arguments
     (list
      #:install-plan #~'(("." "lib/midjourney-mcp-cli"))
      #:phases
      #~(modify-phases %standard-phases
          (add-after 'install 'install-dependencies
            (lambda _
              (for-each
               (lambda (dependency)
                 (let ((dir (string-append #$output "/lib/midjourney-mcp-cli/node_modules/"
                                           (car dependency))))
                   (mkdir-p dir)
                   (invoke "tar" "xzf" (cadr dependency) "--strip-components=1" "-C" dir)))
               (list #$@(map (match-lambda
                               ((name origin) #~(list #$name #$origin)))
                             %midjourney-dependencies)))))
          (add-after 'install-dependencies 'wrap
            (lambda _
              (let ((bin (string-append #$output "/bin"))
                    (entry (string-append #$output "/lib/midjourney-mcp-cli/dist/index.js")))
                (mkdir-p bin)
                (for-each
                 (lambda (command)
                   (let ((wrapper (string-append bin "/" command)))
                     (call-with-output-file wrapper
                       (lambda (port)
                         (format port "#!/bin/sh
export MIDJOURNEY_CHROME_PATH=\"${MIDJOURNEY_CHROME_PATH:-~a}\"
export MIDJOURNEY_CDP_URL=\"${MIDJOURNEY_CDP_URL:-http://127.0.0.1:9339}\"
exec ~a ~a \"$@\"~%"
                                 #$midjourney-chrome
                                 #$(file-append node "/bin/node")
                                 entry)))
                     (chmod wrapper #o555)
                     (patch-shebang wrapper)))
                 '("midjourney-mcp" "midjourney-cli"))))))))
    (synopsis "Midjourney MCP server and CLI that drive a signed-in Chrome")
    (description "An MCP server and command line tool for Midjourney.  Midjourney has
no API, so it issues the web app's own requests from inside a dedicated Chrome
profile that the user signs into once.")
    (home-page "https://github.com/thenavidm/midjourney-mcp-cli")
    (license license:expat)))
