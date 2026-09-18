#!/usr/bin/env python3
"""Stage an opt-in, self-contained Guix desktop; never mount or activate anything."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import time

HERE = Path(__file__).resolve().parent
FEATURES = HERE / "desktop/shared"


def stage(base, output):
    base, output = Path(base).resolve(), Path(output).resolve()
    saved = json.loads((base / "devices.json").read_text())
    if saved.get("schema") != 2:
        raise ValueError("Expected the reviewed schema-2 installation record")
    root, esp = saved["root"]["uuid"], saved["esp"]["uuid"]
    if not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", root):
        raise ValueError("Invalid root UUID")
    if not re.fullmatch(r"[0-9a-fA-F]{4}-[0-9a-fA-F]{4}", esp):
        raise ValueError("Invalid ESP UUID")
    if (esp.upper() == "5CDF-1DF4" or
            saved["esp"]["partuuid"].lower() == "ea8adc5b-ec2d-4df1-913b-f0f05ff85367"):
        raise ValueError("Refusing the protected NixOS ESP")
    if (base / "channels.scm").read_bytes() != (HERE / "channels.scm").read_bytes():
        raise ValueError("Installed channel pins differ; review before staging")
    if output.exists():
        raise ValueError("Output exists; choose a fresh directory")
    output.mkdir(parents=True, mode=0o700)
    now = time.time()

    def reset_times(path):
        for p in [path, *path.rglob("*")]:
            os.utime(p, (now, now))

    shutil.copy2(base / "devices.json", output / "devices.json")
    shutil.copy2(HERE / "channels.scm", output / "channels.scm")
    shutil.copy2(HERE / "desktop/README.md", output / "README.md")
    shutil.copytree(HERE / "modules", output / "modules")
    module = output / "modules/engstrand"
    files = module / "desktop-files"
    shutil.copytree(HERE / "desktop/home", files)
    shutil.copy2(HERE / "desktop/keyd.conf", module / "desktop-keyd.conf")

    def copy(source, target):
        destination = files / target
        destination.parent.mkdir(parents=True, exist_ok=True)
        if source.is_dir():
            shutil.copytree(source, destination)
        else:
            shutil.copy2(source, destination)

    copy(FEATURES / "shell/fish/config.fish", ".config/fish/config.fish")
    copy(FEATURES / "shell/fish/functions", ".config/fish/functions")
    for name in ("00-direnv-mode.fish", "10-theme-none.fish", "aliases.fish"):
        copy(FEATURES / "shell/fish/conf.d" / name, ".config/fish/conf.d/" + name)
    copy(FEATURES / "git/config", ".config/git/config")
    copy(FEATURES / "jj/config.toml", ".config/jj/config.toml")
    copy(FEATURES / "herdr/config.toml", ".config/herdr/config.toml")
    for name in ("herdr-tab-focus", "herdr-workspace-pick", "herdr-agent-pick"):
        copy(FEATURES / "herdr/bin" / name, ".local/bin/" + name)
    for name in ("custom-launcher", "chrome-unified"):
        destination = files / ".local/bin" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(HERE / "desktop/bin" / name, destination)
        destination.chmod(0o755)
    pi_settings = json.loads((FEATURES / "pi/assets/agent/settings.json").read_text())
    pi_settings["shellPath"] = "/run/current-system/profile/bin/bash"
    pi_target = files / ".pi/agent/settings.json"
    pi_target.parent.mkdir(parents=True, exist_ok=True)
    pi_target.write_text(json.dumps(pi_settings, indent=2) + "\n")
    jj = files / ".config/jj/config.toml"
    jj.write_text(jj.read_text().replace('backend = "watchman"', 'backend = "none"')
                  .replace("watchman.register-snapshot-trigger = true",
                           "watchman.register-snapshot-trigger = false"))
    for name in ("options", "keymaps"):
        copy(FEATURES / f"neovim/assets/fnl/config/{name}.fnl",
             f".config/nvim/fnl/familiar-{name}.fnl")
    paths = sorted(p.relative_to(files).as_posix() for p in files.rglob("*") if p.is_file())
    entries = "\n".join("      " + json.dumps(p) for p in paths)
    (module / "desktop-files.scm").write_text(f'''(define-module (engstrand desktop-files)
  #:use-module (guix gexp)
  #:export (desktop-file %desktop-home-files %desktop-keyd-config))

(define %directory
  (dirname (canonicalize-path (search-path %load-path "engstrand/desktop-files.scm"))))
(define (desktop-file path)
  (local-file (string-append %directory "/desktop-files/" path)))
(define %desktop-keyd-config
  (local-file (string-append %directory "/desktop-keyd.conf")))
(define %desktop-home-files
  (map (lambda (path) (list path (desktop-file path)))
    '(\n{entries})))
''')
    (output / "system.scm").write_text(f'''(use-modules (engstrand desktop) (guix channels))

(make-familiar-os
 #:root-uuid "{root}"
 #:esp-uuid "{esp}"
 #:channels (primitive-load
             (string-append (dirname (current-filename)) "/channels.scm")))
''')
    hashes = {p.relative_to(output).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(output.rglob("*")) if p.is_file()}
    (output / "sources.json").write_text(json.dumps(hashes, indent=2) + "\n")
    reset_times(output)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="Existing installation configuration")
    parser.add_argument("--output", required=True, help="Fresh staging directory")
    args = parser.parse_args()
    try:
        print(stage(args.base, args.output))
    except (ValueError, OSError, KeyError, TypeError, AttributeError) as error:
        parser.exit(1, f"STOP: {error}\n")


if __name__ == "__main__":
    main()
