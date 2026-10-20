-- Helpers shared by hyprland.lua and the generated keybindings.lua and leader.lua.
local M = {}

local function quote(s)
  return "'" .. (s:gsub("'", "'\\''")) .. "'"
end

-- Append the binding to the personal log that `keylog` shows.
function M.log(keys, effect)
  hl.exec_cmd("~/.local/bin/keylog add Hyprland " .. quote(keys) .. " " .. quote(effect))
end

-- Bind CHORD to DISPATCHER and log it as KEYS doing EFFECT.
function M.bind(chord, dispatcher, keys, effect, opts)
  return hl.bind(chord, function()
    hl.dispatch(dispatcher)
    M.log(keys, effect)
  end, opts)
end

-- Run DISPATCHER, then leave the leader submap.
function M.leave(dispatcher, keys, effect)
  return function()
    hl.dispatch(hl.dsp.submap("reset"))
    hl.dispatch(dispatcher)
    if keys then
      M.log(keys, effect)
    end
  end
end

-- The catch-all for keys a submap does not bind. Hyprland also runs it after
-- a bound key in the same submap, so it leaves only if that key did not.
function M.cancel(submap)
  return function()
    if hl.get_current_submap() == submap then
      hl.dispatch(hl.dsp.submap("reset"))
    end
  end
end

local terminal_class = "com.mitchellh.ghostty"

-- Cmd+Space: open Herdr's leader menu (Ctrl+b Space). From another app, focus
-- the Herdr terminal on workspace 1 first.
function M.herdr_menu()
  local active = hl.get_active_window()
  if not (active and active.class == terminal_class) then
    local terminal = hl.get_windows({ class = terminal_class, workspace = 1 })[1]
    if not terminal then
      hl.dispatch(hl.dsp.focus({ workspace = 1 }))
      return
    end
    hl.dispatch(hl.dsp.focus({ window = "address:" .. terminal.address }))
  end
  -- send_shortcut would make Hyprland run this handler again on release.
  for _, k in ipairs({ { mods = "CTRL", key = "b" }, { mods = "", key = "space" } }) do
    for _, state in ipairs({ "down", "up" }) do
      hl.dispatch(hl.dsp.send_key_state({ mods = k.mods, key = k.key, state = state }))
    end
  end
  M.log("Cmd+Space", "Herdr leader menu")
end

return M
