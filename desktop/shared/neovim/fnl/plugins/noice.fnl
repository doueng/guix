(local plugin (. (require :lib.lazy) :plugin))

;; JDTLS emits frequent progress updates for validation and publishing
;; diagnostics. Keep progress from other language servers without showing
;; JDTLS's routine updates.
(plugin :folke/noice.nvim
        {:opts (fn [_ opts]
                 (table.insert opts.routes
                               {:filter {:event :lsp
                                         :kind :progress
                                         :cond (fn [message]
                                                 (and message.opts
                                                      message.opts.progress
                                                      (= message.opts.progress.client
                                                         :jdtls)))}
                                :opts {:skip true}})
                 ;; Also hide informational window/showMessage events from JDTLS.
                 (table.insert opts.routes
                               {:filter {:event :lsp
                                         :kind :message
                                         :cond (fn [message]
                                                 (and (= message.level :info)
                                                      (= message.opts.title
                                                         "LSP Message (jdtls)")))}
                                :opts {:skip true}}))})
