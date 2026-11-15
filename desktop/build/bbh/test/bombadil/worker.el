;;; worker.el --- the bbh Worker view for Bombadil to drive -*- lexical-binding: t; -*-

(dolist (pkg '("agent-shell" "acp" "shell-maker" "map" "org" "goto-chg" "evil" "clojure-mode"))
  (let ((dir (expand-file-name pkg (getenv "BBH_ELISP_BUILD"))))
    (when (file-directory-p dir) (add-to-list 'load-path dir))))
(defmacro after! (feature &rest body)
  `(with-eval-after-load ',feature ,@body))
;; Doom's evil settings, which must precede loading evil.
(setq evil-want-C-u-scroll t
      evil-want-C-d-scroll t
      evil-respect-visual-line-mode t
      evil-esc-delay 0)
(require 'cl-lib)
(require 'evil)
(evil-mode 1)
(require 'server)

(menu-bar-mode -1)
(setq scroll-preserve-screen-position 'always
      scroll-margin 2
      scroll-conservatively 10
      redisplay-skip-fontification-on-input nil
      fast-but-imprecise-scrolling nil
      jit-lock-defer-time nil
      recenter-redisplay nil
      baud-rate 1000000
      bidi-inhibit-bpa t
      org-startup-indented t
      inhibit-startup-screen t
      ring-bell-function #'ignore
      confirm-kill-emacs (lambda (&rest _) nil))
(setq-default bidi-display-reordering 'left-to-right
              bidi-paragraph-direction 'left-to-right)

(load (getenv "BBH_DOOM_AGENT_SHELL") nil t)

(defvar bbh-bombadil-root (getenv "BBH_BOMBADIL_ROOT"))
(defun bbh-bombadil--file (name) (expand-file-name name bbh-bombadil-root))
(defun bbh-bombadil--log (file fmt &rest args)
  (let ((inhibit-message t) (message-log-max nil))
    (write-region (concat (format-time-string "%T.%3N ") (apply #'format fmt args) "\n")
                  nil (bbh-bombadil--file file) t 'silent)))

(setq eng/bbh-inbox-bbh (list (bbh-bombadil--file "bin/bbh"))
      eng/bbh-inbox-reload-delay 0.05)

(advice-add 'message :after
            (lambda (format-string &rest args)
              (when format-string
                (bbh-bombadil--log "messages.log" "%s" (apply #'format format-string args)))))

;; C-^ is one byte, so it never arrives split like an escape sequence; evil's C-^ switches buffers, so unbind it there.
(global-set-key (kbd "C-^") #'ignore)
(dolist (map (list evil-normal-state-map evil-motion-state-map evil-insert-state-map))
  (define-key map (kbd "C-^") #'ignore))

(setq server-socket-dir (bbh-bombadil--file "emacs-server")
      server-name "bombadil")
(server-start)

(defvar bbh-bombadil--violations 0)

(defun bbh-bombadil-violation (fmt &rest args)
  "Record a broken property: checks.log, and CHECK in the status tag from now on."
  (setq bbh-bombadil--violations (1+ bbh-bombadil--violations))
  (apply #'bbh-bombadil--log "checks.log" fmt args)
  (force-mode-line-update t))

(defun bbh-bombadil--transcript-windows ()
  (seq-filter (lambda (w) (with-current-buffer (window-buffer w) (derived-mode-p 'eng/bbh-transcript-mode)))
              (window-list nil 'nomini)))

(defun bbh-bombadil--following-p (window)
  (= (window-point window) (with-current-buffer (window-buffer window) (point-max))))

(defun bbh-bombadil--tag (header)
  (let* ((shell (ignore-errors (agent-shell-viewport--shell-buffer)))
         (thread (if (buffer-live-p shell) (car (split-string (buffer-name shell))) "?"))
         (state (with-current-buffer (window-buffer (selected-window))
                  (pcase evil-state ('insert "I") ('normal "N") ('visual "V") ('motion "M") (_ "E"))))
         (transcript (and (buffer-live-p shell) (buffer-local-value 'eng/bbh-transcript shell)))
         (window (and (buffer-live-p transcript) (get-buffer-window transcript)))
         (follow (cond ((not window) "-") ((bbh-bombadil--following-p window) "F") (t "S")))
         (top (and window (= (window-start window) (with-current-buffer transcript (point-min))))))
    (concat " [" thread " " state " " follow (if top " TOP" "") (if (> bbh-bombadil--violations 0) " CHECK" "") "]" header)))

(advice-add 'eng/bbh--header :filter-return #'bbh-bombadil--tag)

(defvar bbh-bombadil-step-ms (string-to-number (or (getenv "BBH_BOMBADIL_STEP_MS") "1000"))
  "A command and its redisplay that take longer break the step budget.")
(defvar bbh-bombadil--step 0)
(defvar bbh-bombadil--start nil)
(defvar bbh-bombadil--ready nil
  "Non-nil once the first Worker view is up.  Steps and checks count from
then: the replay before it is setup, timed on its own in timings.log.")

(defun bbh-bombadil--kind ()
  (cond ((minibufferp) "minibuffer")
        ((derived-mode-p 'eng/bbh-transcript-mode) "transcript")
        ((bound-and-true-p eng/bbh-composer) "composer")
        (t (symbol-name major-mode))))

(defvar bbh-bombadil--in-command nil
  "Non-nil from a command's pre-command hook until its step is checked after
redisplay.  Output can land in that span, and the command may move the
windows itself.")

(defun bbh-bombadil--pre-command ()
  (setq bbh-bombadil--in-command t)
  (setq bbh-bombadil--step (1+ bbh-bombadil--step)
        bbh-bombadil--start (and bbh-bombadil--ready (list (float-time) this-command (key-description (this-command-keys)) (bbh-bombadil--kind)
                                                         (format "%s %s %s" evil-state (if (bound-and-true-p evil-local-mode) "evil" "no-evil") (buffer-name))))))

(defun bbh-bombadil--post-command ()
  (when-let* ((start bbh-bombadil--start))
    (let ((step bbh-bombadil--step))
      (run-with-idle-timer
       0 nil
       (lambda ()
         (when (= step bbh-bombadil--step)
           (setq bbh-bombadil--in-command nil)
           (pcase-let* ((`(,t0 ,command ,keys ,kind ,where) start)
                        (ms (round (* 1000 (- (float-time) t0)))))
             (bbh-bombadil--log "steps.log" "%d\t%s\t%s\t%s\t%d\t%s" ms command keys kind
                                (with-current-buffer (window-buffer (selected-window)) (buffer-size))
                                where)
             (when (> ms bbh-bombadil-step-ms)
               (bbh-bombadil-violation "slow: %s (%s) in the %s took %d ms, over %d" command keys kind ms bbh-bombadil-step-ms))
             (bbh-bombadil--check-windows (format "after %s (%s)" command keys))
             (bbh-bombadil--check-transcript-state (format "after %s (%s)" command keys)))))))))

(defun bbh-bombadil--check-transcript-state (when)
  "The selected transcript or thread list takes normal-state keys: both are
read-only, so j moves and does not try to insert."
  (with-current-buffer (window-buffer (selected-window))
    (when (derived-mode-p 'eng/bbh-transcript-mode 'eng/bbh-threads-mode)
      (let ((j (key-binding "j")))
        (unless (and (memq evil-state '(normal motion visual)) (not (memq j '(self-insert-command org-self-insert-command undefined))))
          (bbh-bombadil-violation "%s: %s is in %s state with evil-local-mode %s, and j runs %s"
                                  when (buffer-name) evil-state (bound-and-true-p evil-local-mode) j))))))

(add-hook 'pre-command-hook #'bbh-bombadil--pre-command)
(add-hook 'post-command-hook #'bbh-bombadil--post-command 90)

(defun bbh-bombadil--check-windows (when)
  "After a command has redisplayed: every transcript window shows its point,
starts inside its buffer, and leaves no blank rows below the end.
Emacs skips redisplay while typed-ahead keys wait, and output can land
between a command and this timer, so the check first redisplays, as Emacs
does once the typing pauses: what is wrong after that is on the screen."
  (redisplay t)
  (dolist (w (bbh-bombadil--transcript-windows))
    (with-current-buffer (window-buffer w)
      (let ((start (window-start w)) (pt (window-point w)))
        (cond ((or (< start (point-min)) (> start (point-max)))
               (bbh-bombadil-violation "%s: window-start %d is outside %s (1..%d)" when start (buffer-name) (point-max)))
              ((not (pos-visible-in-window-p pt w))
               (bbh-bombadil-violation "%s: point %d of %s is not visible (start %d, the clamp's latest start %d, %d rows, %d screen lines from start to the end %d, window-end %s, following %s)"
                                       when pt (buffer-name) start (eng/bbh-transcript--latest-start w) (window-body-height w)
                                       (count-screen-lines start (point-max) t w) (point-max) (window-end w) (bbh-bombadil--following-p w)))
              ((and (pos-visible-in-window-p (point-max) w) (> start (point-min)))
               (let* ((lines (count-screen-lines start (point-max) t w))
                      (blank (- (window-body-height w) lines)))
                 (when (> blank 0)
                   (bbh-bombadil-violation "%s: %s shows %d blank rows below its end (start %d, the clamp's latest start %d, %d rows, %d screen lines to the end %d, org-indent %s)"
                                           when (buffer-name) blank start (eng/bbh-transcript--latest-start w)
                                           (window-body-height w) lines (point-max)
                                           (if (bound-and-true-p org-indent-mode)
                                               (if (and (markerp org-indent--initial-marker) (marker-position org-indent--initial-marker)) "initializing" "done")
                                             "off"))))))))))

(defun bbh-bombadil--around-edit (edit change)
  "Time EDIT of the transcript and check what it did to the windows on it:
one following the end still does, and one scrolled up keeps its point and start."
  (let* ((windows (mapcar (lambda (w) (list w (window-start w) (window-point w) (bbh-bombadil--following-p w)))
                          (get-buffer-window-list nil nil t)))
         (t0 (float-time))
         (step bbh-bombadil--step))
    (prog1 (funcall edit change)
      (let ((ms (round (* 1000 (- (float-time) t0)))))
        (when bbh-bombadil--ready
          (bbh-bombadil--log "steps.log" "%d\toutput\t-\ttranscript\t%d" ms (buffer-size))
          (when (> ms bbh-bombadil-step-ms)
            (bbh-bombadil-violation "slow: rendering output into %s took %d ms" (buffer-name) ms))))
      (pcase-dolist (`(,w ,start ,pt ,following) windows)
        (when (window-live-p w)
          (cond (following
                 (if (bbh-bombadil--following-p w)
                     (unless bbh-bombadil--in-command
                       (run-with-idle-timer
                        0 nil
                        (lambda ()
                          (when (and (= step bbh-bombadil--step) (not bbh-bombadil--in-command))
                            (bbh-bombadil--check-windows "after output")))))
                   (bbh-bombadil-violation "output left a following window of %s at %d, not the end %d"
                                           (buffer-name) (window-point w) (point-max))))
                ((/= (window-point w) pt)
                 (bbh-bombadil-violation "output moved point of a scrolled-up window of %s from %d to %d"
                                         (buffer-name) pt (window-point w)))
                ((/= (window-start w) start)
                 (bbh-bombadil-violation "output moved the start of a scrolled-up window of %s from %d to %d"
                                         (buffer-name) start (window-start w)))
                (bbh-bombadil--in-command)
                (t
                 (let ((buffer (current-buffer)))
                   (run-with-idle-timer
                    0 nil
                    (lambda ()
                      (when (and (= step bbh-bombadil--step) (window-live-p w) (eq (window-buffer w) buffer)
                                 (= (window-point w) pt) (/= (window-start w) start))
                        (bbh-bombadil-violation "redisplay after output moved the start of a scrolled-up window of %s from %d to %d"
                                                (buffer-name buffer) start (window-start w)))))))))))))

(advice-add 'eng/bbh-transcript--edit :around #'bbh-bombadil--around-edit)

;; The controller's emacsclient and the Inbox's sentinel show a view outside any command, so the per-command check misses them.
(advice-add 'eng/bbh-super-visit :after
            (lambda (name)
              (when bbh-bombadil--ready
                (run-with-idle-timer 0 nil (lambda () (bbh-bombadil--check-windows (format "after showing %s" name)))))))

(defun bbh-bombadil--after-error (message)
  (save-excursion
    (goto-char (point-max))
    (let ((limit (or (save-excursion (re-search-backward "^\\* " nil t)) (point-min)))
          (n 0))
      (while (search-backward (concat "Error: " message) limit t) (setq n (1+ n)))
      (when (> n 1)
        (bbh-bombadil-violation "%s shows the error %S %d times since its last prompt" (buffer-name) message n)))))

(advice-add 'eng/bbh-transcript--error :after #'bbh-bombadil--after-error)

(defvar bbh-bombadil--rng (random (or (getenv "BBH_BOMBADIL_SEED") "0")))
(defvar bbh-bombadil-turn-end-failure-rate 0.15)

(defun bbh-bombadil--turn-end-fault (&rest _)
  (when (< (random 1000) (* 1000 bbh-bombadil-turn-end-failure-rate))
    (bbh-bombadil--log "faults.log" "turn end signals in %s" (buffer-name))
    (error "bombadil: injected turn-end failure (T163)")))

(advice-add 'agent-shell--collapse-expanded-activity-group :before #'bbh-bombadil--turn-end-fault)

(defvar bbh-bombadil--busy-since (make-hash-table :test #'eq :weakness 'key))

(defun bbh-bombadil--check-busy ()
  "A shell busy with no request in flight and no retry waiting has lost
its turn's end; after 10 s that breaks the busy-clears property."
  (dolist (shell (buffer-list))
    (when (eng/bbh--shell-p shell)
      (with-current-buffer shell
        (if (and (bound-and-true-p shell-maker--busy)
                 (not (map-elt agent-shell--state :active-requests))
                 (not (timerp eng/bbh--retry)))
            (let ((since (or (gethash shell bbh-bombadil--busy-since)
                             (puthash shell (float-time) bbh-bombadil--busy-since))))
              (when (and since (> (- (float-time) since) 10))
                (puthash shell nil bbh-bombadil--busy-since)
                (bbh-bombadil-violation "%s stayed busy for 10 s with no request in flight (T163)" (buffer-name))))
          (remhash shell bbh-bombadil--busy-since))))))

(run-with-timer 1 1 #'bbh-bombadil--check-busy)

(defvar bbh-bombadil--last-beat nil)

(defun bbh-bombadil--beat ()
  "Write the heartbeat, and time any stall: the per-command timing misses
Emacs busy between commands, as with output, which leaves keys waiting."
  (let* ((now (float-time))
         (gap (and bbh-bombadil--last-beat (round (* 1000 (- now bbh-bombadil--last-beat))))))
    (setq bbh-bombadil--last-beat (and bbh-bombadil--ready now))
    (when (and gap (> gap 750))
      (bbh-bombadil--log "steps.log" "%d\tstall\t-\ttimers\t0" gap)
      (when (> gap bbh-bombadil-step-ms)
        (bbh-bombadil-violation "slow: Emacs ran no timers for %d ms, over %d" gap bbh-bombadil-step-ms)))
    (write-region (format-time-string "%s.%3N") nil (bbh-bombadil--file "heartbeat") nil 'silent)))

(run-with-timer 0 0.5 #'bbh-bombadil--beat)

(setq debug-on-event 'sigusr2
      debugger (lambda (&rest _)
                 (write-region (concat "---- " (format-time-string "%T.%3N") "\n"
                                       (with-output-to-string (backtrace)) "\n")
                               nil (bbh-bombadil--file "hang.log") t 'silent)
                 nil))

(defvar bbh-bombadil--seeds (split-string (or (getenv "BBH_BOMBADIL_SEED_CONVERSATIONS") "") "," t)
  "Seeded conversations, one for each Worker opened before the view is up.")
(defvar bbh-bombadil--pending nil "(SHELL-NAME . SEED) to replay, newest first.")

(defun bbh-bombadil--replay-seed (name seed)
  "Render conversation SEED into the transcript of shell NAME, timed."
  (when-let* ((shell (get-buffer name))
              ((file-exists-p (eng/bbh--conversation-file seed "messages.jsonl"))))
    (let ((t0 (float-time)))
      (with-current-buffer (eng/bbh--transcript shell)
        (eng/bbh-transcript--replay seed)
        (bbh-bombadil--log "timings.log" "replay %s\t%d ms\t%d chars" seed (round (* 1000 (- (float-time) t0))) (buffer-size))))))

(defun bbh-bombadil--set-up ()
  "Replay the seeds into the Workers opened so far, then show the first."
  (let ((pending (reverse bbh-bombadil--pending)))
    (pcase-dolist (`(,name . ,seed) pending) (bbh-bombadil--replay-seed name seed))
    (let ((t0 (float-time)))
      (eng/bbh-super-visit (car (car pending)))
      (redisplay t)
      (bbh-bombadil--log "timings.log" "first redisplay\t%d ms" (round (* 1000 (- (float-time) t0)))))
    (setq bbh-bombadil--ready t)
    (write-region "" nil (bbh-bombadil--file "ready") nil 'silent)))

(defun bbh-bombadil--opened (name)
  (when-let* (((not bbh-bombadil--ready))
              (shell (get-buffer name))
              ((not (equal "router" (buffer-local-value 'eng/bbh-super-conversation shell))))
              ((not (assoc name bbh-bombadil--pending)))
              (seed (nth (length bbh-bombadil--pending) bbh-bombadil--seeds)))
    (unless bbh-bombadil--pending (run-at-time 3 nil #'bbh-bombadil--set-up))
    (push (cons name seed) bbh-bombadil--pending)))

(advice-add 'eng/bbh-super-open :filter-return (lambda (name) (bbh-bombadil--opened name) name))

(switch-to-buffer (get-buffer-create "*bombadil*"))
(insert "Waiting for the fixture's controller to open a Worker here.\n")
