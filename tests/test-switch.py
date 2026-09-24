"""Exercise the guarded Make recipes with fake host commands."""
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
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "commands.log"
        self.profile = self.root / "profile"
        self.profile.mkdir()
        (self.profile / "bin").mkdir()
        self.write_stub("readlink", '''case "$*" in
  *"/run/current-system") echo /gnu/store/fake-system ;;
  *) echo "$*" ;;
esac
''')
        self.write_stub("findmnt", '''case "$*" in
  *"UUID /") echo c4f25409-b1a5-4ef0-8ac9-8e75f011668c ;;
  *"UUID /boot/efi") echo 5CDF-1DF4 ;;
  *) exit 1 ;;
esac
''')
        self.write_stub("guix", f'echo "guix $*" >> "{self.log}"\necho {self.profile}\n')
        self.write_stub("sudo", f'echo "sudo $*" >> "{self.log}"\nexit 0\n')
        self.env = dict(os.environ)
        self.env["PATH"] = str(self.bin) + os.pathsep + self.env["PATH"]

    def write_stub(self, name, content):
        path = self.bin / name
        path.write_text("#!/bin/sh\nset -eu\n" + content)
        path.chmod(0o755)

    def make(self, *args):
        return subprocess.run(
            ["make", "--no-print-directory", *args], cwd=REPO, env=self.env,
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )

    def test_switch_runs_one_pinned_reconfigure(self):
        result = self.make("switch")
        self.assertEqual(result.returncode, 0, result.stdout)
        commands = self.log.read_text()
        self.assertIn("guix time-machine", commands)
        self.assertIn("sudo", commands)
        self.assertIn("system reconfigure --no-kexec", commands)
        self.assertEqual(commands.count("system reconfigure"), 1)

    def test_non_guix_system_stops_before_disk_checks(self):
        self.write_stub("readlink", 'echo /nix/store/not-guix\\n')
        result = self.make("switch")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.log.exists())

    def test_wrong_root_stops_before_sudo(self):
        self.write_stub("findmnt", 'echo wrong-root\n')
        result = self.make("switch")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.log.exists())

    def test_wrong_esp_stops_before_sudo(self):
        self.write_stub("findmnt", '''case "$*" in
  *"UUID /") echo c4f25409-b1a5-4ef0-8ac9-8e75f011668c ;;
  *) echo wrong-esp ;;
esac
''')
        result = self.make("switch")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.log.exists())


if __name__ == "__main__":
    unittest.main()
