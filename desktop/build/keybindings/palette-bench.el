;;; palette-bench.el --- Isolated Emacs palette phase timings -*- lexical-binding: t; -*-
(require 'json)

(defun palette-bench--time (thunk)
  (let ((start (float-time)))
    (cons (funcall thunk) (* 1000 (- (float-time) start)))))

(defun palette-bench-export (directory side run)
  "Write measured phases and the exact wire payload. Run in a dedicated daemon."
  (with-current-buffer (get-buffer-create "*palette-benchmark*")
    (emacs-lisp-mode)
    (evil-normal-state)
    (let* ((eng/palette--cache nil)
           (gc-before gcs-done)
           (gc-time-before gc-elapsed)
           (cold (palette-bench--time #'eng/palette--cached))
           (gc-count (- gcs-done gc-before))
           (gc-ms (* 1000 (- gc-elapsed gc-time-before)))
           (filtering (palette-bench--time #'eng/palette--entries))
           (names (car filtering))
           (encoding (palette-bench--time (lambda () (eng/palette--request names))))
           (wire (car encoding))
           (path (expand-file-name (format "%s-%d" side run) directory)))
      (unless (and (> (length names) 1000) (member "save-buffer" names))
        (error "Benchmark did not enumerate real commands"))
      (with-temp-file (concat path ".edn") (insert wire))
      (with-temp-file (concat path ".json")
        (insert (json-serialize
                 `((side . ,side) (run . ,run) (count . ,(length names))
                   (bytes . ,(string-bytes wire)) (cold_catalogue_ms . ,(cdr cold))
                   (cold_gc_ms . ,gc-ms) (cold_gc_count . ,gc-count)
                   (gc_threshold . ,gc-cons-threshold)
                   (warm_filter_ms . ,(cdr filtering)) (encode_ms . ,(cdr encoding)))))))))

(defun palette-bench-warm ()
  (with-current-buffer "*palette-benchmark*" (eng/palette--cached)))

(defun palette-bench-open ()
  (with-current-buffer "*palette-benchmark*" (eng/palette-M-x)))

(provide 'palette-bench)
