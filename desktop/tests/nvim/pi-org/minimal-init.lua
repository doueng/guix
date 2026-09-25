-- Minimal Neovim init for the pi-org test suite.
--
-- Sets up the Fennel searcher (so `require(:pi-org.*)` works from the repo's
-- fnl/ tree), installs a lazy.nvim spec for plenary.nvim (the test runner)
-- and nvim-orgmode (so the org filetype loads during integration tests), and
-- calls pi-org.setup with the stub-pi command so session tests never spawn a
-- real `pi`.
--
-- Headless invocation (from the repository root):
--   nvim --headless -u minimal-init.lua \
--     -c "lua require('plenary.busted').run(vim.fn.argv(0))" spec/foo_spec.fnl
--
-- Fennel spec files (.fnl) are compiled at runtime by the patched loadfile
-- below, so specs can be written in Fennel while reusing plenary's runner.

vim.g.mapleader = " "
vim.g.maplocalleader = "\\"

-- NOTE: vim.loader.enable() is intentionally NOT called here. It caches
-- compiled bytecode and can serve a stale/different module instance than
-- the one setup() was called on, causing config() to return nil in tests.

-- Discover the Fennel share dir. In the Nix setup the system nvim config's
-- bootstrap prepends a Nix-store path to package.path; we replicate that by
-- resolving `fennel` on PATH and deriving its share/lua/<ver> directory.
-- This keeps the test init self-contained (no hardcoded store hash).
local function find_fennel_share()
  -- Try the profile path first (works under Home Manager).
  local profile = vim.fn.expand("$HOME/.nix-profile/share/lua")
  for _, ver in ipairs({ "5.2", "5.1", "?.lua" }) do
    local candidate = profile .. "/" .. ver .. "/fennel.lua"
    if vim.loop.fs_stat(candidate) then
      return profile .. "/" .. ver
    end
  end
  -- Fall back to resolving `fennel` on PATH and walking up to share/lua.
  local fennel_bin = vim.fn.exepath("fennel")
  if fennel_bin and fennel_bin ~= "" then
    local resolved = vim.fn.resolve(fennel_bin)
    local dir = vim.fn.fnamemodify(resolved, ":h:h") -- .../bin -> ...
    for _, ver in ipairs({ "5.2", "5.1" }) do
      local candidate = dir .. "/share/lua/" .. ver .. "/fennel.lua"
      if vim.loop.fs_stat(candidate) then
        return dir .. "/share/lua/" .. ver
      end
    end
  end
  return nil
end

local fennel_share = find_fennel_share()
if fennel_share then
  package.path = fennel_share .. "/?.lua;" .. fennel_share .. "/?/init.lua;" .. package.path
end

local fennel = require("fennel")
-- Resolve the config and fixture trees from this file's path (arg[0] is nil under -u).
local source = debug.getinfo(1, "S").source
if source:sub(1, 1) == "@" then source = source:sub(2) end
local test_dir = vim.fn.fnamemodify(vim.fn.resolve(source), ":p:h")
local desktop_dir = vim.fn.fnamemodify(vim.fn.resolve(source), ":p:h:h:h:h")
local config_dir = desktop_dir .. "/configs/nvim"

-- NOTE: We do NOT prepend config_dir to the runtimepath. Doing so exposes
-- fnl/config/*.fnl (e.g. config.fff) to lazy.nvim's rtp scan, which tries
-- to require them and fails (fff plugin isn't installed in the test env).
-- The Fennel searcher + fennel.path below are sufficient for require(:pi-org.*).
fennel.path = config_dir .. "/fnl/?.fnl;" .. config_dir .. "/fnl/?/init.fnl;" .. fennel.path

local searchers = package.searchers or package.loaders
table.insert(searchers, 2, fennel.searcher)

-- Allow plenary.busted.run() to load Fennel spec files (.fnl). plenary uses
-- loadfile() internally, which only understands Lua. We wrap loadfile so
-- .fnl files are compiled via fennel and returned as a callable chunk.
-- This lets specs be written in Fennel while reusing plenary's runner.
local _orig_loadfile = loadfile
loadfile = function(path, ...)
  if path and path:match("%.fnl$") then
    local f = io.open(path, "r")
    if not f then return nil, "cannot open " .. path end
    local src = f:read("*a")
    f:close()
    local compiled, err = fennel.compileString(src)
    if not compiled then return nil, err end
    return load(compiled, path)
  end
  return _orig_loadfile(path, ...)
end

-- Put the spec directory on fennel.path so `require` can find helper modules
-- written in Fennel (e.g. helpers.fnl → require :helpers).
local spec_dir = test_dir .. "/spec"
fennel.path = spec_dir .. "/?.fnl;" .. fennel.path

-- Where the stub-pi fixture lives; individual specs override
-- PI_ORG_STUB_FIXTURE to choose which event sequence to replay.
local fixtures_dir = test_dir .. "/fixtures"
vim.env.PI_ORG_STUB_FIXTURE = fixtures_dir .. "/turn-text-only.jsonl"

-- Lazy-load plenary (test runner) + orgmode (org filetype for integration).
local lazypath = vim.fn.stdpath("data") .. "/lazy/lazy.nvim"
if not vim.loop.fs_stat(lazypath) then
  vim.fn.system({
    "git", "clone", "--filter=blob:none",
    "https://github.com/folke/lazy.nvim.git", lazypath,
  })
end
vim.opt.rtp:prepend(lazypath)

local ok, lazy = pcall(require, "lazy")
if ok then
  lazy.setup({
    { "nvim-lua/plenary.nvim", lazy = false },
    { "nvim-orgmode/orgmode",
      ft = "org",
      config = function()
        local org = require("orgmode")
        org.setup({ org_agenda_files = {}, org_default_notes_file = nil })
      end,
    },
  }, { install = { missing = true }, checker = { enabled = false } })
end

-- Expose the fixtures path to specs via vim.env so they don't have to
-- recompute it.
vim.env.PI_ORG_FIXTURES_DIR = fixtures_dir

-- Set up pi-org with the stub command. Specs that drive the session use this
-- configuration; unit tests call the modules directly and ignore it.
local pi_org = require("pi-org")
pi_org.setup({
  command = fixtures_dir .. "/stub-pi",
  args = {},
  -- Keep the window split small/predictable for screen assertions.
  window = { position = "botright", split_ratio = 0.6, input_ratio = 0.25 },
})
