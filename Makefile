# Use the pulled Guix for daily operations; pull after changing channels.scm.
# Reconfigure builds before activation; no separate build receipt is needed.

CONFIG ?= desktop/system.scm
HOME_CONFIG ?= desktop/home.scm
HOME_MANIFEST ?= desktop/home-manifest.scm
CHANNELS ?= channels.scm
MODULES ?= modules

# Prefer the general cache; keep Asahi and CI as signed-substitute fallbacks.
SUBSTITUTE_URLS ?= https://bordeaux.guix.gnu.org https://substitutes.asahi-guix.org https://ci.guix.gnu.org

# Normal boot still uses the updated ESP; fast kexec reboot is opt-in.
RECONFIGURE_FLAGS ?= --no-kexec

GUIX := $(HOME)/.config/guix/current/bin/guix
COMMON := --substitute-urls="$(SUBSTITUTE_URLS)" -L "$(MODULES)"

.PHONY: help pull check eval eval-desktop dry-run build repro-build switch apply \
        home-build home-weather home-apply stow

help:
	@echo 'pull          update the user Guix profile from $(CHANNELS)'
	@echo 'check         dry-run system and build Home'
	@echo 'dry-run       show what system build would fetch or build'
	@echo 'build         build $(CONFIG) without activating it'
	@echo 'repro-build   build system using the pinned channels via time-machine'
	@echo 'switch        build and activate system (sudo; writes ESP)'
	@echo 'apply         alias for switch'
	@echo 'home-build    build Home without activating it'
	@echo 'home-weather  check substitutes for explicit Home packages'
	@echo 'home-apply    activate Home, then stow live configs'
	@echo 'stow          install live config links using GNU Stow'
	@echo 'eval          alias for check'
	@echo 'eval-desktop  alias for check'

pull:
	"$(GUIX)" pull -C "$(CHANNELS)"

check: dry-run home-build

eval: check

eval-desktop: check

dry-run:
	"$(GUIX)" system build --dry-run $(COMMON) "$(CONFIG)"

build:
	GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  "$(GUIX)" system build $(COMMON) "$(CONFIG)"

repro-build:
	GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  "$(GUIX)" time-machine -C "$(CHANNELS)" -- \
	  system build $(COMMON) "$(CONFIG)"

switch:
	sudo rm -f \
	  /boot/efi/m1n1/boot.bin.old \
	  /boot/efi/m1n1/boot.bin.new
	sudo env GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  "$(GUIX)" system reconfigure $(RECONFIGURE_FLAGS) \
	    --substitute-urls="$(SUBSTITUTE_URLS)" \
	    -L "$(abspath $(MODULES))" \
	    "$(abspath $(CONFIG))"

apply: switch

home-build:
	"$(GUIX)" home build $(COMMON) "$(HOME_CONFIG)"

home-weather:
	"$(GUIX)" weather $(COMMON) -m "$(HOME_MANIFEST)"

stow:
	"$(HOME)/.guix-home/profile/bin/bb" desktop/stow-home

home-apply:
	"$(GUIX)" home reconfigure $(COMMON) "$(HOME_CONFIG)"
	$(MAKE) stow
