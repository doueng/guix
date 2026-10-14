;;; init.el --- Doom Emacs module configuration -*- lexical-binding: t; -*-

(doom! :completion
       (corfu +orderless)
       vertico

       :ui
       doom
       dashboard
       doom-quit
       hl-todo
       modeline
       ophints
       (popup +defaults)
       treemacs
       (vc-gutter +pretty)
       vi-tilde-fringe
       workspaces

       :editor
       (evil +everywhere)
       format
       parinfer

       :emacs
       dired
       undo
       vc

       :term
       ghostel

       :checkers
       syntax

       :tools
       debugger
       direnv
       (eval +overlay)
       lookup
       lsp
       tree-sitter

       :os
       (:if (featurep :system 'macos) macos)
       tty

       :lang
       (clojure +lsp)
       emacs-lisp
       (go +lsp)
       json
       (javascript +lsp +tree-sitter)
       (lua +fennel)
       markdown
       (nix +lsp)
       org
       python
       (scheme +guile)
       (sh +fish +lsp)
       yaml

       :config
       (default +bindings))
