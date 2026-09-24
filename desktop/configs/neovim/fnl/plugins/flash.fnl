;; flash.nvim - jump/search motion replacement for leap.nvim
;; https://github.com/folke/flash.nvim
(local lazy-spec (require :lib.lazy))
(local plugin lazy-spec.plugin)
(local key lazy-spec.key)

(fn flash-jump [forward?]
  ((. (require :flash) :jump) {:search {:forward forward? :wrap false}}))

(plugin :folke/flash.nvim
        {:event :VeryLazy
         :keys [(key :s (fn [] (flash-jump true))
                     {:mode [:n :x :o] :desc "Flash forward"})
                (key :S (fn [] (flash-jump false))
                     {:mode [:n :x :o] :desc "Flash backward"})]
         :opts {:modes {:char {:enabled false}}}})
