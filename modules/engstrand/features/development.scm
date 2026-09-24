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
              '("clojure"
                "clojure-tools"
                "emacs-clojure-mode"
                "emacs-cider"
                "python"
                "python-pyopenssl"
                "python-pyyaml"
                "node"
                "make"
                "gcc-toolchain"
                "tree-sitter-cli"
                "pkg-config"
                "cmake"
                "dasel"
                "file"
                "fennel"
                "fnlfmt"
                "go"
                "gopls"
                "gore"
                "hyperfine"
                "jless"
                "lua"
                "nixfmt"
                "pandoc"
                "shellcheck"
                "shfmt"
                "sox"
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
