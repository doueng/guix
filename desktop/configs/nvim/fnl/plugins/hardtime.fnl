(local spec (require :lib.lazy))

(spec.plugin :m4xshen/hardtime.nvim
             {:lazy false
              :dependencies [:MunifTanjim/nui.nvim]
              :keys [(spec.key :<leader>uh "<cmd>Hardtime toggle<cr>"
                               {:desc "Toggle motion coaching"})
                     (spec.key :<leader>uH "<cmd>Hardtime report<cr>"
                               {:desc "Motion coaching report"})]
              :config (fn [_ opts]
                        (local hardtime (require :hardtime))
                        (local enable hardtime.enable)
                        (local disable hardtime.disable)
                        (var saved [])
                        (set hardtime.enable
                             (fn []
                               (when (not hardtime.is_plugin_enabled)
                                 (set saved [])
                                 (local config (. (require :hardtime.config) :config))
                                 (each [_ group (ipairs [config.resetting_keys config.restricted_keys config.disabled_keys])]
                                   (each [key modes (pairs group)]
                                     (when modes
                                       (each [_ mode (ipairs modes)]
                                         (local code (vim.api.nvim_replace_termcodes key true false true))
                                         (each [_ mapping (ipairs (vim.api.nvim_get_keymap mode))]
                                           (when (= code (vim.api.nvim_replace_termcodes mapping.lhs true false true))
                                             (table.insert saved [mode mapping])))))))
                                 (enable))))
                        ;; Hardtime deletes its maps on disable but does not restore ours.
                        (set hardtime.disable
                             (fn []
                               (when hardtime.is_plugin_enabled
                                 (disable)
                                 (each [_ item (ipairs saved)]
                                   (vim.fn.mapset (. item 1) false (. item 2)))
                                 (set saved []))))
                        (hardtime.setup opts))
              :opts {:restriction_mode :hint
                     :disable_mouse false
                     :max_count 4
                     :disabled_keys {:<Up> false :<Down> false
                                     :<Left> false :<Right> false}}})
