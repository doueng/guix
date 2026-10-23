"""Harbor's Podman environment, driving podman-compose directly."""

import functools
from pathlib import Path
from typing import override

from harbor.environments.docker.runtime import ContainerRuntime
from harbor.environments.podman import PodmanEnvironment

HOST_NETWORK = Path(__file__).with_name("docker-compose-host-network.yaml")


class GuixPodman(PodmanEnvironment):
    # Harbor 0.24.0 prefers `podman compose` whenever its help mentions
    # --detach, then treats it as Compose V2. Here that wrapper runs
    # podman-compose 1.x, which has no `ls`, `cp` or --project-directory.
    @classmethod
    @functools.cache
    @override
    def runtime(cls) -> ContainerRuntime:
        return ContainerRuntime(
            engine=("podman",),
            compose=("podman-compose", "--in-pod=false"),
            supports_compose_wait=False,
            supports_compose_cp=False,
            supports_compose_project_directory=False,
        )

    # podman-compose creates a project network that this kernel cannot firewall,
    # so the main service joins the host network instead.
    @property
    @override
    def _docker_compose_paths(self) -> list[Path]:
        return [*super()._docker_compose_paths, HOST_NETWORK]
