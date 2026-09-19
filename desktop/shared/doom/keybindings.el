;;; keybindings.el --- Clojure feature keybindings -*- lexical-binding: t; -*-

(after! clojure-mode
  (map! :map clojure-mode-map
        :localleader
        :desc "Start REPL" "r J" #'eng/clojure-start-repl
        (:prefix ("b" . "Babashka/Nix")
         :desc "Generate Nix" "g" #'eng/bb-generate-nix
         :desc "Check generated Nix" "c" #'eng/bb-check-generated
         :desc "Validate DSL" "v" #'eng/bb-validate
         :desc "Run DSL tests" "t" #'eng/bb-test))

  (when (boundp 'clojure-ts-mode-map)
    (map! :map clojure-ts-mode-map
          :localleader
          :desc "Start REPL" "r J" #'eng/clojure-start-repl
          (:prefix ("b" . "Babashka/Nix")
           :desc "Generate Nix" "g" #'eng/bb-generate-nix
           :desc "Check generated Nix" "c" #'eng/bb-check-generated
           :desc "Validate DSL" "v" #'eng/bb-validate
           :desc "Run DSL tests" "t" #'eng/bb-test))))
