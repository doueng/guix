-- SDDM starts Hyprland outside the Fish session, so ~/.local/bin is not
-- guaranteed to be on PATH. Use the Guix profile and Home-installed helper
-- explicitly for bindings invoked by keyd.
local profile = "/run/current-system/profile/bin/"
local terminal = profile .. "kitty"
local launcher = os.getenv("HOME") .. "/.local/bin/custom-launcher"

local function command(value)
  return hl.dsp.exec_cmd(value)
end

hl.monitor({ output = "eDP-1", mode = "2560x1600", position = "auto", scale = 1 })
hl.config({
  general = { gaps_in = 8, gaps_out = 14, border_size = 0, layout = "dwindle" },
  decoration = { rounding = 18, shadow = { enabled = true, range = 18 },
    blur = { enabled = true, size = 4, passes = 2 } },
  animations = { enabled = false },
  dwindle = { force_split = 2, preserve_split = true },
  cursor = { inactive_timeout = 2, enable_hyprcursor = false },
  env = { "XCURSOR_THEME,Adwaita", "XCURSOR_SIZE,20" },
  input = { repeat_delay = 250, repeat_rate = 30, sensitivity = 0.3,
    accel_profile = "adaptive", scroll_method = "2fg",
    touchpad = { tap_to_click = false, natural_scroll = true,
      middle_button_emulation = false, scroll_factor = 0.5,
      clickfinger_behavior = true, disable_while_typing = true } },
  misc = { disable_hyprland_logo = true, force_default_wallpaper = 0 },
})

hl.on("hyprland.start", function()
  hl.exec_cmd("dbus-update-activation-environment WAYLAND_DISPLAY XDG_CURRENT_DESKTOP HYPRLAND_INSTANCE_SIGNATURE")
  hl.exec_cmd("/run/current-system/profile/libexec/polkit-gnome-authentication-agent-1")
  hl.exec_cmd("waybar")
  hl.exec_cmd("hypridle")
  hl.exec_cmd(terminal)
end)

hl.bind("SUPER + RETURN", command(terminal))
hl.bind("SUPER + SPACE", command(launcher))
hl.bind("SUPER + U", hl.dsp.focus({ workspace = 2 }))
hl.bind("SUPER + SHIFT + U", hl.dsp.focus({ workspace = 3 }))
hl.bind("SUPER + E", command(terminal .. " nvim"))
hl.bind("SUPER + D", command(terminal))
hl.bind("SUPER + G", command(terminal .. " fish -C 'jj status'"))
hl.bind("SUPER + S", command("herdr"))
hl.bind("SUPER + COMMA", command(terminal .. " nvim ~/.config/hypr/hyprland.lua"))
hl.bind("SUPER + SHIFT + L", command("/run/privileged/bin/hyprlock"))
hl.bind("CTRL + ALT + L", command("/run/privileged/bin/hyprlock"))
hl.bind("ALT + RETURN", command(terminal))
hl.bind("ALT + SPACE", command(launcher))
hl.bind("ALT + U", hl.dsp.focus({ workspace = 2 }))
hl.bind("ALT + SHIFT + U", hl.dsp.focus({ workspace = 3 }))
hl.bind("ALT + E", command(terminal .. " nvim"))
hl.bind("ALT + D", command(terminal))
hl.bind("ALT + G", command(terminal .. " fish -C 'jj status'"))
hl.bind("ALT + W", hl.dsp.window.close())
hl.bind("ALT + F", hl.dsp.window.fullscreen({ action = "toggle" }))
for _, direction in ipairs({ "left", "down", "up", "right" }) do
  hl.bind("ALT + " .. direction, hl.dsp.focus({ direction = direction }))
  hl.bind("ALT + SHIFT + " .. direction, hl.dsp.window.move({ direction = direction }))
end
for workspace = 1, 10 do
  local key = workspace % 10
  hl.bind("ALT + " .. key, hl.dsp.focus({ workspace = workspace }))
  hl.bind("ALT + SHIFT + " .. key, hl.dsp.window.move({ workspace = workspace }))
end
hl.bind("PRINT", command("sh -c 'mkdir -p ~/Pictures; grim ~/Pictures/screenshot-$(date +%Y%m%d-%H%M%S).png'"))
hl.bind("CTRL + SHIFT + 4", command("sh -c 'region=$(slurp) && grim -g \"$region\" - | wl-copy --type image/png'"))
hl.bind("XF86MonBrightnessUp", command("brightnessctl set +5%"), { locked = true })
hl.bind("XF86MonBrightnessDown", command("brightnessctl set 5%-"), { locked = true })
