"""Serve the pinned Parakeet CLI's transcription from one long-lived process.

Each stdin line names a WAV file; each reply is one JSON line
{"ok": bool, "ms": int, "text": str}.  Importing MLX and the CLI once
saves about a second per transcription, which streaming dictation pays on
every pass.  Usage: parakeet_server.py PATH-TO-mlx-omarchy-parakeet
"""
import argparse
import contextlib
import importlib.machinery
import importlib.util
import json
import sys
import tempfile
import time
from pathlib import Path

tool = sys.argv[1]
loader = importlib.machinery.SourceFileLoader("mlx_omarchy_parakeet", tool)
spec = importlib.util.spec_from_loader(loader.name, loader, origin=tool)
cli = importlib.util.module_from_spec(spec)
cli.__file__ = tool  # the CLI finds its share tree from __file__
sys.modules[loader.name] = cli
loader.exec_module(cli)
print(json.dumps({"ready": True}), flush=True)

for line in sys.stdin:
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="parakeet-server-") as out:
        args = argparse.Namespace(audio=line.strip(), out=out, deadline_ms=20000,
                                  repeat=1, keep_scratch=False)
        try:
            with contextlib.redirect_stdout(sys.stderr):
                code = cli._transcribe(args)
            text = (Path(out) / "transcript.txt").read_text().strip() if code == 0 else ""
        except Exception as error:  # report and keep serving
            print(f"transcription failed: {error!r}", file=sys.stderr)
            code, text = 1, ""
    print(json.dumps({"ok": code == 0, "text": text,
                      "ms": round((time.monotonic() - started) * 1000)}), flush=True)
