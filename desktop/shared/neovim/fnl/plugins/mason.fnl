(local plugin (. (require :lib.lazy) :plugin))

[(plugin :mason-org/mason.nvim {:enabled false :optional true})
 (plugin :mason-org/mason-lspconfig.nvim {:enabled false :optional true})
 (plugin :jay-babu/mason-null-ls.nvim {:enabled false :optional true})
 (plugin :jay-babu/mason-nvim-dap.nvim {:enabled false :optional true})]
