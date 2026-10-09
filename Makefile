# Use the pulled Guix for daily operations; pull after changing channels.scm.
# Reconfigure builds before activation; no separate build receipt is needed.

CONFIG ?= desktop/system.scm
HOME_CONFIG ?= desktop/home.scm
CHANNELS ?= channels.scm
MODULES ?= modules

# Normal boot still uses the updated ESP; fast kexec reboot is opt-in.
RECONFIGURE_FLAGS ?= --no-kexec

# make gc keeps generations newer than GC_AGE; the current one always stays.
GC_AGE ?= 2w

GUIX := $(HOME)/.config/guix/current/bin/guix
COMMON := -L "$(MODULES)"

.PHONY: help pull check dry-run build repro-build switch gc \
        home-build home-apply stow dusk dusk-test dusk-verify pi-sci pi-sci-test

help:
	@echo 'pull          update the user Guix profile from $(CHANNELS)'
	@echo 'check         dry-run system and build Home'
	@echo 'dry-run       show what system build would fetch or build'
	@echo 'build         build $(CONFIG) without activating it'
	@echo 'repro-build   build system using the pinned channels via time-machine'
	@echo 'switch        build and activate system (sudo; writes ESP)'
	@echo 'gc            delete generations older than $(GC_AGE), then collect garbage'
	@echo 'home-build    build Home without activating it'
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
	    -L "$(abspath $(MODULES))" \
	    "$(abspath $(CONFIG))"

gc:
	sudo "$(GUIX)" system delete-generations $(GC_AGE)
	"$(GUIX)" home delete-generations $(GC_AGE)
	"$(GUIX)" pull --delete-generations=$(GC_AGE)
	"$(GUIX)" gc

home-build:
	"$(GUIX)" home build $(COMMON) "$(HOME_CONFIG)"

PI_SCI := desktop/build/pi-sci
PI_SCI_OUTPUTS := $(PI_SCI)/dist/sci.js $(addprefix $(PI_SCI)/dist/pi-,grep.mjs find.mjs read.mjs)
PI_SCI_SOURCES := $(addprefix $(PI_SCI)/,build.sh compiler.edn deps.edn externs.js \
                    package-lock.json scripts/prepare-search.mjs) \
                  $(wildcard $(PI_SCI)/src/pi/*)

$(PI_SCI_OUTPUTS) &: $(PI_SCI_SOURCES)
	$(PI_SCI)/build.sh

pi-sci: $(PI_SCI_OUTPUTS)

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
