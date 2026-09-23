# Build and apply the familiar desktop layer directly from this checkout.
# Run on the native Guix system. This never formats, mounts, runs
# `guix system init`, or touches the protected NixOS ESP.

BASE ?= /etc/guix-ssd
CONFIG ?= desktop/system.scm
ROOT_UUID ?= c694c1fc-a241-459a-a60d-1c829f1b9c30
ESP_UUID ?= 77C4-10EC
ESP_PARTUUID ?= 5408cbd2-dc6c-49c6-bee9-c51c5f3a29fc

.PHONY: help test eval eval-desktop dry-run build apply switch home-build home-apply

help:
	@echo 'build         pinned Guix build directly from $(CONFIG)'
	@echo 'dry-run       show what build would fetch or build'
	@echo 'apply         guarded native reconfigure (sudo; build and review first)'
	@echo 'switch        build and apply sequentially (system and bootloader)'
	@echo 'home-build    build desktop Home without changing system/bootloader'
	@echo 'home-apply    interactively activate desktop Home (no sudo/ESP writes)'
	@echo 'test          offline Scheme checks'
	@echo 'eval          base pinned Scheme checks'
	@echo 'eval-desktop  desktop Scheme checks from this checkout'

test:
	guile -L modules -s tests/test-desktop-direct.scm

eval:
	@command -v guix >/dev/null || { echo 'guix not available; skipping Scheme checks'; exit 0; }
	guix time-machine -C channels.scm -- repl -L modules tests/evaluate.scm channels.scm

eval-desktop:
	guix time-machine -C channels.scm -- repl \
	  -L modules tests/evaluate-desktop.scm channels.scm

dry-run:
	@test -f $(BASE)/devices.json || { echo "STOP: no devices.json under BASE=$(BASE)"; exit 1; }
	GUIX_BASE=$(BASE) guix time-machine -C channels.scm -- \
	  system build --dry-run -L modules $(CONFIG)

build:
	@test -f $(BASE)/devices.json || { echo "STOP: no devices.json under BASE=$(BASE)"; exit 1; }
	GUIX_BASE=$(BASE) guix time-machine -C channels.scm -- \
	  system build -L modules $(CONFIG)

switch:
	$(MAKE) build
	$(MAKE) apply

home-build:
	guix time-machine -C channels.scm -- home build -L modules desktop/home.scm

home-apply:
	@printf 'Activate Guix Home from desktop/home.scm for user %s? [y/N] ' "$$USER"; \
	read answer; test "$$answer" = y -o "$$answer" = Y
	guix time-machine -C channels.scm -- home reconfigure -L modules desktop/home.scm

apply:
	@test -f $(BASE)/devices.json || { echo "STOP: no devices.json under BASE=$(BASE)"; exit 1; }
	@test -f $(CONFIG) || { echo "STOP: no system configuration $(CONFIG)"; exit 1; }
	@echo 'apply expects a successful make build and your explicit review'
	set -eu; \
	case "$$(readlink -f /run/current-system)" in /gnu/store/*) ;; *) echo 'STOP: not the native Guix system'; exit 1;; esac; \
	test "$$(findmnt -nro UUID /)" = $(ROOT_UUID) || { echo 'STOP: unexpected root filesystem'; exit 1; }; \
	test "$$(findmnt -nro UUID /boot/efi)" = $(ESP_UUID) || { echo 'STOP: unexpected ESP'; exit 1; }; \
	test "$$(tr -d '\0' < /proc/device-tree/chosen/asahi,efi-system-partition)" = $(ESP_PARTUUID) \
	  || { echo 'STOP: booted through an unexpected ESP'; exit 1; }; \
	sudo env GUIX_BASE="$(BASE)" guix time-machine -C "$(CURDIR)/channels.scm" -- \
	  system reconfigure -L "$(CURDIR)/modules" "$(CURDIR)/$(CONFIG)"
