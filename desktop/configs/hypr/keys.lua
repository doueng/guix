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

-- The terminal multiplexer's window: Herdr's Ghostty, rexmux when
-- ~/.config/engstrand/mux says rex, or the ghostty-rex Ghostty when it says
-- ghostty-rex (T207; Guix Home writes it).
local function mux_backend()
  local ok, word = pcall(function()
    local home = os.getenv("XDG_CONFIG_HOME") or ((os.getenv("HOME") or "") .. "/.config")
    local f = io.open(home .. "/engstrand/mux", "r")
    if not f then return "herdr" end
    local s = f:read("*l") or ""
    f:close()
    return (s:gsub("%s", ""))
  end)
  if ok and (word == "rex" or word == "ghostty-rex") then return word end
  return "herdr"
end

M.mux = mux_backend()
M.herdr_class = ({ rex = "rexmux", ["ghostty-rex"] = "dev.engstrand.Rex" })[M.mux]
  or "com.mitchellh.ghostty.herdr"

local function send_to_herdr(chords)
  local active = hl.get_active_window()
  if not (active and active.class == M.herdr_class) then
    local terminal = hl.get_windows({ class = M.herdr_class })[1]
    if not terminal then
      hl.exec_cmd("~/.local/bin/mux-terminal")
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
    M.log("Alt+Space", "Forwarded to " .. M.mux, "forward")
  end
end

local function tap(mods, key)
  hl.dispatch(hl.dsp.send_key_state({ mods = mods, key = key, state = "down" }))
  hl.dispatch(hl.dsp.send_key_state({ mods = mods, key = key, state = "up" }))
end

-- In Chrome, send a vimium-cljs command's chord; every other window gets the chord unchanged.
local function in_chrome(own_mods, key, chrome_key, keys, effect)
  return function()
    local active = hl.get_active_window()
    if active and active.class == "google-chrome" then
      tap("CTRL SHIFT", chrome_key)
      M.log(keys, effect)
    else
      tap(own_mods, key)
    end
  end
end

-- Non-Chrome windows get Alt+E back for Emacs M-e and fish's edit_command_buffer.
M.chrome_tab_list = in_chrome("ALT", "e", "e", "Alt+E", "Chrome tab list (side panel)")
-- kanata turns Cmd+T into Ctrl+T. Elsewhere Ctrl+T stays, such as fish's transpose on Caps+T.
M.chrome_vomnibar_new_tab = in_chrome("CTRL", "t", "y", "Cmd+T", "Vomnibar, pick opens in a new tab")
-- Chrome keeps Ctrl+L for its address bar and never gives it to an extension. Elsewhere Ctrl+L stays clear-screen etc.
M.chrome_vomnibar = in_chrome("CTRL", "l", "h", "Ctrl+L", "Vomnibar, pick opens in this tab")

function M.herdr_yank()
  if send_to_herdr({ { mods = "CTRL", key = "b" }, { mods = "", key = "f" } }) then
    M.log("Cmd+Y", "Copy visible text by hint")
  end
end

return M
