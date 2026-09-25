-- Compile-check helper for Fennel spec files.
--
-- Called by scripts/test-nvim (babashka) to pre-validate .fnl specs before
-- spawning test processes. A Fennel parse error inside plenary.busted.run
-- causes nvim to hang (the coroutine never exits), so catching syntax errors
-- early is essential.
--
-- Usage (from nvim -c, after -u minimal-init.lua which installs fennel):
--   lua dofile('compile-check.lua')('spec/a.fnl','spec/b.fnl',...)
--
-- Prints "OK: <file>" for each spec that compiles, or "FAIL: <file> <error>"
-- for failures. Exits with code 1 if any fail, 0 if all pass.

local fennel = require("fennel")

return function(...)
  local specs = { ... }
  local any_fail = false
  for _, spec in ipairs(specs) do
    local fh = io.open(spec, "r")
    if not fh then
      print("FAIL: " .. spec .. " (cannot open file)")
      any_fail = true
    else
      local src = fh:read("*a")
      fh:close()
      local ok, compiled_or_err = pcall(fennel.compileString, src, { filename = spec })
      if ok and compiled_or_err then
        print("OK:   " .. spec)
      else
        -- Extract the first line of the error message for brevity.
        local msg = tostring(compiled_or_err):match("([^\n]+)") or tostring(compiled_or_err)
        -- Strip ANSI escape codes and fennel's reverse-video formatting.
        msg = msg:gsub("\27%[[0-9;]*m", "")
        print("FAIL: " .. spec .. "\n      " .. msg)
        any_fail = true
      end
    end
  end
  if any_fail then
    vim.cmd("1cq")
  else
    vim.cmd("0cq")
  end
end
