(local plugin (. (require :lib.lazy) :plugin))

(local fff-plugin-dir
       (or vim.env.FFF_NVIM_PLUGIN_DIR
           (let [path (.. (vim.fn.stdpath :config) "/fff-plugin-dir")]
             (when (= (vim.fn.filereadable path) 1)
               (vim.trim (table.concat (vim.fn.readfile path) "\n"))))))

(plugin :fff.nvim-no-git
        {:dir fff-plugin-dir
         :name :fff.nvim
         :build false})
