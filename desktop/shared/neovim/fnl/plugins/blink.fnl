(local plugin (. (require :lib.lazy) :plugin))

(fn babashka-buffer? []
  (or (= (vim.fn.expand "%:e") "bb")
      (= (vim.fn.expand "%:t") "bb.edn")))

(plugin :Saghen/blink.cmp
        {:opts {:enabled (fn []
                           (not (babashka-buffer?)))
                :fuzzy {:implementation :lua
                        :prebuilt_binaries {:download false}}
                :sources {:default [:lsp :path]}
                :completion {:menu {:auto_show false}
                             :trigger {:prefetch_on_insert false
                                       :show_on_backspace false
                                       :show_on_backspace_in_keyword false
                                       :show_on_backspace_after_accept false
                                       :show_on_backspace_after_insert_enter false
                                       :show_on_keyword false
                                       :show_on_trigger_character false
                                       :show_on_insert false
                                       :show_on_accept_on_trigger_character false}}
                :keymap {:<C-space> false
                         :<C-l> [:show]}}})
