import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib
import unittest

HERE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("desktop", HERE / "desktop.py")
desktop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(desktop)


class DesktopTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.base = self.root / "base"
        self.base.mkdir()
        self.saved = {"schema": 2,
                      "root": {"uuid": "11111111-2222-3333-4444-555555555555"},
                      "esp": {"uuid": "1234-ABCD",
                              "partuuid": "bbbbbbbb-2222-3333-4444-555555555555"}}
        self.save()
        shutil.copy2(HERE / "channels.scm", self.base / "channels.scm")
        self.output = self.root / "desktop"

    def save(self):
        (self.base / "devices.json").write_text(json.dumps(self.saved))

    def stage(self):
        return desktop.stage(self.base, self.output)

    def test_self_contained_snapshot_and_manifest(self):
        self.stage()
        self.assertEqual((self.output / "devices.json").read_bytes(),
                         (self.base / "devices.json").read_bytes())
        self.assertEqual((self.output / "channels.scm").read_bytes(),
                         (HERE / "channels.scm").read_bytes())
        self.assertIn("make-familiar-os", (self.output / "system.scm").read_text())
        system = (self.output / "system.scm").read_text()
        self.assertIn(self.saved["root"]["uuid"], system)
        self.assertIn(self.saved["esp"]["uuid"], system)
        hashes = json.loads((self.output / "sources.json").read_text())
        for name, digest in hashes.items():
            self.assertEqual(hashlib.sha256((self.output / name).read_bytes()).hexdigest(), digest)
        self.assertFalse(any(p.is_symlink() for p in self.output.rglob("*")))
        self.assertNotIn(str(self.root), (self.output / "modules/engstrand/desktop-files.scm").read_text())

    def test_never_overwrites(self):
        self.stage()
        with self.assertRaisesRegex(ValueError, "exists"):
            self.stage()

    def test_rejects_bad_schema_ids_and_protected_esp(self):
        for role, key, value in ((None, "schema", 1), ("root", "uuid", 'bad"uuid'),
                                 ("esp", "uuid", "5CDF-1DF4"),
                                 ("esp", "partuuid", "ea8adc5b-ec2d-4df1-913b-f0f05ff85367")):
            old = json.loads(json.dumps(self.saved))
            target = self.saved if role is None else self.saved[role]
            target[key] = value
            self.save()
            with self.subTest(role=role, key=key), self.assertRaises(ValueError):
                self.stage()
            self.assertFalse(self.output.exists())
            self.saved = old

    def test_rejects_channel_drift(self):
        (self.base / "channels.scm").write_text("changed")
        with self.assertRaisesRegex(ValueError, "pins differ"):
            self.stage()

    def test_portable_shell_and_jj(self):
        self.stage()
        files = self.output / "modules/engstrand/desktop-files"
        self.assertFalse((files / ".profile").exists())
        self.assertFalse((files / ".config/fish/conf.d/00-paths.fish").exists())
        self.assertFalse((files / ".config/fish/conf.d/supervisor.fish").exists())
        paths = (files / ".config/fish/conf.d/00-guix-paths.fish").read_text()
        self.assertIn("/run/privileged/bin", paths)
        self.assertIn("fish_add_path", paths)
        self.assertIn('DOOMDIR "$HOME/.config/doom"', paths)
        self.assertNotIn("set -gx PATH", paths)
        git = (files / ".config/git/config").read_text()
        self.assertIn('[credential "https://github.com"]', git)
        self.assertIn("!gh auth git-credential", git)
        noctalia = tomllib.loads((files / ".config/noctalia/config.toml").read_text())
        self.assertEqual(noctalia["accessibility"]["ui_scale"], 1.8)
        self.assertFalse(noctalia["bar"]["default"]["enabled"])
        jjui = tomllib.loads((files / ".config/jjui/config.toml").read_text())
        self.assertEqual(jjui["ui"]["auto_refresh_interval"], 0)
        self.assertEqual(jjui["preview"]["revision_command"][0], "jjui-show-fancy")
        self.assertTrue((files / ".pi/README.md").is_file())
        self.assertTrue((files / ".pi/agent/keybindings.json").is_file())
        self.assertTrue((files / ".pi/agent/extensions/tokenjuice/src/extension.ts").is_file())
        self.assertTrue((files / ".pi/agent/skills/autoresearch-create/SKILL.md").is_file())
        self.assertFalse((files / ".pi/agent/auth.json").exists())
        jj = tomllib.loads((files / ".config/jj/config.toml").read_text())
        self.assertEqual(jj["fsmonitor"]["backend"], "none")
        self.assertFalse(jj["fsmonitor"]["watchman"]["register-snapshot-trigger"])
        self.assertEqual(jj["user"]["name"], "engstrand")
        json.loads((files / ".config/waybar/config.jsonc").read_text())

    def test_herdr_and_pi_config(self):
        self.stage()
        files = self.output / "modules/engstrand/desktop-files"
        herdr = tomllib.loads((files / ".config/herdr/config.toml").read_text())
        self.assertEqual(herdr["terminal"]["default_shell"], "fish")
        chords = [key["key"] for key in herdr["keys"]["command"]]
        self.assertIn("ctrl+alt+e", chords)
        pi = json.loads((files / ".pi/agent/settings.json").read_text())
        self.assertEqual(pi["shellPath"], "/run/current-system/profile/bin/bash")
        self.assertTrue(pi["quietStartup"])
        for source_dir in (HERE / "desktop/bin", HERE / "desktop/shared/herdr/bin"):
            for source in source_dir.iterdir():
                if source.is_file():
                    path = files / ".local/bin" / source.name
                    self.assertTrue(path.is_file(), source.name)
                    self.assertGreater(path.stat().st_mode & 0o111, 0, source.name)

    @unittest.skipUnless(shutil.which("python3"), "Python not available")
    def test_launcher_scripts_compile(self):
        self.stage()
        files = self.output / "modules/engstrand/desktop-files/.local/bin"
        for source in (HERE / "desktop/bin").iterdir():
            if source.is_file():
                subprocess.run(["python3", "-m", "py_compile", str(files / source.name)],
                               check=True, capture_output=True, text=True)

    @unittest.skipUnless(shutil.which("fish"), "Fish not available")
    def test_fish_syntax_and_preserves_inherited_path(self):
        self.stage()
        files = self.output / "modules/engstrand/desktop-files/.config/fish"
        for path in files.rglob("*.fish"):
            subprocess.run(["fish", "--no-config", "--no-execute", str(path)], check=True,
                           capture_output=True, text=True)
        script = f'''set -gx PATH /guix-test-sentinel $PATH
source {files}/conf.d/00-guix-paths.fish
contains -- /guix-test-sentinel $PATH; or exit 1
'''
        subprocess.run(["fish", "--no-config", "-c", script], check=True,
                       capture_output=True, text=True)

    def test_live_editor_sources_are_staged(self):
        self.stage()
        files = self.output / "modules/engstrand/desktop-files"
        nvim = files / ".config/nvim"
        self.assertTrue((nvim / "init.fnl").is_file())
        self.assertTrue((nvim / "fnl/config/lazy.fnl").is_file())
        self.assertTrue((nvim / "lua/config/options.lua").is_file())
        hypr = files / ".config/hypr"
        hyprland = hypr / "hyprland.conf"
        self.assertTrue(hyprland.is_file())
        config = hyprland.read_text()
        self.assertIn("XF86AudioRaiseVolume", config)
        self.assertIn("XF86AudioLowerVolume", config)
        self.assertIn("XF86AudioMute", config)
        self.assertIn("XF86MonBrightnessUp", config)
        self.assertIn("XF86MonBrightnessDown", config)
        self.assertIn("workspace-or-herdr-tab 1", config)
        self.assertIn("workspace-or-herdr-tab 2", config)
        self.assertIn("workspace-or-herdr-tab 3", config)
        self.assertIn("workspace-or-herdr-tab 4", config)
        self.assertNotIn("hypridle", config)
        idle = (hypr / "hypridle.conf").read_text()
        self.assertNotIn("hyprlock", idle)
        self.assertFalse((hypr / "hyprland.lua").exists())
        scm = (self.output / "modules/engstrand/desktop-files.scm").read_text()
        self.assertIn('(cons ".config/hypr/hyprland.conf" "/home/', scm)
        self.assertNotIn("live-desktop-file", scm)
        doom = files / ".config/doom"
        self.assertTrue((doom / "init.el").is_file())
        self.assertTrue((doom / "config.el").is_file())
        self.assertIn("wl-copy", (doom / "config.el").read_text())


if __name__ == "__main__":
    unittest.main()
