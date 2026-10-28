-- Guix Hyprland configuration in Hyprland 0.55's Lua format.
-- keybindings.lua and leader.lua are generated; see keybindings/install.md.
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

-- IntelliJ owns workspace 4. Opening the empty workspace starts it, and its
-- windows land there when started from elsewhere.
hl.workspace_rule({ workspace = "4", on_created_empty = "flatpak run com.jetbrains.IntelliJ-IDEA-Community" })
hl.window_rule({ name = "intellij-workspace", match = { class = "jetbrains-idea" }, workspace = "4" })

-- Cmd application shortcuts. Terminal bindings focus the Herdr terminal,
-- then select its Herdr tab when it is already focused.
hl.bind("SUPER + RETURN", hl.dsp.exec_cmd("~/.local/bin/workspace-or-herdr-tab 1"))
hl.bind("SUPER + U", hl.dsp.focus({ workspace = 2 }))
hl.bind("SUPER + SHIFT + U", hl.dsp.focus({ workspace = 3 }))
hl.bind("SUPER + I", hl.dsp.focus({ workspace = 4 }))
hl.bind("SUPER + E", hl.dsp.exec_cmd("~/.local/bin/workspace-or-herdr-tab 2"))
hl.bind("SUPER + D", hl.dsp.exec_cmd("~/.local/bin/workspace-or-herdr-tab 3"))
hl.bind("SUPER + G", hl.dsp.exec_cmd("~/.local/bin/workspace-or-herdr-tab 4"))
hl.bind("SUPER + B", hl.dsp.exec_cmd("~/.local/bin/herdr-terminal"))
hl.bind("SUPER + COMMA", hl.dsp.exec_cmd("ghostty nvim ~/.config/hypr/hyprland.lua"))
hl.bind("SUPER + SLASH", hl.dsp.exec_cmd("~/.local/bin/keyguide"))
-- Label every visible window, Herdr pane and editor window, then focus the typed one.
hl.bind("SUPER + F", hl.dsp.exec_cmd("~/.local/bin/winhint"))

-- Top-level menus: Space Space in editors, Cmd+Space for Hyprland (leader.lua),
-- Alt+Space for Herdr.
hl.bind("ALT + SPACE", keys.herdr_menu)
-- Yank: copy visible Herdr text by hint, from any window.
hl.bind("SUPER + Y", keys.herdr_yank)

require("keybindings")
require("leader")

hl.bind("PRINT", hl.dsp.exec_cmd("sh -c 'mkdir -p ~/Pictures; grim ~/Pictures/screenshot-$(date +%Y%m%d-%H%M%S).png'"))
-- kanata sends Print for Cmd+Shift+3 and Shift+Print for Cmd+Shift+4.
hl.bind("SHIFT + PRINT", hl.dsp.exec_cmd("sh -c 'region=$(slurp) && grim -g \"$region\" - | wl-copy --type image/png'"))
hl.bind("XF86AudioRaiseVolume", hl.dsp.exec_cmd("wpctl set-volume -l 1.0 @DEFAULT_AUDIO_SINK@ 5%+"), { locked = true, repeating = true })
hl.bind("XF86AudioLowerVolume", hl.dsp.exec_cmd("wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%-"), { locked = true, repeating = true })
hl.bind("XF86AudioMute", hl.dsp.exec_cmd("wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle"))
hl.bind("XF86AudioMicMute", hl.dsp.exec_cmd("~/.local/bin/system-info"))
hl.bind("XF86LaunchA", hl.dsp.exec_cmd("~/.local/bin/connect-airpods"))
hl.bind("XF86MonBrightnessUp", hl.dsp.exec_cmd("brightnessctl set +5%"), { locked = true, repeating = true })
hl.bind("XF86MonBrightnessDown", hl.dsp.exec_cmd("brightnessctl set 5%-"), { locked = true, repeating = true })
