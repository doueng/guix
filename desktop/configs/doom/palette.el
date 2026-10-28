;;; palette.el --- Choose M-x commands in the Dusk palette -*- lexical-binding: t; -*-

;; M-x and SPC SPC open the desktop's Dusk palette instead of the minibuffer.
;; Dusk only chooses the command. execute-extended-command still runs it, so
;; prefix arguments, argument prompts and history behave as in M-x. The global
;; palette (Cmd+Space Space) calls eng/palette-M-x over this Emacs's server.

(require 'server)

(defun eng/palette--socket ()
  (expand-file-name (format "dusk-%s.sock" (or (getenv "WAYLAND_DISPLAY") "wayland-0"))
                    (or (getenv "XDG_RUNTIME_DIR") temporary-file-directory)))

(defun eng/palette--command (binding)
  "The command or keymap behind BINDING, through which-key labels, menu items
  and eng/keylog-command wrappers."
  (cond ((and (consp binding) (stringp (car binding))) (eng/palette--command (cdr binding)))
        ((eq (car-safe binding) 'menu-item) (eng/palette--command (nth 2 binding)))
        ((and (boundp 'eng/keylog-commands) (gethash binding eng/keylog-commands)))
        (t binding)))

(defun eng/palette--leader-keys ()
  "Map each command in Doom's leader map to its keys, such as \"SPC f s\".
Asking where-is-internal for every key of every command took 2.4 s."
  (let ((table (make-hash-table :test #'eq)))
    (when (boundp 'doom-leader-map)
      (named-let walk ((map doom-leader-map) (prefix "SPC"))
        (map-keymap (lambda (event binding)
                      (let ((keys (concat prefix " " (single-key-description event)))
                            (binding (eng/palette--command binding)))
                        (cond ((keymapp binding) (walk binding keys))
                              ((and (symbolp binding) (commandp binding) (not (gethash binding table)))
                               (puthash binding keys table)))))
                    (keymap-canonicalize map))))
    table))

(defun eng/palette--keys (command leader)
  "COMMAND's leader keys in LEADER, or else its first key."
  (or (gethash command leader)
      (when-let* ((key (where-is-internal command nil t)))
        (key-description key))))

(defun eng/palette--edn-string (s)
  (let ((print-escape-newlines t) (print-escape-control-characters t))
    (prin1-to-string (substring-no-properties s))))

(defun eng/palette--fragment (name keys)
  "One palette entry in EDN for the command NAME with KEYS."
  (let ((label (eng/palette--edn-string name)))
    (concat "[" label (if keys (concat " " (eng/palette--edn-string keys)) "") "]")))

;; Key lookups and escaping are cached per buffer because bindings are local.
;; Applicability and history are evaluated on every opening.
(cl-defstruct eng/palette--catalog generation maps names fragments)
(defvar-local eng/palette--cache nil)
(defvar eng/palette--generation 0)

(defun eng/palette--build ()
  (let ((leader (eng/palette--leader-keys))
        (fragments (make-hash-table :test #'equal :size 8000))
        names)
    (mapatoms (lambda (s) (when (commandp s) (push (symbol-name s) names))))
    (dolist (name names)
      (puthash name (eng/palette--fragment name (eng/palette--keys (intern name) leader)) fragments))
    (make-eng/palette--catalog :generation eng/palette--generation
                               :maps (current-active-maps t)
                               :names (sort names #'string<) :fragments fragments)))

(defun eng/palette--cached ()
  (unless (and eng/palette--cache
               (= (eng/palette--catalog-generation eng/palette--cache) eng/palette--generation)
               (equal (eng/palette--catalog-maps eng/palette--cache) (current-active-maps t)))
    (setq eng/palette--cache (eng/palette--build)))
  eng/palette--cache)

(defvar eng/palette--warm-timer nil)

(defun eng/palette--flush (&rest _)
  "Forget the cache and rebuild it the next time Emacs is idle."
  (cl-incf eng/palette--generation)
  (unless (timerp eng/palette--warm-timer)
    (setq eng/palette--warm-timer
          (run-with-idle-timer 2 nil (lambda () (setq eng/palette--warm-timer nil) (eng/palette--cached))))))

(add-hook 'after-load-functions #'eng/palette--flush)

(defun eng/palette--entries ()
  "M-x's command names for the current buffer, the most recently used first."
  (let* ((pred (or read-extended-command-predicate (lambda (_ _) t)))
         (buffer (current-buffer))
         (fragments (eng/palette--catalog-fragments (eng/palette--cached)))
         (keep (lambda (n) (let ((s (intern-soft n))) (and s (commandp s) (funcall pred s buffer)))))
         (recent (seq-filter (lambda (n) (and (gethash n fragments) (funcall keep n)))
                             (delete-dups (copy-sequence extended-command-history))))
         (seen (make-hash-table :test #'equal)))
    (dolist (n recent) (puthash n t seen))
    (append recent
            (seq-filter (lambda (n) (and (not (gethash n seen)) (funcall keep n)))
                        (eng/palette--catalog-names (eng/palette--cached))))))

(defun eng/palette--request (names)
  (let ((fragments (eng/palette--catalog-fragments (eng/palette--cached))))
    (concat "{:op :request :name :palette :args {:prompt \"M-x\" :source \"Emacs\" :entries ["
            (mapconcat (lambda (n) (or (gethash n fragments) (eng/palette--fragment n nil))) names " ")
            "]}}\n")))

(defun eng/palette--choice (reply)
  "The chosen value in Dusk's REPLY, or nil when the palette was cancelled."
  (when (string-match ":value \"\\(\\(?:[^\"\\\\]\\|\\\\.\\)*\\)\"" reply)
    (read (concat "\"" (match-string 1 reply) "\""))))

(defun eng/palette--run (window prefix name)
  (when (window-live-p window) (select-window window))
  (add-to-history 'extended-command-history name)
  (let ((current-prefix-arg prefix))
    (execute-extended-command prefix name name)))

(defun eng/palette-M-x (&optional prefix)
  "Choose a command in the Dusk palette and run it like M-x.
Falls back to M-x when Dusk is not running."
  (interactive "P")
  (let ((window (selected-window))
        (socket (eng/palette--socket))
        (reply ""))
    (if (not (file-exists-p socket))
        (call-interactively #'execute-extended-command)
      (let ((process (make-network-process
                      :name "dusk-palette" :family 'local :service socket :noquery t
                      :coding 'utf-8
                      :filter (lambda (_ chunk) (setq reply (concat reply chunk)))
                      :sentinel (lambda (_ _)
                                  (when-let* ((name (eng/palette--choice reply)))
                                    (run-at-time 0 nil #'eng/palette--run window prefix name))))))
        (process-send-string process (eng/palette--request (eng/palette--entries)))))))

(global-set-key [remap execute-extended-command] #'eng/palette-M-x)

;; The global palette finds this Emacs by its process id.
(setq server-name (format "eng-%d" (emacs-pid)))
(unless (or noninteractive (daemonp) (server-running-p))
  (server-start))

(provide 'palette)
