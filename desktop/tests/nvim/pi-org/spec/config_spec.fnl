;; Unit tests for pi-org.config: defaults merging + validation.
;;
;; config.parse is pure (uses vim.notify/vim.deepcopy only), so no UI needed.

(local config (require :pi-org.config))
(local defaults config.defaults)
(local eq assert.are.same)
(local is-true assert.is_true)

(describe "pi-org.config defaults"
  (fn []
    (it "has a non-empty default command"
      (fn []
        (is-true (= (type defaults.command) :string))
        (is-true (> (length defaults.command) 0))))

    (it "has window/keymaps/org sub-tables"
      (fn []
        (eq :table (type defaults.window))
        (eq :table (type defaults.keymaps))
        (eq :table (type defaults.org))))

    (it "defaults split_ratio and input_ratio to (0,1]"
      (fn []
        (is-true (and (> defaults.window.split_ratio 0)
                      (<= defaults.window.split_ratio 1)))
        (is-true (and (> defaults.window.input_ratio 0)
                      (<= defaults.window.input_ratio 1)))))))

(describe "pi-org.config parse"
  (fn []
    (it "returns the merged config for valid input"
      (fn []
        (local cfg (config.parse {:command :my-pi :args [ "--foo" ]} true))
        (eq :my-pi cfg.command)
        (eq [ "--foo" ] cfg.args)
        ;; defaults are preserved for unspecified keys
        (eq defaults.window.split_ratio cfg.window.split_ratio)
        (eq defaults.keymaps.submit cfg.keymaps.submit)))

    (it "deep-merges nested window/keymaps tables"
      (fn []
        (local cfg (config.parse {:window {:input_ratio 0.5}} true))
        (eq 0.5 cfg.window.input_ratio)
        ;; unspecified nested keys keep their defaults
        (eq defaults.window.split_ratio cfg.window.split_ratio)
        (eq defaults.window.position cfg.window.position)))

    (it "rejects an empty command string and falls back to defaults (silent)"
      (fn []
        (local cfg (config.parse {:command ""} true))
        (eq defaults.command cfg.command)))

    (it "rejects a non-string command and falls back to defaults (silent)"
      (fn []
        (local cfg (config.parse {:command 123} true))
        (eq defaults.command cfg.command)))

    (it "rejects non-table args and falls back to defaults (silent)"
      (fn []
        (local cfg (config.parse {:command :pi :args :nope} true))
        (eq defaults.args cfg.args)))

    (it "rejects split_ratio <= 0"
      (fn []
        (local cfg (config.parse {:command :pi :window {:split_ratio 0}} true))
        (eq defaults.window.split_ratio cfg.window.split_ratio)))

    (it "rejects split_ratio > 1"
      (fn []
        (local cfg (config.parse {:command :pi :window {:split_ratio 1.5}} true))
        (eq defaults.window.split_ratio cfg.window.split_ratio)))

    (it "rejects input_ratio <= 0"
      (fn []
        (local cfg (config.parse {:command :pi :window {:input_ratio 0}} true))
        (eq defaults.window.input_ratio cfg.window.input_ratio)))

    (it "rejects input_ratio > 1"
      (fn []
        (local cfg (config.parse {:command :pi :window {:input_ratio 2}} true))
        (eq defaults.window.input_ratio cfg.window.input_ratio)))

    (it "accepts split_ratio = 1 (boundary)"
      (fn []
        (local cfg (config.parse {:command :pi :window {:split_ratio 1}} true))
        (eq 1 cfg.window.split_ratio)))))
