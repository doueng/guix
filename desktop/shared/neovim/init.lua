-- Guix does not provide Home Manager's generated Fennel bootstrap.  Keep the
-- bootstrap portable: the Fennel executable supplied by Guix tells us where
-- its Lua module lives, and lazy.nvim remains mutable under XDG data.
-- Guix patches Neovim's parser lookup to use TREE_SITTER_GRAMMAR_PATH
-- instead of runtimepath. Prefer Guix parsers, then fall back to parsers
-- installed by nvim-treesitter in runtimepath, loading only on demand.
do
  local add = vim.treesitter.language.add
  vim.treesitter.language.add = function(lang, opts)
    local loaded, err = add(lang, opts)
    if loaded or (opts and opts.path) or type(lang) ~= "string" then
      return loaded, err
    end

    local normalized = lang:lower()
    if not normalized:match("^[%w_]+$")
      or err ~= ('No parser for language "' .. normalized .. '"') then
      return loaded, err
    end

    local path = vim.api.nvim_get_runtime_file("parser/" .. normalized .. ".*", false)[1]
    if not path then
      return loaded, err
    end

    local fallback_opts = vim.tbl_extend("force", {}, opts or {}, { path = path })
    return add(lang, fallback_opts)
  end
end

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
