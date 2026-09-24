# Native Guix workflow. Reconfigure builds before activation; no separate
# build receipt is needed. Disk checks protect the internal installation.

CONFIG ?= desktop/system.scm
# Prefer the general cache; keep Asahi and CI as signed-substitute fallbacks.
SUBSTITUTE_URLS ?= https://bordeaux.guix.gnu.org https://substitutes.asahi-guix.org https://ci.guix.gnu.org
# Normal boot still uses the updated ESP; fast kexec reboot is opt-in.
RECONFIGURE_FLAGS ?= --no-kexec

.PHONY: help test eval eval-desktop dry-run build apply switch home-build home-apply

help:
	@echo 'build         build $(CONFIG) without activating it'
	@echo 'dry-run       show what build would fetch or build'
	@echo 'switch        build and activate once via reconfigure (sudo; writes ESP)'
	@echo 'apply         alias for switch'
	@echo 'home-build    build desktop Home without changing system/bootloader'
	@echo 'home-apply    interactively activate desktop Home (no sudo/ESP writes)'
	@echo 'test          offline Scheme and workflow checks'
	@echo 'eval          pinned installed-system and Home checks'
	@echo 'eval-desktop  alias for eval'

test:
	guile -L modules -s tests/test-desktop-direct.scm
	@find desktop/shared/shell/fish -type f -name '*.fish' -exec fish --no-execute {} \;
	python3 tests/test-switch.py

eval:
	@command -v guix >/dev/null || { echo 'guix required for pinned Scheme checks'; exit 1; }
	guix time-machine -C channels.scm -- repl \
	  -L modules tests/evaluate-desktop.scm channels.scm

eval-desktop: eval

dry-run:
	guix time-machine -C channels.scm -- \
	  system build --dry-run --substitute-urls="$(SUBSTITUTE_URLS)" -L modules "$(CONFIG)"

build:
	GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  guix time-machine -C channels.scm -- \
	  system build --substitute-urls="$(SUBSTITUTE_URLS)" -L modules "$(CONFIG)"

switch:
	@set -eu; \
	  case "$$(readlink -f /run/current-system)" in /gnu/store/*) ;; *) echo 'STOP: not the native Guix system'; exit 1;; esac; \
	  test "$$(findmnt -nro UUID /)" = "c4f25409-b1a5-4ef0-8ac9-8e75f011668c" || { echo 'STOP: unexpected root filesystem'; exit 1; }; \
	  test "$$(findmnt -nro UUID /boot/efi)" = "5CDF-1DF4" || { echo 'STOP: unexpected ESP'; exit 1; }; \
	  profile=$$(guix time-machine -C "$(CURDIR)/channels.scm"); \
	  profile=$$(readlink -f "$$profile"); \
	  sudo env GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	    "$$profile/bin/guix" system reconfigure $(RECONFIGURE_FLAGS) \
	    --substitute-urls="$(SUBSTITUTE_URLS)" -L "$(CURDIR)/modules" "$(abspath $(CONFIG))"

apply: switch

home-build:
	guix time-machine -C channels.scm -- home build -L modules desktop/home.scm

home-apply:
	@printf 'Activate Guix Home from desktop/home.scm for user %s? [y/N] ' "$$USER"; \
	read answer; test "$$answer" = y -o "$$answer" = Y
	guix time-machine -C channels.scm -- home reconfigure -L modules desktop/home.scm
