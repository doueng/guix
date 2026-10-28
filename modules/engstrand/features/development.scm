(define-module (engstrand features development)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages babashka)
  #:use-module (engstrand packages bun)
  #:use-module (engstrand packages github-cli)
  #:use-module (engstrand packages jiti)
  #:use-module (engstrand packages pi-coding-agent)
  #:use-module (engstrand nixpkgs)
  #:use-module (gnu packages)
  #:export (feature-familiar-development))

(define %development-home-packages
  (cons* babashka bun github-cli jiti pi-coding-agent
         (append
          (map nixpkgs-package
               '("clojure" "curl" "dasel" "file" "fnlfmt" "gopls" "hyperfine" "nixfmt"
                 "shellcheck" "shfmt" "tree-sitter" "typst" "uv"))
          (map specification->package
              '("python"
                ;; Doom :lang python: LSP server, formatter, test runner, REPL.
                "python-lsp-server"
                "python-black"
                "python-pytest"
                "python-ipython"
                "node"
                "make"
                "gcc-toolchain"
                "pkg-config"
                "cmake"
                "fennel"
                "go"
                "lua"
                "pandoc"
                "xxd"
                "unzip"
                "zip"
                "tree")))))

(define (feature-familiar-development)
  (feature-package-set
   'familiar-development
   #:home-packages %development-home-packages))
