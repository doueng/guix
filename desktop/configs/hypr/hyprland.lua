local keys = require("keys")

hl.monitor({ output = "eDP-1", mode = "2560x1600", position = "auto", scale = 1.6 })

hl.config({
  general = { gaps_in = 5, gaps_out = 9, border_size = 0, layout = "dwindle" },
  decoration = {
    rounding = 11,
    shadow = { enabled = true, range = 11 },
    blur = { enabled = true, size = 4, passes = 2 },
  },
  animations = { enabled = false },
  dwindle = { force_split = 2, preserve_split = true },
  cursor = { inactive_timeout = 2, enable_hyprcursor = false },
  input = {
    repeat_delay = 250,
    repeat_rate = 30,
    sensitivity = 0.3,
    accel_profile = "adaptive",
    scroll_method = "2fg",
    touchpad = {
      tap_to_click = false,
      natural_scroll = true,
      middle_button_emulation = false,
      scroll_factor = 0.5,
      clickfinger_behavior = true,
      disable_while_typing = true,
    },
  },
  misc = { disable_hyprland_logo = true, force_default_wallpaper = 0 },
})

hl.env("XCURSOR_THEME", "Adwaita")
hl.env("XCURSOR_SIZE", "20")

require("startup")

hl.window_rule({ name = "intellij-workspace", match = { class = "jetbrains-idea" }, workspace = "4" })
hl.window_rule({
  name = "chrome-extension-popup",
  match = { initial_title = "_crx_.*" },
  float = true,
  size = "900 520",
  center = true,
})

hl.bind("SUPER + RETURN", hl.dsp.exec_cmd("~/.local/bin/workspace-or-herdr-tab 1"))
hl.bind("SUPER + U", hl.dsp.focus({ workspace = 2 }))
hl.bind("SUPER + SHIFT + U", hl.dsp.focus({ workspace = 3 }))
hl.bind("SUPER + E", hl.dsp.exec_cmd("~/.local/bin/workspace-or-herdr-tab 2"))
hl.bind("SUPER + D", hl.dsp.exec_cmd("~/.local/bin/workspace-or-herdr-tab 3"))
hl.bind("SUPER + G", hl.dsp.exec_cmd("~/.local/bin/workspace-or-herdr-tab 4"))
hl.bind("SUPER + B", hl.dsp.exec_cmd("~/.local/bin/herdr-terminal"))
hl.bind("SUPER + COMMA", hl.dsp.exec_cmd("ghostty -e nvim ~/.config/hypr/hyprland.lua"))
hl.bind("SUPER + SLASH", hl.dsp.exec_cmd("~/.local/bin/keyguide"))
-- Hyprland reports M's release only while Cmd is held, so Cmd's release (code:133/134) also stops;
-- the flag keeps other Cmd releases from starting a process.
local dictating = false
local agent_dictating = false
local suppress_plain_dictation = false
-- Release stops only the press's own take, so a tap whose release runs first cannot leave Hex recording.
local agent_take = nil
local agent_takes = 0
hl.bind("SUPER + SHIFT + M", function()
  if not dictating and not agent_dictating then
    agent_dictating = true
    suppress_plain_dictation = true
    agent_takes = agent_takes + 1
    agent_take = os.time() .. "-" .. agent_takes
    hl.dispatch(hl.dsp.exec_cmd("~/.local/bin/bbh say start " .. agent_take))
  end
end)
local function stop_agent_dictation()
  if agent_dictating then
    agent_dictating = false
    hl.dispatch(hl.dsp.exec_cmd("~/.local/bin/bbh say stop " .. agent_take))
  end
end
local function release_agent_cmd()
  stop_agent_dictation()
  suppress_plain_dictation = false
end
hl.bind("SUPER + SHIFT + M", release_agent_cmd, { release = true })
hl.bind("SUPER + SHIFT + code:133", release_agent_cmd, { release = true, non_consuming = true })
hl.bind("SUPER + SHIFT + code:134", release_agent_cmd, { release = true, non_consuming = true })
hl.bind("SUPER + SHIFT + code:50", stop_agent_dictation, { release = true, non_consuming = true })
hl.bind("SUPER + SHIFT + code:62", stop_agent_dictation, { release = true, non_consuming = true })
hl.bind("SUPER + M", function()
  if not agent_dictating and not suppress_plain_dictation then
    dictating = true
    hl.dispatch(hl.dsp.exec_cmd("hex dictation start"))
  end
end)
local function stop_dictation()
  stop_agent_dictation()
  if dictating then
    dictating = false
    hl.dispatch(hl.dsp.exec_cmd("hex dictation stop"))
  end
end
local function release_cmd()
  stop_dictation()
  suppress_plain_dictation = false
end
hl.bind("SUPER + M", release_cmd, { release = true })
hl.bind("SUPER + code:133", release_cmd, { release = true, non_consuming = true })
hl.bind("SUPER + code:134", release_cmd, { release = true, non_consuming = true })
hl.bind("SUPER + F", hl.dsp.exec_cmd("~/.local/bin/winhint"))

hl.bind("ALT + SPACE", keys.herdr_menu)
hl.bind("SUPER + Y", keys.herdr_yank)
hl.bind("ALT + E", keys.chrome_tab_list)
hl.bind("CTRL + T", keys.chrome_vomnibar_new_tab)
hl.bind("SUPER + CTRL + V", hl.dsp.exec_cmd("~/.local/bin/clipboard-history pick"))

require("keybindings")
require("leader")

hl.bind("PRINT", hl.dsp.exec_cmd("sh -c 'mkdir -p ~/Pictures; grim ~/Pictures/screenshot-$(date +%Y%m%d-%H%M%S).png'"))
hl.bind("SHIFT + PRINT", hl.dsp.exec_cmd("sh -c 'region=$(slurp) && grim -g \"$region\" - | wl-copy --type image/png'"))
hl.bind("XF86AudioRaiseVolume", hl.dsp.exec_cmd("wpctl set-volume -l 1.0 @DEFAULT_AUDIO_SINK@ 5%+"), { locked = true, repeating = true })
hl.bind("XF86AudioLowerVolume", hl.dsp.exec_cmd("wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%-"), { locked = true, repeating = true })
hl.bind("XF86AudioMute", hl.dsp.exec_cmd("wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle"))
hl.bind("XF86AudioMicMute", hl.dsp.exec_cmd("~/.local/bin/system-info"))
hl.bind("XF86LaunchA", hl.dsp.exec_cmd("~/.local/bin/connect-airpods"))
hl.bind("XF86MonBrightnessUp", hl.dsp.exec_cmd("brightnessctl set +5%"), { locked = true, repeating = true })
hl.bind("XF86MonBrightnessDown", hl.dsp.exec_cmd("brightnessctl set 5%-"), { locked = true, repeating = true })
