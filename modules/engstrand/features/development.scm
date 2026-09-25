(define-module (engstrand features development)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages babashka)
  #:use-module (engstrand packages github-cli)
  #:use-module (engstrand packages pi-coding-agent)
  #:use-module (gnu packages curl)
  #:use-module (gnu packages)
  #:export (feature-familiar-development %development-home-packages))

(define %development-home-packages
  (cons* babashka github-cli pi-coding-agent curl
         (map specification->package
              '("clojure-tools"
                "emacs-clojure-mode"
                "emacs-cider"
                "python"
                "node"
                "make"
                "gcc-toolchain"
                "tree-sitter-cli"
                "pkg-config"
                "cmake"
                "file"
                "fennel"
                "fnlfmt"
                "go"
                "gopls"
                "hyperfine"
                "lua"
                "nixfmt"
                "pandoc"
                "shellcheck"
                "shfmt"
                "typst"
                "uv"
                "xxd"
                "unzip"
                "zip"
                "tree"))))

(define (feature-familiar-development)
  (feature-package-set
   'familiar-development
   #:home-packages %development-home-packages))
