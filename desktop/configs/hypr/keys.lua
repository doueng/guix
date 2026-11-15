local M = {}

local function quote(s)
  return "'" .. (s:gsub("'", "'\\''")) .. "'"
end

function M.log(keys, effect, kind)
  hl.exec_cmd("~/.local/bin/keylog add " .. (kind or "action") .. " hyprland " .. quote(keys) .. " " .. quote(effect))
end

function M.enter(submap, keys)
  return function()
    hl.dispatch(hl.dsp.submap(submap))
    M.log(keys, "Desktop leader menu", "leader")
  end
end

function M.bind(chord, dispatcher, keys, effect, opts)
  return hl.bind(chord, function()
    hl.dispatch(dispatcher)
    M.log(keys, effect)
  end, opts)
end

function M.leave(dispatcher, keys, effect)
  return function()
    hl.dispatch(hl.dsp.submap("reset"))
    hl.dispatch(dispatcher)
    if keys then
      M.log(keys, effect)
    end
  end
end

-- Hyprland also runs the catch-all after a bound key in the same submap, so leave only if that key did not.
function M.cancel(submap, keys, key)
  return function()
    if hl.get_current_submap() == submap then
      hl.dispatch(hl.dsp.submap("reset"))
      M.log(keys, key and ("Cancelled with " .. key) or "Cancelled by an unbound key", "cancel")
    end
  end
end

M.herdr_class = "com.mitchellh.ghostty.herdr"

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

function M.herdr_menu()
  if send_to_herdr({ { mods = "CTRL", key = "b" }, { mods = "", key = "space" } }) then
    M.log("Alt+Space", "Forwarded to herdr", "forward")
  end
end

-- Non-Chrome windows get Alt+E back for Emacs M-e and fish's edit_command_buffer.
function M.chrome_tab_list()
  local active = hl.get_active_window()
  if active and active.class == "google-chrome" then
    hl.dispatch(hl.dsp.send_key_state({ mods = "CTRL SHIFT", key = "e", state = "down" }))
    hl.dispatch(hl.dsp.send_key_state({ mods = "CTRL SHIFT", key = "e", state = "up" }))
    M.log("Alt+E", "Chrome tab list (side panel)")
  else
    hl.dispatch(hl.dsp.send_key_state({ mods = "ALT", key = "e", state = "down" }))
    hl.dispatch(hl.dsp.send_key_state({ mods = "ALT", key = "e", state = "up" }))
  end
end

function M.herdr_yank()
  if send_to_herdr({ { mods = "CTRL", key = "b" }, { mods = "", key = "f" } }) then
    M.log("Cmd+Y", "Copy visible text by hint")
  end
end

return M
