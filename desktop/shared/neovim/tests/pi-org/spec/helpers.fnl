;; Shared test helpers for pi-org specs.
;;
;; Ensures pi-org.setup() has been called with the stub-pi config, so
;; require("pi-org").config() returns a valid table in every spec.
;; Loaded via (require :helpers) — the spec dir is on fennel.path.

(local M {})

(local fixtures-dir vim.env.PI_ORG_FIXTURES_DIR)

;; Ensure pi-org is set up with the stub-pi command.
(fn M.ensure-setup []
  (local pi-org (require :pi-org))
  (var cfg (pi-org.config))
  (when (or (= cfg nil) (= cfg.command nil))
    (pi-org.setup
      {:command (.. fixtures-dir "/stub-pi")
       :args []
       :window {:position :botright :split_ratio 0.6 :input_ratio 0.25}})
    (set cfg (pi-org.config)))
  cfg)

M
