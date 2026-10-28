"""Run bbh as a Harbor agent through Harbor's generic ACP runner.

Use it with `harbor run --agent bbh_agent:BbhAgent` and this directory on
PYTHONPATH. Written against harbor 0.25.0. The agent installs a static bb, the
bbh sources and an eval init file into the task container, and Harbor's ACP
runner then drives `bbh-acp` over stdio like any other ACP agent.
"""

import shlex
from pathlib import Path
from typing import override

from harbor.agents.installed.acp import AcpAgent
from harbor.environments.base import BaseEnvironment

BB_VERSION = "1.13.226"
HERE = Path(__file__).resolve().parent
BBH_ROOT = HERE.parent
INIT = BBH_ROOT.parent.parent / "configs" / "bbh" / "init.clj"
REMOTE = "/opt/bbh"

REGISTRY_ENTRY = {
    "id": "bbh",
    "name": "bbh",
    "version": "0.1",
    "description": "bbh, a coding agent harness in babashka",
    "distribution": {"local": {"cmd": f"{REMOTE}/bbh-acp"}},
}


def install_command() -> str:
    """Shell run as root: bash and tar for bbh's helpers, then a static bb."""
    return f"""
set -eu
if ! command -v bash >/dev/null 2>&1 || ! command -v tar >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then apt-get install -y bash tar gzip
  elif command -v apk >/dev/null 2>&1; then apk add --no-cache bash tar gzip
  elif command -v dnf >/dev/null 2>&1; then dnf install -y bash tar gzip
  elif command -v yum >/dev/null 2>&1; then yum install -y bash tar gzip
  fi
fi
case "$(uname -m)" in
  x86_64|amd64) arch=amd64 ;;
  aarch64|arm64) arch=aarch64 ;;
  *) echo "bbh: unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
mkdir -p {REMOTE}/bin
curl -fsSL "https://github.com/babashka/babashka/releases/download/v{BB_VERSION}/babashka-{BB_VERSION}-linux-$arch-static.tar.gz" | tar -xz -C {REMOTE}/bin bb
ln -sf {REMOTE}/bin/bb /usr/local/bin/bb  # for bbh-acp's #!/usr/bin/env bb
{REMOTE}/bin/bb --version
""".strip()


def eval_init() -> str:
    """The production init without skills or poteto-mode, with effort from BBH_EFFORT."""
    return INIT.read_text() + (HERE / "eval-init.clj").read_text()


class BbhAgent(AcpAgent):
    def __init__(self, *args, **kwargs):
        kwargs.setdefault("registry_entry", REGISTRY_ENTRY)
        kwargs.setdefault("distribution_preference", "local")
        super().__init__(*args, **kwargs)

    @staticmethod
    @override
    def name() -> str:
        return "bbh"

    @override
    async def install(self, environment: BaseEnvironment) -> None:
        await super().install(environment)
        await self.exec_as_root(environment, command=install_command())
        init = self.logs_dir / "bbh-init.clj"
        init.write_text(eval_init())
        await environment.upload_dir(BBH_ROOT / "src", f"{REMOTE}/app/src")
        await environment.upload_file(BBH_ROOT / "bb.edn", f"{REMOTE}/app/bb.edn")
        await environment.upload_file(init, f"{REMOTE}/init.clj")
        await environment.upload_file(HERE / "bbh-acp", f"{REMOTE}/bbh-acp")
        await self.exec_as_root(
            environment,
            command=f"chmod 755 {shlex.quote(REMOTE)}/bbh-acp && chmod -R a+rX {shlex.quote(REMOTE)}",
        )
