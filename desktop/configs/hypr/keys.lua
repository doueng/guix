-- Helpers shared by hyprland.lua and the generated keybindings.lua and leader.lua.
local M = {}

local function quote(s)
  return "'" .. (s:gsub("'", "'\\''")) .. "'"
end

-- Append the binding to the personal log that `keylog` shows. KIND is one of
-- desktop/lib/keylog.clj's kinds and defaults to action.
function M.log(keys, effect, kind)
  hl.exec_cmd("~/.local/bin/keylog add " .. (kind or "action") .. " hyprland " .. quote(keys) .. " " .. quote(effect))
end

-- Enter the leader SUBMAP that KEYS open.
function M.enter(submap, keys)
  return function()
    hl.dispatch(hl.dsp.submap(submap))
    M.log(keys, "Desktop leader menu", "leader")
  end
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

-- Leave SUBMAP, entered with KEYS, without acting: on KEY, or on any key it
-- does not bind when KEY is nil. Hyprland also runs the catch-all after a bound
-- key in the same submap, so it leaves only if that key did not.
function M.cancel(submap, keys, key)
  return function()
    if hl.get_current_submap() == submap then
      hl.dispatch(hl.dsp.submap("reset"))
      M.log(keys, key and ("Cancelled with " .. key) or "Cancelled by an unbound key", "cancel")
    end
  end
end

-- desktop/bin/herdr-terminal starts Herdr's Ghostty with this class.
M.herdr_class = "com.mitchellh.ghostty.herdr"

-- Send CHORDS, such as { { mods = "CTRL", key = "b" } }, to the Herdr
-- terminal. From another window, focus the Herdr terminal first, or start one
-- and send nothing. Returns whether the chords were sent.
local function send_to_herdr(chords)
  local active = hl.get_active_window()
  if not (active and active.class == M.herdr_class) then
    local terminal = hl.get_windows({ class = M.herdr_class })[1]
    if not terminal then
      hl.exec_cmd("~/.local/bin/herdr-terminal")
      return false
    end
    hl.dispatch(hl.dsp.focus({ window = "address:" .. terminal.address }))
  end
  -- send_shortcut would make Hyprland run the handler again on release.
  for _, k in ipairs(chords) do
    for _, state in ipairs({ "down", "up" }) do
      hl.dispatch(hl.dsp.send_key_state({ mods = k.mods, key = k.key, state = state }))
    end
  end
  return true
end

-- Alt+Space: open Herdr's leader menu (Ctrl+b Space).
function M.herdr_menu()
  if send_to_herdr({ { mods = "CTRL", key = "b" }, { mods = "", key = "space" } }) then
    M.log("Alt+Space", "Forwarded to herdr", "forward")
  end
end

-- Cmd+Y: copy visible text by hint with tiny-fingers (Herdr's Ctrl+b f).
function M.herdr_yank()
  if send_to_herdr({ { mods = "CTRL", key = "b" }, { mods = "", key = "f" } }) then
    M.log("Cmd+Y", "Copy visible text by hint")
  end
end

return M
