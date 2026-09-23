# Build and apply the familiar desktop layer directly from this checkout.
# Run on the native Guix system. This never formats, mounts, runs
# `guix system init`, or touches the protected NixOS ESP.

BASE ?= /etc/guix-ssd
CONFIG ?= desktop/system.scm
ROOT_UUID ?= c694c1fc-a241-459a-a60d-1c829f1b9c30
ESP_UUID ?= 77C4-10EC
ESP_PARTUUID ?= 5408cbd2-dc6c-49c6-bee9-c51c5f3a29fc
BUILD_RECEIPT ?= local/system-build.receipt
SOURCE_FINGERPRINT = $$( { sha256sum Makefile channels.scm "$(BASE)/devices.json"; find modules desktop -type f -print0 | sort -z | xargs -0 sha256sum; } | sha256sum | cut -d ' ' -f1)

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
	@find desktop/shared/shell/fish -type f -name '*.fish' -exec fish --no-execute {} \;

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
	@set -eu; mkdir -p "$$(dirname "$(BUILD_RECEIPT)")"; \
	  fingerprint=$(SOURCE_FINGERPRINT); \
	  output_file=$$(mktemp); trap 'rm -f "$$output_file"' EXIT; \
	  GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} GUIX_BASE=$(BASE) \
	    guix time-machine -C channels.scm -- \
	    system build -L modules $(CONFIG) > "$$output_file"; \
	  cat "$$output_file"; output=$$(tail -n 1 "$$output_file"); \
	  case "$$output" in /gnu/store/*) ;; *) echo 'STOP: build returned no system output path'; exit 1;; esac; \
	  printf 'output=%s\nfingerprint=%s\nconfig=%s\n' \
	    "$$output" "$$fingerprint" '$(CONFIG)' > "$(BUILD_RECEIPT).tmp"; \
	  mv "$(BUILD_RECEIPT).tmp" "$(BUILD_RECEIPT)"; \
	  echo "Build receipt: $(BUILD_RECEIPT)"

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
	@test -f "$(BUILD_RECEIPT)" || { echo 'STOP: run make build and review its output first'; exit 1; }
	@set -eu; fingerprint=$(SOURCE_FINGERPRINT); \
	  grep -Fx "fingerprint=$$fingerprint" "$(BUILD_RECEIPT)" >/dev/null || { \
	    echo 'STOP: source changed since the successful build; rebuild and review'; exit 1; }; \
	  grep -Fx 'config=$(CONFIG)' "$(BUILD_RECEIPT)" >/dev/null || { \
	    echo 'STOP: build receipt is for a different system configuration'; exit 1; }; \
	  output=$$(sed -n 's/^output=//p' "$(BUILD_RECEIPT)"); \
	  case "$$output" in /gnu/store/*) ;; *) echo 'STOP: invalid output in build receipt'; exit 1;; esac; \
	  test -e "$$output" || { echo 'STOP: built output was garbage-collected; rebuild'; exit 1; }; \
	  echo "Reviewed system output: $$output"; \
	  printf 'After reviewing the build and disk identities, reconfigure this Guix system/ESP? [y/N] '; \
	  read answer; test "$$answer" = y -o "$$answer" = Y || { echo 'Cancelled'; exit 1; }; \
	  case "$$(readlink -f /run/current-system)" in /gnu/store/*) ;; *) echo 'STOP: not the native Guix system'; exit 1;; esac; \
	  test "$$(findmnt -nro UUID /)" = $(ROOT_UUID) || { echo 'STOP: unexpected root filesystem'; exit 1; }; \
	  test "$$(findmnt -nro UUID /boot/efi)" = $(ESP_UUID) || { echo 'STOP: unexpected ESP'; exit 1; }; \
	  test "$$(tr -d '\0' < /proc/device-tree/chosen/asahi,efi-system-partition)" = $(ESP_PARTUUID) \
	    || { echo 'STOP: booted through an unexpected ESP'; exit 1; }; \
	  sudo env GUIX_BASE="$(BASE)" guix time-machine -C "$(CURDIR)/channels.scm" -- \
	    system reconfigure -L "$(CURDIR)/modules" "$(CURDIR)/$(CONFIG)"
