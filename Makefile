CONFIG ?= desktop/system.scm
HOME_CONFIG ?= desktop/home.scm
CHANNELS ?= channels.scm
MODULES ?= modules

RECONFIGURE_FLAGS ?= --no-kexec

GC_AGE ?= 2w

GUIX := $(HOME)/.config/guix/current/bin/guix
BB := $(HOME)/.guix-home/profile/bin/bb
JOBS ?=
JOBS_FLAG = $(if $(JOBS),--max-jobs=$(JOBS))
COMMON := -L "$(MODULES)"
# Fails when a built Home's user shepherd.conf would not load at login (T216).
CHECK_SHEPHERD := $(BB) desktop/build/home/check-shepherd.bb
STALE := $(BB) desktop/bin/stale
CHECKS := $(BB) desktop/build/checks/affected.bb
REV ?= @

.PHONY: help lint test verify build switch home status refresh pull gc mux prebuild builder

help:
	@echo 'status        what runs stale after a change, why, and the command that refreshes it; ALL=1 lists every component'
	@echo 'refresh       run every refresh that is safe unattended, then list what needs you (reboot, re-login, super-agent)'
	@echo 'switch        check system facts, build and check, activate system and Home (sudo; writes ESP), then refresh'
	@echo 'home          build and activate Home only, link live configs, then refresh'
	@echo 'build         build without activating: the system, or NAME=home|dusk|rexmux|ghostty-rex|oilfm|pi-sci|pierre-diff-ansi|bbh-web-highlights; DRY=1 shows what it would fetch, REPRO=1 uses the pinned channels'
	@echo 'lint          fast checks for every change: Scheme compiles, scripts parse, Lisp is parinfer-stable, docs agree'
	@echo 'test          lint, then the checks.edn checks covering files changed in REV (default @); ONLY=name[,name] picks rules by name prefix, ALL=1 every rule, LIST=1 only lists'
	@echo 'verify        test, plus the slow and live checks of the same rules'
	@echo 'pull          update the user Guix profile from $(CHANNELS)'
	@echo 'gc            delete generations older than $(GC_AGE), then collect garbage'
	@echo 'mux           choose the terminal multiplexer: MUX=rex|ghostty-rex|herdr (sets %terminal-mux, then make home)'
	@echo 'prebuild      push @ to the builder and build system, Home and pull there'
	@echo 'builder       the Hetzner builder: ACTION=check (default: reachable, last prebuild, nothing to build here), deploy, or create (spends money)'

CHANNELS_CURRENT = pinned=$$(grep -o '(commit "[0-9a-f]*")' "$(CHANNELS)" | sort); \
	pulled=$$("$(GUIX)" describe -f channels | grep -o '(commit "[0-9a-f]*")' | sort); \
	test "$$pinned" = "$$pulled" || { echo '$(CHANNELS) differs from the pulled Guix; run make pull' >&2; exit 1; }

lint:
	$(BB) desktop/build/checks/lint.bb

test:
	@$(CHECKS) $(if $(LIST),,--run) $(if $(ONLY),--only "$(ONLY)") $(if $(ALL),--all) $(REV)

verify:
	@$(CHECKS) $(if $(LIST),,--run) --slow $(if $(ONLY),--only "$(ONLY)") $(if $(ALL),--all) $(REV)

pull:
	"$(GUIX)" pull $(JOBS_FLAG) -C "$(CHANNELS)"

SYSTEM_BUILD = $(CHANNELS_CURRENT) && "$(GUIX)" repl -L "$(MODULES)" -- desktop/system-facts.scm && \
	out=$$(GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  "$(GUIX)" system build $(JOBS_FLAG) $(COMMON) "$(CONFIG)") && echo "$$out" && \
	  $(CHECK_SHEPHERD) "$$out"
HOME_BUILD = $(CHANNELS_CURRENT) && \
	out=$$("$(GUIX)" home build $(JOBS_FLAG) $(COMMON) "$(HOME_CONFIG)") && echo "$$out" && \
	  $(CHECK_SHEPHERD) "$$out"

PI_SCI := desktop/build/pi-sci
PI_SCI_OUTPUTS := $(PI_SCI)/dist/sci.js $(addprefix $(PI_SCI)/dist/pi-,grep.mjs find.mjs read.mjs)
PI_SCI_SOURCES := $(addprefix $(PI_SCI)/,build.bb compiler.edn deps.edn externs.js \
                    package-lock.json scripts/prepare-search.mjs) \
                  $(wildcard $(PI_SCI)/src/pi/*)

$(PI_SCI_OUTPUTS) &: $(PI_SCI_SOURCES)
	$(PI_SCI)/build.bb

PIERRE_DIFF := desktop/build/pierre-diff-ansi

$(PIERRE_DIFF)/node_modules: $(PIERRE_DIFF)/package.json $(PIERRE_DIFF)/bun.lock
	cd $(PIERRE_DIFF) && bun install --frozen-lockfile
	touch $@

OILFM ?= $(HOME)/projects/oilfm

build:
ifeq ($(NAME),)
ifneq ($(DRY),)
	$(CHANNELS_CURRENT) && "$(GUIX)" system build --dry-run $(COMMON) "$(CONFIG)"
else ifneq ($(REPRO),)
	GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  "$(GUIX)" time-machine -C "$(CHANNELS)" -- system build $(COMMON) "$(CONFIG)"
else
	$(SYSTEM_BUILD)
endif
else ifeq ($(NAME),home)
	$(HOME_BUILD)
else ifeq ($(NAME),dusk)
	desktop/build/dusk/build.bb
else ifeq ($(NAME),rexmux)
	desktop/build/rexmux/build.bb
else ifeq ($(NAME),ghostty-rex)
	cd desktop/build/ghostty-rex && $(BB) build
else ifeq ($(NAME),oilfm)
	cd "$(OILFM)" && scripts/vendor-gpui && scripts/cargo build --release -p oilfm
else ifeq ($(NAME),pi-sci)
	$(MAKE) $(PI_SCI_OUTPUTS)
else ifeq ($(NAME),pierre-diff-ansi)
	$(MAKE) $(PIERRE_DIFF)/node_modules
else ifeq ($(NAME),bbh-web-highlights)
	$(MAKE) $(PIERRE_DIFF)/node_modules
	cd $(PIERRE_DIFF) && bun build browser.ts --target=browser --format=iife --minify --outfile=../bbh/src/bbh/web/highlights.js
else
	@echo 'No component $(NAME); make help lists them' >&2; exit 2
endif

switch:
	$(SYSTEM_BUILD)
	sudo rm -f \
	  /boot/efi/m1n1/boot.bin.old \
	  /boot/efi/m1n1/boot.bin.new
	sudo env GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  "$(GUIX)" system reconfigure $(RECONFIGURE_FLAGS) $(JOBS_FLAG) \
	    -L "$(abspath $(MODULES))" \
	    "$(abspath $(CONFIG))"
	-$(STALE) refresh

home: $(PI_SCI_OUTPUTS) $(PIERRE_DIFF)/node_modules
	$(HOME_BUILD)
	"$(GUIX)" home reconfigure $(JOBS_FLAG) $(COMMON) "$(HOME_CONFIG)"
	$(BB) desktop/stow-home
	-$(STALE) refresh

status:
	@$(STALE) $(if $(ALL),--all)

refresh:
	@$(STALE) refresh

gc:
	sudo "$(GUIX)" system delete-generations $(GC_AGE)
	"$(GUIX)" home delete-generations $(GC_AGE)
	"$(GUIX)" pull --delete-generations=$(GC_AGE)
	"$(GUIX)" gc

mux:
	@test "$(MUX)" = rex -o "$(MUX)" = ghostty-rex -o "$(MUX)" = herdr || { echo 'usage: make mux MUX=rex|ghostty-rex|herdr' >&2; exit 2; }
	@if [ "$(MUX)" = rex ]; then $(MAKE) build NAME=rexmux; fi
	$(BB) desktop/build/rexmux/switch.bb $(MUX)
	$(MAKE) home
	@echo 'Now on $(MUX): Super+B opens it. make mux MUX=$(if $(filter herdr,$(MUX)),ghostty-rex,herdr) switches back.'

prebuild:
	desktop/bin/builder-prebuild build

BUILDER_TOKEN ?= $(HOME)/.config/guix-builder/hcloud-token
ACTION ?= check

builder:
ifeq ($(ACTION),check)
	desktop/bin/builder-prebuild check
else ifeq ($(ACTION),deploy)
	"$(GUIX)" deploy $(COMMON) desktop/builder.scm
else ifeq ($(ACTION),create)
	test -r "$(BUILDER_TOKEN)" || { echo '$(BUILDER_TOKEN) is missing; see the plan, phase 2' >&2; exit 1; }
	GUIX_HETZNER_API_TOKEN="$$(cat "$(BUILDER_TOKEN)")" BUILDER_BOOTSTRAP=1 \
	  "$(GUIX)" deploy $(COMMON) desktop/builder.scm
else
	@echo 'usage: make builder ACTION=check|deploy|create' >&2; exit 2
endif
