(define-module (engstrand features development)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages babashka)
  #:use-module (engstrand packages bun)
  #:use-module (engstrand packages github-cli)
  #:use-module (engstrand packages pi-coding-agent)
  #:use-module (engstrand packages dasel)
  #:use-module (engstrand packages hyperfine)
  #:use-module (engstrand packages shellcheck)
  #:use-module (engstrand packages shfmt)
  #:use-module (engstrand packages tree-sitter)
  #:use-module (engstrand packages ty)
  #:use-module (engstrand packages typst)
  #:use-module (engstrand packages uv)
  #:use-module (gnu packages)
  #:export (feature-familiar-development))

(define %development-home-packages
  (cons* babashka bun github-cli pi-coding-agent
         dasel hyperfine shellcheck shfmt tree-sitter ty typst uv
         (map specification->package
              '("clojure-tools"
                "curl"
                "file"
                "fnlfmt"
                "gopls"
                "nixfmt"
                "python"
                "python-lsp-server"
                "python-black"
                "python-pytest"
                "node"
                "make"
                "gcc-toolchain"
                "pkg-config"
                "cmake"
                "fennel"
                "go"
                "hetznercloud-cli"
                "lua"
                "pandoc"
                ;; libsqlite3 for bbh super, loaded through babashka's FFI.
                "sqlite"
                "tla2tools"
                "xxd"
                "unzip"
                "zip"
                "tree"))))

(define (feature-familiar-development)
  (feature-package-set
   'familiar-development
   #:home-packages %development-home-packages))
