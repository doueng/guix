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

.PHONY: help pull check dry-run build repro-build switch \
        home-build home-weather home-apply stow dusk dusk-test dusk-verify pi-sci pi-sci-test

help:
	@echo 'pull          update the user Guix profile from $(CHANNELS)'
	@echo 'check         dry-run system and build Home'
	@echo 'dry-run       show what system build would fetch or build'
	@echo 'build         build $(CONFIG) without activating it'
	@echo 'repro-build   build system using the pinned channels via time-machine'
	@echo 'switch        build and activate system (sudo; writes ESP)'
	@echo 'home-build    build Home without activating it'
	@echo 'home-weather  check substitutes for explicit Home packages'
	@echo 'home-apply    activate Home, then stow live configs'
	@echo 'stow          build SCI and install live config links using GNU Stow'
	@echo 'pi-sci        compile the pinned SCI codemode runtime'
	@echo 'pi-sci-test   build and verify SCI codemode with the installed Pi'
	@echo 'dusk          build the dusk shell (desktop/build/dusk)'
	@echo 'dusk-test     run isolated runtime and headless GPUI tests'
	@echo 'dusk-verify   run dusk end-to-end checks on the live session'

pull:
	"$(GUIX)" pull -C "$(CHANNELS)"

check: dry-run home-build

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

home-build:
	"$(GUIX)" home build $(COMMON) "$(HOME_CONFIG)"

home-weather:
	"$(GUIX)" weather $(COMMON) -m "$(HOME_MANIFEST)"

pi-sci:
	desktop/build/pi-sci/build.sh

pi-sci-test:
	desktop/build/pi-sci/verify.sh

stow: pi-sci
	"$(HOME)/.guix-home/profile/bin/bb" desktop/stow-home

home-apply:
	"$(GUIX)" home reconfigure $(COMMON) "$(HOME_CONFIG)"
	$(MAKE) stow

dusk:
	desktop/build/dusk/build.sh

dusk-test:
	desktop/build/dusk/test.sh

dusk-verify:
	"$(HOME)/.guix-home/profile/bin/bb" desktop/build/dusk/verify.bb
