(local plugin (. (require :lib.lazy) :plugin))

(local fff-plugin-dir
       (or vim.env.FFF_NVIM_PLUGIN_DIR
           (let [path (.. (vim.fn.stdpath :config) "/fff-plugin-dir")]
             (when (= (vim.fn.filereadable path) 1)
               (vim.trim (table.concat (vim.fn.readfile path) "\n"))))))

(if fff-plugin-dir
    (plugin :fff.nvim-no-git
            {:dir fff-plugin-dir
             :name :fff.nvim
             :build false})
    ;; Guix has no immutable vimPlugins collection for this plugin.  Let
    ;; lazy.nvim fetch the same upstream plugin on first launch instead of
    ;; failing because the Nix-only fff-plugin-dir is absent.
    (plugin :dmtrKovalenko/fff.nvim
            {:name :fff.nvim
             :build false}))
