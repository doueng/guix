"""Exercise Make recipes with fake Guix/sudo/hardware; never activate a system."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parent.parent


class SwitchTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "bin").mkdir()
        # Redirect the device-tree read; all privileged commands are stubbed.
        (self.root / "Makefile").write_text((REPO / "Makefile").read_text().replace(
            "/proc/device-tree/chosen/asahi,efi-system-partition",
            str(self.root / "efi-partuuid")))
        (self.root / "efi-partuuid").write_text("test-partuuid")
        self.log = self.root / "commands.log"
        self.stub("guix", f'''printf '%s gc=%s base=%s\\n' "$*" "${{GC_FREE_SPACE_DIVISOR-unset}}" "${{GUIX_BASE-unset}}" >> "{self.log}"
''')
        # Model sudo's environment filtering: only explicit assignments survive.
        self.stub("sudo", f'''echo sudo >> "{self.log}"
exec env -i PATH="$PATH" "$@"
''')
        self.stub("readlink", "echo /gnu/store/fake-system\n")
        self.stub("findmnt", '''case "$*" in
  *'/boot/efi') echo test-esp ;;
  *) echo test-root ;;
esac
''')
        self.env = dict(os.environ)
        for key in ("GC_FREE_SPACE_DIVISOR", "MAKEFLAGS", "MFLAGS", "MAKELEVEL"):
            self.env.pop(key, None)
        self.env["PATH"] = str(self.root / "bin") + os.pathsep + os.environ["PATH"]

    def stub(self, name, body):
        path = self.root / "bin" / name
        path.write_text("#!/bin/sh\nset -eu\n" + body)
        path.chmod(0o755)

    def make(self, *args, success=True):
        result = subprocess.run(
            ["make", "--no-print-directory", "BASE=base", "ROOT_UUID=test-root",
             "ESP_UUID=test-esp", "ESP_PARTUUID=test-partuuid", *args],
            cwd=self.root, env=self.env, input="", text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        self.assertEqual(result.returncode == 0, success, result.stdout)
        return self.log.read_text() if self.log.exists() else ""

    def test_switch_reconfigures_once_without_receipt_or_prompt(self):
        commands = self.make("switch")
        self.assertEqual(commands.count("sudo\n"), 1)
        self.assertEqual(commands.count("system reconfigure"), 1)
        self.assertNotIn("system build", commands)
        self.assertIn(f"time-machine -C {self.root}/channels.scm --", commands)
        self.assertIn(f"gc=1 base={self.root}/base", commands)

    def test_gc_override_survives_sudo(self):
        self.env["GC_FREE_SPACE_DIVISOR"] = "3"
        self.assertIn("gc=3", self.make("switch"))

    def test_apply_is_alias(self):
        self.assertEqual(self.make("apply", "switch").count("system reconfigure"), 1)

    def test_build_does_not_activate_or_write_receipt(self):
        commands = self.make("build")
        self.assertIn("system build", commands)
        self.assertNotIn("sudo", commands)
        self.assertFalse((self.root / "local").exists())

    def test_wrong_native_system_stops_before_sudo(self):
        self.stub("readlink", "echo /nix/store/not-guix\n")
        self.assertEqual(self.make("switch", success=False), "")

    def test_wrong_disks_stop_before_sudo(self):
        for variable in ("ROOT_UUID", "ESP_UUID", "ESP_PARTUUID"):
            with self.subTest(variable=variable):
                self.assertEqual(self.make("switch", f"{variable}=wrong",
                                           success=False), "")

    def test_missing_device_tree_stops_before_sudo(self):
        (self.root / "efi-partuuid").unlink()
        self.assertEqual(self.make("switch", success=False), "")

    def test_absolute_config_path(self):
        config = self.root / "external.scm"
        self.assertIn(f' {config} gc=', self.make("switch", f"CONFIG={config}"))

    def test_guix_failure_propagates(self):
        self.stub("guix", "exit 1\n")
        self.make("switch", success=False)


if __name__ == "__main__":
    unittest.main()
