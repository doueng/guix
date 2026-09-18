vim.g.mapleader = " "
vim.g.maplocalleader = "\\"
require("familiar-options")
require("familiar-keymaps")
vim.g.clipboard = {
  name = "Wayland",
  copy = { ["+"] = { "wl-copy", "--type", "text/plain" }, ["*"] = { "wl-copy", "--primary", "--type", "text/plain" } },
  paste = { ["+"] = { "wl-paste", "--no-newline" }, ["*"] = { "wl-paste", "--primary", "--no-newline" } },
  cache_enabled = 0,
}
vim.cmd.colorscheme("habamax")
vim.api.nvim_set_hl(0, "Normal", { fg = "#cdd6f4", bg = "#1e1e2e" })
vim.api.nvim_set_hl(0, "NormalFloat", { fg = "#cdd6f4", bg = "#313244" })
vim.api.nvim_set_hl(0, "Visual", { bg = "#45475a" })
vim.keymap.set("n", "<A-,>", "<cmd>bprevious<cr>")
vim.keymap.set("n", "<A-.>", "<cmd>bnext<cr>")
vim.keymap.set("n", "<leader>bb", "<cmd>ls<cr>:buffer ")
vim.keymap.set("n", "<leader>e", "<cmd>Explore<cr>")
vim.keymap.set("n", "<leader>ff", function()
  local files = vim.fn.systemlist({ "fd", "--type", "f", "--hidden", "--exclude", ".git", "--exclude", ".jj" })
  if vim.v.shell_error ~= 0 then
    vim.notify("fd failed", vim.log.levels.ERROR)
    return
  end
  vim.ui.select(files, { prompt = "Find file: " }, function(path)
    if path then vim.cmd.edit(vim.fn.fnameescape(path)) end
  end)
end)
vim.keymap.set({ "n", "v" }, "<leader>gr", ":silent grep ")
vim.api.nvim_del_user_command("Sourcegraph")
vim.opt.grepprg = "rg --vimgrep --smart-case"
vim.opt.grepformat = "%f:%l:%c:%m"
vim.keymap.set("n", "<leader>/", ":silent grep ")
vim.api.nvim_create_autocmd("QuickFixCmdPost", { pattern = "grep", command = "cwindow" })
