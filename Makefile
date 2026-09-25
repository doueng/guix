# Native Guix workflow. Reconfigure builds before activation; no separate
# build receipt is needed. Disk checks protect the internal installation.

CONFIG ?= desktop/system.scm
# Prefer the general cache; keep Asahi and CI as signed-substitute fallbacks.
SUBSTITUTE_URLS ?= https://bordeaux.guix.gnu.org https://substitutes.asahi-guix.org https://ci.guix.gnu.org
# Normal boot still uses the updated ESP; fast kexec reboot is opt-in.
RECONFIGURE_FLAGS ?= --no-kexec

.PHONY: help eval eval-desktop dry-run build apply switch home-build home-apply home-weather stow

help:
	@echo 'build         build $(CONFIG) without activating it'
	@echo 'dry-run       show what build would fetch or build'
	@echo 'switch        build and activate once via reconfigure (sudo; writes ESP)'
	@echo 'apply         alias for switch'
	@echo 'home-build    build desktop Home without changing system/bootloader'
	@echo 'home-weather  check substitutes for explicit Home packages (network)'
	@echo 'home-apply    activate desktop Home, then stow live configs'
	@echo 'stow          install live config links using GNU Stow'
	@echo 'eval          pinned system dry-run and Home build'
	@echo 'eval-desktop  alias for eval'

eval: dry-run home-build

eval-desktop: eval

dry-run:
	guix time-machine -C channels.scm -- \
	  system build --dry-run --substitute-urls="$(SUBSTITUTE_URLS)" -L modules "$(CONFIG)"

build:
	GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  guix time-machine -C channels.scm -- \
	  system build --substitute-urls="$(SUBSTITUTE_URLS)" -L modules "$(CONFIG)"

switch:
	sudo rm /boot/efi/m1n1/boot.bin.old || true
	sudo rm /boot/efi/m1n1/boot.bin.new || true
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

home-weather:
	guix time-machine -C channels.scm -- weather -L modules \
	  --substitute-urls="$(SUBSTITUTE_URLS)" -m desktop/home-manifest.scm

stow:
	"$(HOME)/.guix-home/profile/bin/bb" desktop/stow-home

home-apply:
	guix time-machine -C channels.scm -- home reconfigure -L modules desktop/home.scm
	$(MAKE) stow
