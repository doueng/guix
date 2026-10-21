-- Programs started once per session.
hl.on("hyprland.start", function()
  hl.exec_cmd("dbus-update-activation-environment WAYLAND_DISPLAY XDG_CURRENT_DESKTOP HYPRLAND_INSTANCE_SIGNATURE")
  hl.exec_cmd("~/.local/bin/dusk")
  hl.exec_cmd("/run/current-system/profile/libexec/polkit-gnome-authentication-agent-1")
  hl.exec_cmd("~/.local/bin/herdr-terminal")
  hl.exec_cmd("~/.local/bin/keyleader listen")
  hl.exec_cmd("~/.local/bin/hyprkan")
end)
