# Native Guix workflow. Reconfigure builds before activation; no separate
# build receipt is needed. Disk checks protect the independent NixOS ESP.

BASE ?= /etc/guix-ssd
CONFIG ?= desktop/system.scm
# Prefer the general cache; keep Asahi and CI as signed-substitute fallbacks.
SUBSTITUTE_URLS ?= https://bordeaux.guix.gnu.org https://substitutes.asahi-guix.org https://ci.guix.gnu.org
# Normal boot still uses the updated ESP; fast kexec reboot is opt-in.
RECONFIGURE_FLAGS ?= --no-kexec
ROOT_UUID ?= c694c1fc-a241-459a-a60d-1c829f1b9c30
ESP_UUID ?= 77C4-10EC
ESP_PARTUUID ?= 5408cbd2-dc6c-49c6-bee9-c51c5f3a29fc

.PHONY: help test eval eval-desktop dry-run build apply switch home-build home-apply

help:
	@echo 'build         build $(CONFIG) without activating it'
	@echo 'dry-run       show what build would fetch or build'
	@echo 'switch        build and activate once via reconfigure (sudo; writes ESP)'
	@echo 'apply         alias for switch'
	@echo 'home-build    build desktop Home without changing system/bootloader'
	@echo 'home-apply    interactively activate desktop Home (no sudo/ESP writes)'
	@echo 'test          offline Scheme and workflow checks'
	@echo 'eval          base pinned Scheme checks'
	@echo 'eval-desktop  desktop Scheme checks from this checkout'

test:
	guile -L modules -s tests/test-desktop-direct.scm
	@find desktop/shared/shell/fish -type f -name '*.fish' -exec fish --no-execute {} \;
	python3 tests/test-switch.py

eval:
	@command -v guix >/dev/null || { echo 'guix not available; skipping Scheme checks'; exit 0; }
	guix time-machine -C channels.scm -- repl -L modules tests/evaluate.scm channels.scm

eval-desktop:
	guix time-machine -C channels.scm -- repl \
	  -L modules tests/evaluate-desktop.scm channels.scm

dry-run:
	GUIX_BASE="$(BASE)" guix time-machine -C channels.scm -- \
	  system build --dry-run --substitute-urls="$(SUBSTITUTE_URLS)" -L modules "$(CONFIG)"

build:
	GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} GUIX_BASE="$(BASE)" \
	  guix time-machine -C channels.scm -- \
	  system build --substitute-urls="$(SUBSTITUTE_URLS)" -L modules "$(CONFIG)"

switch:
	@set -eu; \
	  case "$$(readlink -f /run/current-system)" in /gnu/store/*) ;; *) echo 'STOP: not the native Guix system'; exit 1;; esac; \
	  test "$$(findmnt -nro UUID /)" = "$(ROOT_UUID)" || { echo 'STOP: unexpected root filesystem'; exit 1; }; \
	  test "$$(findmnt -nro UUID /boot/efi)" = "$(ESP_UUID)" || { echo 'STOP: unexpected ESP'; exit 1; }; \
	  test "$$(tr -d '\0' < /proc/device-tree/chosen/asahi,efi-system-partition)" = "$(ESP_PARTUUID)" \
	    || { echo 'STOP: booted through an unexpected ESP'; exit 1; }; \
	  profile=$$(guix time-machine -C "$(CURDIR)/channels.scm"); \
	  profile=$$(readlink -f "$$profile"); \
	  sudo env GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} GUIX_BASE="$(abspath $(BASE))" \
	    "$$profile/bin/guix" system reconfigure $(RECONFIGURE_FLAGS) \
	    --substitute-urls="$(SUBSTITUTE_URLS)" -L "$(CURDIR)/modules" "$(abspath $(CONFIG))"

apply: switch

home-build:
	guix time-machine -C channels.scm -- home build -L modules desktop/home.scm

home-apply:
	@printf 'Activate Guix Home from desktop/home.scm for user %s? [y/N] ' "$$USER"; \
	read answer; test "$$answer" = y -o "$$answer" = Y
	guix time-machine -C channels.scm -- home reconfigure -L modules desktop/home.scm
