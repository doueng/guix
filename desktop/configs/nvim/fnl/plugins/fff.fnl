(local plugin (. (require :lib.lazy) :plugin))

;; Guix has no vim plugin package for fff, so lazy.nvim fetches it.
(plugin :dmtrKovalenko/fff.nvim
        {:name :fff.nvim
         :build false})
