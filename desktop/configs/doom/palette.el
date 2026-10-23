;;; palette.el --- Choose M-x commands in the Dusk palette -*- lexical-binding: t; -*-

;; M-x and SPC SPC open the desktop's Dusk palette instead of the minibuffer.
;; Dusk only chooses the command. execute-extended-command still runs it, so
;; prefix arguments, argument prompts and history behave as in M-x. The global
;; palette (Alt+Space Space) calls eng/palette-M-x over this Emacs's server.

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

(defun eng/palette--entries ()
  "M-x's commands for the current buffer, the most recently used first."
  (let ((pred (or read-extended-command-predicate (lambda (_ _) t)))
        (buffer (current-buffer))
        (recent (delete-dups (copy-sequence extended-command-history)))
        names)
    (mapatoms (lambda (s) (when (and (commandp s) (funcall pred s buffer)) (push (symbol-name s) names))))
    (let ((commands (make-hash-table :test #'equal))
          (leader (eng/palette--leader-keys)))
      (dolist (n names) (puthash n t commands))
      (mapcar (lambda (name)
                (list name (eng/palette--keys (intern name) leader)))
              (append (seq-filter (lambda (n) (gethash n commands)) recent)
                      (sort (seq-remove (lambda (n) (member n recent)) names) #'string<))))))

(defun eng/palette--edn-string (s)
  (let ((print-escape-newlines t) (print-escape-control-characters t))
    (prin1-to-string (substring-no-properties s))))

(defun eng/palette--request (entries)
  (concat "{:op :request :name :palette :args {:prompt \"M-x\" :entries ["
          (mapconcat (lambda (e)
                       (pcase-let ((`(,name ,keys) e))
                         (concat "{:label " (eng/palette--edn-string name)
                                 (if keys (concat " :keys " (eng/palette--edn-string keys)) "")
                                 " :source \"Emacs\" :value " (eng/palette--edn-string name) "}")))
                     entries " ")
          "]}}\n"))

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
