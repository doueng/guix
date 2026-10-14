(local plugin (. (require :lib.lazy) :plugin))

(plugin :nvim-lualine/lualine.nvim
        {:opts {:options {:theme :auto
                          :globalstatus true
                          :component_separators {:left "│" :right "│"}
                          :section_separators {:left "" :right ""}}
                :sections {:lualine_a [:mode]
                           :lualine_b []
                           :lualine_c [(plugin :diagnostics {})
                                       (plugin :filetype
                                               {:icon_only true
                                                :separator ""
                                                :padding {:left 1 :right 0}})
                                       (plugin :filename
                                               {:path 1
                                                :symbols {:modified " ●"
                                                          :readonly " "
                                                          :unnamed "[No Name]"}})]
                           :lualine_x [:filetype]
                           :lualine_y [(plugin :progress
                                               {:separator " "
                                                :padding {:left 1 :right 0}})
                                       (plugin :location
                                               {:padding {:left 0 :right 1}})]
                           :lualine_z []}}})
