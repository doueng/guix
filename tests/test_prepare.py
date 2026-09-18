import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("prepare", HERE / "prepare.py")
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)

ROOT_UUID = "11111111-2222-3333-4444-555555555555"


def devices():
    return [
        {"path": "/dev/sda", "type": "disk", "tran": "usb", "size": 500 * 1024**3,
         "serial": "T7-test", "model": "PSSD T7", "ro": False, "children": [
             {"path": "/dev/sda2", "type": "part", "fstype": "ext4", "uuid": ROOT_UUID,
              "partuuid": "aaaaaaaa-2222-3333-4444-555555555555",
              "parttype": "0fc63daf-8483-4772-8e79-3d69d8477de4",
              "label": "guix-root", "ro": False, "mountpoints": []}]},
        {"path": "/dev/nvme0n1", "type": "disk", "tran": "nvme", "serial": "apple-test",
         "model": "APPLE SSD", "size": 250 * 1024**3, "children": [
            {"path": "/dev/nvme0n1p9", "type": "part", "fstype": "vfat", "uuid": "1234-ABCD",
             "parttype": p.ESP_TYPE, "partuuid": "bbbbbbbb-2222-3333-4444-555555555555",
             "ro": False, "mountpoints": []},
            {"path": "/dev/nvme0n1p7", "type": "part", "fstype": "vfat", "uuid": p.CURRENT_ESP,
             "parttype": p.ESP_TYPE, "partuuid": p.CURRENT_ESP_PARTUUID,
             "ro": False, "mountpoints": ["/boot/efi"]}]}]


class PrepareTests(unittest.TestCase):
    def test_inventory_requests_tree_without_name_column(self):
        with patch.object(p, "run", return_value=json.dumps({"blockdevices": devices()})) as run:
            self.assertEqual(p.inventory(), devices())
            self.assertIn("--tree", run.call_args.args)

    def test_refuse_bootstrap_partition_as_root(self):
        nodes = devices()
        nodes[0]["children"][0]["label"] = "guix-bootstrap"
        with self.assertRaises(ValueError):
            p.checked_pair(nodes, "/dev/sda2", "/dev/nvme0n1p9")

    def test_new_usb_disk(self):
        p.check_blank_target(devices()[0])

    def test_refuse_internal_readonly_small_unknown(self):
        for changes in ({"tran": "nvme"}, {"tran": None}, {"ro": True},
                        {"size": 10}, {"path": "/dev/nvme1n1"}, {"type": "part"}):
            disk = devices()[0]
            disk.update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                p.check_blank_target(disk)

    def test_refuse_mounted_swap_or_holders(self):
        for changes in ({"mountpoints": ["/"]}, {"mountpoints": ["[SWAP]"]},
                        {"mountpoints": ["/run/media/user/Disk"]}, {"type": "crypt"}, {"ro": True}):
            disk = devices()[0]
            disk["children"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                p.check_blank_target(disk)

    def test_pair(self):
        root, esp = p.checked_pair(devices(), "/dev/sda2", "/dev/nvme0n1p9")
        self.assertEqual(root["uuid"], ROOT_UUID)
        self.assertEqual(esp["uuid"], "1234-ABCD")

    def test_refuse_existing_esp_and_wrong_type(self):
        for changes in ({"uuid": p.CURRENT_ESP}, {"mountpoints": ["/boot/efi"]},
                        {"fstype": "apfs"}, {"parttype": "linux"}, {"ro": True},
                        {"uuid": '1234-ABCD\" malicious'}):
            nodes = devices()
            nodes[1]["children"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                p.checked_pair(nodes, "/dev/sda2", "/dev/nvme0n1p9")

    def test_refuse_duplicate_uuid(self):
        nodes = devices()
        extra = copy.deepcopy(nodes[0]["children"][0])
        extra["path"] = "/dev/sda3"
        nodes[0]["children"].append(extra)
        with self.assertRaises(ValueError):
            p.checked_pair(nodes, "/dev/sda2", "/dev/nvme0n1p9")

    def test_refuse_live_root(self):
        nodes = devices()
        nodes[0]["children"][0]["mountpoints"] = ["/"]
        with self.assertRaises(ValueError):
            p.checked_pair(nodes, "/dev/sda2", "/dev/nvme0n1p9")

    def test_plan_is_only_text_and_needs_stable_id(self):
        with self.assertRaises(ValueError):
            p.plan(devices(), "/dev/sda")
        with patch.object(p.os.path, "realpath", return_value="/dev/sda"), patch.object(p, "run") as run:
            text = p.plan(devices(), "/dev/disk/by-id/usb-example")
            run.assert_not_called()
        self.assertIn("NOTHING HAS BEEN EXECUTED", text)
        self.assertIn("size=96GiB", text)
        self.assertNotIn("mkfs.vfat", text)

    def test_generate_only_local_config_and_refuse_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "config"
            p.configure(devices(), "/dev/sda2", "/dev/nvme0n1p9", output)
            self.assertIn(ROOT_UUID, (output / "system.scm").read_text())
            self.assertIn("(guix channels)", (output / "system.scm").read_text())
            self.assertEqual((output / "channels.scm").read_bytes(), (HERE / "channels.scm").read_bytes())
            self.assertTrue((output / "modules/engstrand/asahi.scm").is_file())
            self.assertEqual(json.loads((output / "devices.json").read_text())["esp"]["uuid"], "1234-ABCD")
            with self.assertRaises(ValueError):
                p.configure(devices(), "/dev/sda2", "/dev/nvme0n1p9", output)

    def test_mount_uuid_and_rw(self):
        expected = {"uuid": ROOT_UUID, "fstype": "ext4", "path": "/dev/sda2"}
        good = dict(expected, options="rw,relatime", source="/dev/sda2",
                    target="/mnt/guix", fsroot="/")
        p.verify_mount(good, expected, "/mnt/guix")
        for changes in ({"uuid": "wrong"}, {"options": "ro,relatime"}, {"fstype": "btrfs"},
                        {"source": "/dev/sda1"}, {"source": "/dev/sda2[/subdir]"},
                        {"target": "/mnt/other"}, {"fsroot": "/subdir"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                p.verify_mount(dict(good, **changes), expected, "/mnt/guix")

    def snapshot(self, nodes):
        return copy.deepcopy(p.identity_record(nodes, *p.checked_pair(nodes, "/dev/sda2", "/dev/nvme0n1p9")))

    def check_saved(self, nodes, saved):
        paths = {n.get("uuid"): n["path"] for n, _ in p.flatten(nodes) if n.get("uuid")}
        realpath = p.os.path.realpath
        with patch.object(p.os.path, "realpath", side_effect=lambda path:
                          paths.get(str(path).removeprefix("/dev/disk/by-uuid/"), realpath(path))):
            return p.checked_saved_pair(nodes, saved)

    def test_saved_identities_allow_device_renumbering(self):
        nodes = devices()
        saved = self.snapshot(nodes)
        nodes[0]["path"] = "/dev/sdb"
        nodes[0]["children"][0]["path"] = "/dev/sdb2"
        nodes[1]["children"][0]["path"] = "/dev/nvme0n1p4"
        nodes[1]["children"][1]["path"] = "/dev/nvme0n1p11"
        self.assertEqual(self.check_saved(nodes, saved)[1]["path"], "/dev/nvme0n1p4")

    def test_saved_partuuid_changes_rejected(self):
        for disk, child in ((0, 0), (1, 0), (1, 1)):
            nodes = devices()
            saved = self.snapshot(nodes)
            nodes[disk]["children"][child]["partuuid"] = "cccccccc-2222-3333-4444-555555555555"
            with self.subTest(disk=disk, child=child), self.assertRaises(ValueError):
                self.check_saved(nodes, saved)

    def test_missing_duplicate_and_case_variant_identities(self):
        for field in ("uuid", "partuuid"):
            for value in (None, "", devices()[1]["children"][0][field].upper()):
                nodes = devices()
                nodes[0]["children"][0][field] = value
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    p.checked_pair(nodes, "/dev/sda2", "/dev/nvme0n1p9")

    def test_protected_partuuid_rejected_even_with_new_fat_uuid(self):
        nodes = devices()
        nodes[1]["children"] = nodes[1]["children"][:1]
        nodes[1]["children"][0]["partuuid"] = p.CURRENT_ESP_PARTUUID
        with self.assertRaises(ValueError):
            p.checked_pair(nodes, "/dev/sda2", "/dev/nvme0n1p9")

    def test_currently_booted_esp_rejected_despite_different_mount(self):
        nodes = devices()
        chosen = nodes[1]["children"][0]["partuuid"].encode() + b"\0"
        with patch.object(p.Path, "exists", return_value=True), \
                patch.object(p.Path, "read_bytes", return_value=chosen):
            with self.assertRaisesRegex(ValueError, "currently booted"):
                p.checked_pair(nodes, "/dev/sda2", "/dev/nvme0n1p9")

    def test_parent_identity_changes_rejected(self):
        for disk in (0, 1):
            for field, value in (("serial", "other"), ("model", "other"), ("size", 1)):
                nodes = devices()
                saved = self.snapshot(nodes)
                nodes[disk][field] = value
                with self.subTest(disk=disk, field=field), self.assertRaises(ValueError):
                    self.check_saved(nodes, saved)

    def test_missing_parent_identity_and_protected_mount(self):
        for disk, field in ((0, "serial"), (1, "model")):
            nodes = devices()
            del nodes[disk][field]
            with self.assertRaises(ValueError):
                self.snapshot(nodes)
        nodes = devices()
        nodes[1]["children"][1]["mountpoints"] = []
        with self.assertRaises(ValueError):
            self.snapshot(nodes)

    def test_legacy_or_incomplete_record_requires_review(self):
        for key in ("schema", "root_disk", "protected_esp"):
            nodes = devices()
            saved = self.snapshot(nodes)
            del saved[key]
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check_saved(nodes, saved)

    def test_check_mounts_complete_and_reject_extra_mount(self):
        with tempfile.TemporaryDirectory() as directory:
            nodes = devices()
            target = Path(directory).resolve() / "target"
            config = Path(directory) / "config"
            p.configure(nodes, "/dev/sda2", "/dev/nvme0n1p9", config)
            root, esp = nodes[0]["children"][0], nodes[1]["children"][0]
            root["mountpoints"] = [str(target)]
            esp["mountpoints"] = [str(target / "boot/efi")]
            for name in ("vendorfw/firmware.cpio", "m1n1/boot.bin"):
                path = target / "boot/efi" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"test")
            def mount_record(path):
                node = next(n for n, _ in p.flatten(nodes) if str(path) in p.mounts(n))
                return dict(node, source=node["path"], target=str(path), fsroot="/", options="rw")
            saved = json.loads((config / "devices.json").read_text())
            checked = self.check_saved(nodes, saved)
            with patch.object(p, "checked_saved_pair", return_value=checked), \
                    patch.object(p, "mount_record", side_effect=mount_record), \
                    patch.object(p, "run", return_value="vendorfw/brcm/test\n"):
                p.check_mounts(nodes, config, target)
                root["mountpoints"].append("/unexpected")
                with self.assertRaises(ValueError):
                    p.check_mounts(nodes, config, target)


if __name__ == "__main__":
    unittest.main()
