-- Guix does not provide Home Manager's generated Fennel bootstrap.  Keep the
-- bootstrap portable: the Fennel executable supplied by Guix tells us where
-- its Lua module lives, and lazy.nvim remains mutable under XDG data.
local fennel_bin = vim.fn.exepath("fennel")
if fennel_bin ~= "" then
  local fennel_path = vim.fn.fnamemodify(fennel_bin, ":p:h:h")
    .. "/share/lua/5.3/?.lua"
  package.path = fennel_path .. ";" .. package.path
end

local lazy_dir = vim.fn.stdpath("data") .. "/lazy/lazy.nvim"
if vim.fn.isdirectory(lazy_dir) == 0 then
  vim.fn.mkdir(vim.fn.fnamemodify(lazy_dir, ":h"), "p")
  local output = vim.fn.system({
    "git", "clone", "--filter=blob:none", "--branch", "stable",
    "https://github.com/folke/lazy.nvim.git", lazy_dir,
  })
  if vim.v.shell_error ~= 0 then
    error("Could not bootstrap lazy.nvim: " .. output)
  end
end
vim.opt.rtp:prepend(lazy_dir)

local fennel = require("fennel")
return fennel.dofile(vim.fn.stdpath("config") .. "/init.fnl", {
  correlate = true,
})
