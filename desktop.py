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

    copy(FEATURES / "shell/fish", ".config/fish")
    copy(FEATURES / "neovim", ".config/nvim")
    copy(FEATURES / "doom", ".config/doom")
    copy(FEATURES / "git/config", ".config/git/config")
    copy(FEATURES / "jj/config.toml", ".config/jj/config.toml")
    copy(FEATURES / "herdr/config.toml", ".config/herdr/config.toml")
    copy(FEATURES / "herdr/sesh.toml", ".config/herdr/sesh.toml")
    copy(FEATURES / "herdr/tiny-fingers", ".local/share/herdr/tiny-fingers")
    copy(HERE / "desktop/shared/noctalia/config.toml", ".config/noctalia/config.toml")
    copy(HERE / "desktop/shared/theme/btop.theme", ".config/btop/themes/catppuccin-mocha.theme")
    copy(HERE / "desktop/shared/theme/wallpapers", ".local/share/catppuccin-mocha/wallpapers")
    for source_dir in (FEATURES / "herdr/bin", HERE / "desktop/bin"):
        for source in sorted(source_dir.iterdir()):
            if source.is_file():
                destination = files / ".local/bin" / source.name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
                destination.chmod(source.stat().st_mode & 0o777)
    copy(FEATURES / "pi/README.md", ".pi/README.md")
    copy(FEATURES / "pi/assets/agent", ".pi/agent")
    copy(FEATURES / "jjui/config.toml", ".config/jjui/config.toml")
    paths = sorted(p.relative_to(files).as_posix() for p in files.rglob("*") if p.is_file())
    entries = "\n".join("      " + json.dumps(p) for p in paths)
    live_sources = [
        (".config/git/config", "desktop/shared/git/config"),
        (".config/jj/config.toml", "desktop/shared/jj/config.toml"),
        (".config/jjui/config.toml", "desktop/shared/jjui/config.toml"),
        (".config/herdr/config.toml", "desktop/shared/herdr/config.toml"),
        (".config/herdr/sesh.toml", "desktop/shared/herdr/sesh.toml"),
        (".config/noctalia/config.toml", "desktop/shared/noctalia/config.toml"),
        (".config/btop/themes/catppuccin-mocha.theme", "desktop/shared/theme/btop.theme"),
        (".pi/README.md", "desktop/shared/pi/README.md")]
    for target_root, source_root in (
            (".config/fish", "desktop/shared/shell/fish"),
            (".config/nvim", "desktop/shared/neovim"),
            (".config/doom", "desktop/shared/doom"),
            (".config/hypr", "desktop/home/.config/hypr"),
            (".config/waybar", "desktop/home/.config/waybar"),
            (".pi/agent", "desktop/shared/pi/assets/agent"),
            (".local/share/catppuccin-mocha/wallpapers", "desktop/shared/theme/wallpapers"),
            (".local/share/herdr/tiny-fingers", "desktop/shared/herdr/tiny-fingers")):
        source_dir = HERE / source_root
        for source in sorted(path for path in source_dir.rglob("*") if path.is_file()):
            relative = source.relative_to(source_dir).as_posix()
            live_sources.append((target_root + "/" + relative,
                                 str(source.relative_to(HERE))))
    for source_dir in (HERE / "desktop/bin", FEATURES / "herdr/bin"):
        for source in sorted(source_dir.iterdir()):
            if source.is_file():
                live_sources.append((".local/bin/" + source.name,
                                     str(source.relative_to(HERE))))
    live_entries = "\n".join(
        "      (list " + json.dumps(target) + " (live-desktop-file " +
        json.dumps(source) + "))"
        for target, source in live_sources)

    (module / "desktop-files.scm").write_text(f'''(define-module (engstrand desktop-files)
  #:use-module (guix gexp)
  #:export (desktop-file live-desktop-file %desktop-home-files
            %desktop-live-home-files %desktop-keyd-config))

(define %directory
  (dirname (canonicalize-path (search-path %load-path "engstrand/desktop-files.scm"))))
(define (desktop-file path)
  (local-file (string-append %directory "/desktop-files/" path)))
(define %live-root {json.dumps(str(HERE))})
(define (live-desktop-file path)
  (computed-file "familiar-live-file"
    #~(symlink (string-append #$%live-root "/" #$path) #$output)))
(define %desktop-keyd-config
  (local-file (string-append %directory "/desktop-keyd.conf")))
(define %desktop-home-files
  (map (lambda (path) (list path (desktop-file path)))
    '(\n{entries})))
(define %desktop-live-home-files
  (list
{live_entries}))
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
