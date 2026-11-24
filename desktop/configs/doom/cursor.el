;;; cursor.el --- No cursor in insert state, an underscore in the others -*- lexical-binding: t; -*-
;; T223. Emacs state, which is not modal, keeps Doom's colored box. On a
;; terminal evil-terminal-cursor-changer sends the shape (DECSCUSR) and this
;; file shows or hides the cursor (DECTCEM). config.el loads it, and
;; desktop/build/doom/cursor-test.el tests it.

(with-eval-after-load 'evil
  (setq evil-insert-state-cursor '(nil)
        evil-normal-state-cursor 'hbar
        evil-visual-state-cursor 'hbar
        evil-replace-state-cursor 'hbar
        evil-operator-state-cursor 'hbar
        evil-motion-state-cursor 'hbar))

(defun eng/cursor--shown-p ()
  "Whether the selected window's buffer wants a cursor."
  (and (buffer-local-value 'cursor-type (window-buffer (selected-window))) t))

(defvar eng/cursor--timer nil "The pending `eng/cursor-sync-terminal'.")

(defun eng/cursor-sync-terminal ()
  "Send DECTCEM when the terminal's cursor is not as the selected window wants."
  (setq eng/cursor--timer nil)
  (unless (display-graphic-p)
    (let* ((show (eng/cursor--shown-p))
           (want (if show 'shown 'hidden)))
      (unless (eq want (terminal-parameter nil 'eng/cursor))
        (set-terminal-parameter nil 'eng/cursor want)
        (send-string-to-terminal (if show "\e[?25h" "\e[?25l"))))))

(defun eng/cursor-sync-visibility ()
  "Show or hide the tty cursor as the selected window's `cursor-type' says.
A tty Emacs hides its cursor only while it writes glyphs, and an update ends
by showing it unless the selected window's cursor is off. So this turns the
window's cursor off or on, which keeps later updates from showing it, and sends
DECTCEM itself. Without that, an update with nothing left to write keeps a
cursor that an earlier one showed. evil's -- INSERT -- echo updates the
terminal at once, inside the command that entered insert state, and ends by
showing the cursor; in the bbh composer the redisplay after the command then
draws nothing, so o left the cursor visible until the next key (T223). It hides at once, before
anything is drawn. It shows after the redisplay, so no frame has the cursor
next to the old state's text."
  (unless (display-graphic-p)
    (let ((show (eng/cursor--shown-p)))
      (unless (eq show (internal-show-cursor-p))
        (internal-show-cursor nil show))
      (cond ((not show) (eng/cursor-sync-terminal))
            ((not eng/cursor--timer)
             ;; An idle timer: read_char runs plain timers before it redisplays.
             (setq eng/cursor--timer (run-with-idle-timer 0 nil #'eng/cursor-sync-terminal)))))))

;; At once on entering a state: evil runs its entry hook after it sets
;; `cursor-type' and before anything in the command can redisplay, such as its
;; -- INSERT -- echo. Post-command (below) is too late for that redisplay.
(with-eval-after-load 'evil
  (dolist (state '(normal insert visual replace operator motion emacs))
    (add-hook (evil-state-property state :entry-hook) #'eng/cursor-sync-visibility)))

(defvar eng/etcc--cursor-state (make-hash-table :test #'eq :weakness 'key))

(defun eng/etcc--set-cursor-when-changed (fn &rest args)
  "Call FN only when the selected frame's terminal cursor state changed,
and keep the tty cursor shown or hidden as `cursor-type'."
  (let* ((frame (selected-frame))
         (blink (and etcc-use-blink (bound-and-true-p blink-cursor-mode)))
         (color (and etcc-use-color (frame-parameter frame 'cursor-color)))
         (cached (gethash frame eng/etcc--cursor-state)))
    (unless (and cached
                 (equal cursor-type (nth 0 cached))
                 (eq blink (nth 1 cached))
                 (equal color (nth 2 cached)))
      (puthash frame (list (copy-tree cursor-type) blink color)
               eng/etcc--cursor-state)
      (apply fn args)))
  (eng/cursor-sync-visibility))

(with-eval-after-load 'evil-terminal-cursor-changer
  ;; Rex panes say TERM_PROGRAM=rex, which etcc does not know, and an
  ;; undetected terminal gets no shape at all. Every terminal here (Ghostty,
  ;; Herdr, Rex) takes DECSCUSR.
  (setq etcc-term-type-override 'ghostty)
  (advice-remove #'etcc--set-cursor #'eng/etcc--set-cursor-when-changed)
  (clrhash eng/etcc--cursor-state)
  (advice-add #'etcc--set-cursor :around #'eng/etcc--set-cursor-when-changed)
  (etcc--set-cursor))
