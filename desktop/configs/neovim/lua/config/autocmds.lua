local source = debug.getinfo(1, "S").source
local file = source:sub(1, 1) == "@" and source:sub(2) or (vim.fn.stdpath("config") .. "/lua/config/autocmds.lua")
local root = vim.fn.fnamemodify(file, ":p:h:h:h")

return require("fennel").dofile(root .. "/fnl/config/autocmds.fnl", { correlate = true })
