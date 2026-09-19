# Apply the familiar desktop layer from a fresh clone of this repository.
# Run on the native Guix system. This never formats, mounts, runs
# `guix system init`, or touches the protected NixOS ESP.

BASE ?= /etc/guix-ssd
ASAHI ?= $(HOME)/.cache/checkouts/codeberg.org/asahi-guix/channel
ROOT_UUID ?= c694c1fc-a241-459a-a60d-1c829f1b9c30
ESP_UUID ?= 77C4-10EC
ESP_PARTUUID ?= 5408cbd2-dc6c-49c6-bee9-c51c5f3a29fc
STAMP := $(shell date +%Y%m%d-%H%M%S)

.PHONY: help test eval eval-desktop stage archive dry-run build apply switch clean

help:
	@echo 'stage         snapshot BASE (default /etc/guix-ssd) into local/current'
	@echo 'build         pinned guix system build of local/current'
	@echo 'dry-run       show what build would fetch or build'
	@echo 'apply         guarded native reconfigure (sudo; build and review first)'
	@echo 'switch        stage, build, and apply sequentially'
	@echo 'archive       tarball of a fresh snapshot'
	@echo 'test          offline Python checks'
	@echo 'eval          base Scheme checks (needs guix + ASAHI source)'
	@echo 'eval-desktop  desktop Scheme checks over local/current'
	@echo 'clean         remove the local/current symlink only'

test:
	python3 -m unittest discover -s tests -v

eval:
	@command -v guix >/dev/null || { echo 'guix not available; skipping Scheme checks'; exit 0; }
	guix repl -L $(ASAHI)/modules -L modules tests/evaluate.scm channels.scm

eval-desktop:
	@test -d local/current || { echo 'run make stage first'; exit 1; }
	guix repl -L $(ASAHI)/modules -L local/current/modules \
	  tests/evaluate-desktop.scm local/current/channels.scm

stage:
	@test -f $(BASE)/devices.json || { echo "STOP: no devices.json under BASE=$(BASE)"; exit 1; }
	python3 desktop.py --base $(BASE) --output local/familiar-$(STAMP)
	ln -sfn familiar-$(STAMP) local/current
	@echo 'staged: local/current'

archive: stage
	tar -C local/current -czf local/familiar-$(STAMP).tar.gz .

dry-run:
	@test -d local/current || { echo 'run make stage first'; exit 1; }
	guix time-machine -C local/current/channels.scm -- \
	  system build --dry-run -L local/current/modules local/current/system.scm

build:
	@test -d local/current || { echo 'run make stage first'; exit 1; }
	guix time-machine -C local/current/channels.scm -- \
	  system build -L local/current/modules local/current/system.scm

switch:
	$(MAKE) stage
	$(MAKE) build
	$(MAKE) apply

apply:
	@test -d local/current || { echo 'run make stage and make build first'; exit 1; }
	@echo 'apply expects a successful make build and your explicit review'
	set -eu; \
	case "$$(readlink -f /run/current-system)" in /gnu/store/*) ;; *) echo 'STOP: not the native Guix system'; exit 1;; esac; \
	test "$$(findmnt -nro UUID /)" = $(ROOT_UUID) || { echo 'STOP: unexpected root filesystem'; exit 1; }; \
	test "$$(findmnt -nro UUID /boot/efi)" = $(ESP_UUID) || { echo 'STOP: unexpected ESP'; exit 1; }; \
	test "$$(tr -d '\0' < /proc/device-tree/chosen/asahi,efi-system-partition)" = $(ESP_PARTUUID) \
	  || { echo 'STOP: booted through an unexpected ESP'; exit 1; }; \
	DEST="/etc/guix-ssd-desktop-$$(date +%Y%m%d-%H%M%S)"; \
	sudo test ! -e "$$DEST" || { echo "STOP: $$DEST already exists"; exit 1; }; \
	sudo cp -a "$$(readlink -f local/current)" "$$DEST"; \
	sudo chown -R root:root "$$DEST"; \
	sudo chmod -R a+rX "$$DEST"; \
	sudo guix time-machine -C "$$DEST/channels.scm" -- \
	  system reconfigure -L "$$DEST/modules" "$$DEST/system.scm"

clean:
	rm -f local/current
