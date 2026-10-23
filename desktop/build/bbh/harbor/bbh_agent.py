"""Harbor agent that runs bbh over ACP inside the task container."""

import hashlib
import os
import shutil
import tempfile
from pathlib import Path
from typing import override

from harbor.agents.installed.acp import AcpAgent
from harbor.environments.base import BaseEnvironment
from harbor.models.trial.paths import EnvironmentPaths

BBH_ROOT = Path(__file__).resolve().parents[1]
INIT_SOURCE = BBH_ROOT.parents[1] / "configs/bbh/init.clj"
BB_VERSION = "1.13.226"
REMOTE_DIR = "/installed-agent/bbh"
# The same init as the bbh profile of bb eval:compare, so scores stay comparable.
INIT_SUFFIX = "\n(ns bbh.env) (defn skills [] []) (reset! poteto? false) (ns user)\n"


def _init_text() -> str:
    return INIT_SOURCE.read_text() + INIT_SUFFIX


def _sources() -> list[Path]:
    return sorted((BBH_ROOT / "src").rglob("*.clj"))


def _version() -> str:
    digest = hashlib.sha256(_init_text().encode())
    for path in _sources():
        digest.update(path.relative_to(BBH_ROOT).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()[:12]


class Bbh(AcpAgent):
    def __init__(self, *args, **kwargs):
        kwargs.setdefault(
            "registry_entry",
            {
                "id": "bbh",
                "name": "bbh",
                "version": _version(),
                "description": "Coding agent harness in babashka",
                "distribution": {
                    "local": {
                        "cmd": "/usr/local/bin/bb",
                        "args": ["--config", f"{REMOTE_DIR}/bb.edn", "-m", "bbh.main", "acp"],
                        "env": {
                            "BBH_INIT": f"{REMOTE_DIR}/init.clj",
                            # Conversations land in the trial's logs, where bb eval:lint can read them.
                            "XDG_DATA_HOME": (EnvironmentPaths.agent_dir / "data").as_posix(),
                        },
                    }
                },
            },
        )
        kwargs.setdefault("distribution_preference", "local")
        super().__init__(*args, **kwargs)
        if self.model_name:
            self._extra_env.setdefault("BBH_MODEL", self.model_name)
        if key := os.environ.get("OPENROUTER_API_KEY"):
            self._extra_env.setdefault("OPENROUTER_API_KEY", key)

    @staticmethod
    @override
    def name() -> str:
        return "bbh"

    @override
    async def install(self, environment: BaseEnvironment) -> None:
        await super().install(environment)
        with tempfile.TemporaryDirectory(prefix="harbor-bbh-") as staging:
            stage = Path(staging)
            shutil.copytree(BBH_ROOT / "src", stage / "src")
            (stage / "bb.edn").write_text('{:paths ["src"]}\n')
            (stage / "init.clj").write_text(_init_text())
            await environment.upload_dir(source_dir=stage, target_dir=REMOTE_DIR)
        await self.exec_as_root(
            environment,
            command=(
                "set -eu; "
                'case "$(uname -m)" in aarch64|arm64) arch=aarch64;; *) arch=amd64;; esac; '
                "curl -fsSL https://github.com/babashka/babashka/releases/download/"
                f"v{BB_VERSION}/babashka-{BB_VERSION}-linux-$arch-static.tar.gz "
                "| tar -xz -C /usr/local/bin bb; "
                f"chmod -R a+rX {REMOTE_DIR}; bb --version"
            ),
        )
