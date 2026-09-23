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
        for directory in ("bin", "base", "modules", "desktop"):
            (self.root / directory).mkdir()
        # Redirect the device-tree read as well as stubbing the identity commands.
        makefile = (REPO / "Makefile").read_text().replace(
            "/proc/device-tree/chosen/asahi,efi-system-partition",
            str(self.root / "efi-partuuid"),
        )
        (self.root / "Makefile").write_text(makefile)
        (self.root / "efi-partuuid").write_text("test-partuuid")
        (self.root / "base/devices.json").write_text("{}")
        (self.root / "channels.scm").write_text("test channels")
        (self.root / "desktop/system.scm").write_text("test system")
        self.log = self.root / "commands.log"
        self.stub("guix", f'''printf '%s gc=%s\\n' "$*" "${{GC_FREE_SPACE_DIVISOR-unset}}" >> "{self.log}"
# Existing path solely for the receipt's existence check; not a real system.
printf '/gnu/store/\\n'
''')
        # Model sudo's environment filtering. Only assignments after sudo survive.
        self.stub("sudo", f'''echo sudo >> "{self.log}"
exec env -i PATH="$PATH" "$@"
''')
        self.stub("readlink", "echo /gnu/store/fake-system\n")
        self.stub("findmnt", '''case "$*" in
  *'/boot/efi') echo test-esp ;;
  *) echo "${TEST_ROOT_UUID:-test-root}" ;;
esac
''')
        self.env = dict(os.environ)
        for key in ("GC_FREE_SPACE_DIVISOR", "MAKEFLAGS", "MFLAGS", "MAKELEVEL",
                    "REUSE_BUILD", "TEST_ROOT_UUID"):
            self.env.pop(key, None)
        self.env["PATH"] = str(self.root / "bin") + os.pathsep + os.environ["PATH"]

    def stub(self, name, body):
        path = self.root / "bin" / name
        path.write_text("#!/bin/sh\nset -eu\n" + body)
        path.chmod(0o755)

    def make(self, *args, answer="y\n", success=True):
        result = subprocess.run(
            ["make", "--no-print-directory", "BASE=base", "ROOT_UUID=test-root",
             "ESP_UUID=test-esp", "ESP_PARTUUID=test-partuuid", *args],
            cwd=self.root, env=self.env, input=answer, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        self.assertEqual(result.returncode == 0, success, result.stdout)
        return result.stdout

    def commands(self):
        return self.log.read_text() if self.log.exists() else ""

    def test_apply_gc_default_survives_sudo(self):
        self.make("build")
        self.make("apply")
        self.assertIn("sudo\n", self.commands())
        self.assertIn("system reconfigure", self.commands())
        self.assertTrue(self.commands().rstrip().endswith("gc=1"))

    def test_apply_gc_override_survives_sudo(self):
        self.env["GC_FREE_SPACE_DIVISOR"] = "3"
        self.make("build")
        self.make("apply")
        self.assertTrue(self.commands().rstrip().endswith("gc=3"))

    def test_switch_builds_then_applies_by_default(self):
        self.make("switch")
        commands = self.commands()
        self.assertEqual(commands.count("system build"), 1)
        self.assertLess(commands.index("system build"), commands.index("sudo"))
        self.assertIn("system reconfigure", commands)

    def test_switch_reuses_reviewed_build(self):
        self.make("build")
        self.log.write_text("")
        self.make("switch", "REUSE_BUILD=1")
        self.assertNotIn("system build", self.commands())
        self.assertIn("system reconfigure", self.commands())

    def test_reuse_rejects_missing_receipt(self):
        self.make("switch", "REUSE_BUILD=1", success=False)
        self.assertEqual(self.commands(), "")

    def test_reuse_rejects_stale_sources(self):
        self.make("build")
        self.log.write_text("")
        (self.root / "desktop/system.scm").write_text("changed system")
        self.make("switch", "REUSE_BUILD=1", success=False)
        self.assertEqual(self.commands(), "")

    def test_reuse_rejects_wrong_config(self):
        self.make("build")
        self.log.write_text("")
        receipt = self.root / "local/system-build.receipt"
        receipt.write_text(receipt.read_text().replace(
            "config=desktop/system.scm", "config=other.scm"))
        self.make("switch", "REUSE_BUILD=1", success=False)
        self.assertEqual(self.commands(), "")

    def test_reuse_rejects_missing_output(self):
        self.make("build")
        self.log.write_text("")
        receipt = self.root / "local/system-build.receipt"
        receipt.write_text(receipt.read_text().replace(
            "output=/gnu/store/", "output=/gnu/store/nonexistent-switch-test-output"))
        self.make("switch", "REUSE_BUILD=1", success=False)
        self.assertEqual(self.commands(), "")

    def test_reuse_still_requires_confirmation(self):
        self.make("build")
        self.log.write_text("")
        self.make("switch", "REUSE_BUILD=1", answer="n\n", success=False)
        self.assertEqual(self.commands(), "")

    def test_switch_rejects_invalid_reuse_option(self):
        self.make("switch", "REUSE_BUILD=yes", success=False)
        self.assertEqual(self.commands(), "")

    def test_failed_build_does_not_apply(self):
        self.stub("guix", "exit 1\n")
        self.make("switch", success=False)
        self.assertNotIn("sudo", self.commands())

    def test_cancel_never_reaches_sudo(self):
        self.make("build")
        self.make("apply", answer="n\n", success=False)
        self.assertNotIn("sudo", self.commands())

    def test_wrong_root_never_reaches_sudo(self):
        self.make("build")
        self.env["TEST_ROOT_UUID"] = "wrong-root"
        self.make("apply", success=False)
        self.assertNotIn("sudo", self.commands())


if __name__ == "__main__":
    unittest.main()
