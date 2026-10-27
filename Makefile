# Daily targets use the pulled Guix and refuse to run until it matches channels.scm.

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

.PHONY: help lint affected verify pull channels-current check system-facts dry-run build repro-build switch gc \
        nixpkgs-lock nixpkgs-key nixpkgs-authorize nixpkgs-import nixpkgs-verify home-build home-apply stow pierre-diff-ansi dusk dusk-test dusk-verify pi-sci pi-sci-test \
        doom-check doom-baseline skill-diet-check keybindings-check kanata-check hypr-nested-check

help:
	@echo 'lint          fast checks for every change: Scheme compiles, scripts parse, docs agree'
	@echo 'affected      list the checks covering files changed in REV (default @)'
	@echo 'verify        run lint and the affected checks; SLOW=1 adds the slow ones'
	@echo 'pull          update the user Guix profile from $(CHANNELS)'
	@echo 'channels-current fail unless the pulled Guix matches $(CHANNELS)'
	@echo 'check         check system facts, then build system and Home without activating'
	@echo 'system-facts  print folded daemon, sysctl, Shepherd and mount config; lint mounts'
	@echo 'dry-run       show what system build would fetch or build'
	@echo 'build         build $(CONFIG) without activating it'
	@echo 'repro-build   build system using the pinned channels via time-machine'
	@echo 'switch        check system facts, build and activate system (sudo; writes ESP)'
	@echo 'gc            delete generations older than $(GC_AGE), then collect garbage'
	@echo 'nixpkgs-lock  resolve $(NIXPKGS)/packages.sexp against cache.nixos.org (NIXPKGS_REV=...)'
	@echo 'nixpkgs-key   generate the key that signs nixpkgs imports'
	@echo 'nixpkgs-authorize let the running daemon trust that key before the first switch (sudo)'
	@echo 'nixpkgs-import copy the locked nixpkgs closures into /gnu/store'
	@echo 'nixpkgs-verify check imported items against cache.nixos.org hashes'
	@echo 'home-build    build Home without activating it'
	@echo 'home-apply    activate Home, then stow live configs'
	@echo 'stow          build SCI and install live config links using GNU Stow'
	@echo 'pi-sci        compile the pinned SCI codemode runtime'
	@echo 'pi-sci-test   build and verify SCI codemode with the installed Pi'
	@echo 'skill-diet-check check the skill-diet links and the bbh eval adapter'
	@echo 'pierre-diff-ansi install the highlighter behind jjui diff previews'
	@echo 'dusk          build the dusk shell (desktop/build/dusk)'
	@echo 'dusk-test     run isolated runtime and headless GPUI tests'
	@echo 'dusk-verify   run dusk end-to-end checks on the live session'
	@echo 'doom-check    fail on doom doctor warnings missing from the baseline'
	@echo 'doom-baseline accept the current doom doctor warnings'
	@echo 'kanata-check  run kanata.kbd chord tests in the kanata simulator'
	@echo 'keybindings-check kanata-check plus generated-binding, Hyprland, Emacs and Neovim tests'
	@echo 'hypr-nested-check test Hyprland keybindings in a nested session'

# desktop/build/checks/checks.edn maps paths to the checks that cover them.
REV ?= @
lint:
	"$(HOME)/.guix-home/profile/bin/bb" desktop/build/checks/lint.bb

affected:
	@"$(HOME)/.guix-home/profile/bin/bb" desktop/build/checks/affected.bb $(REV)

verify:
	"$(HOME)/.guix-home/profile/bin/bb" desktop/build/checks/affected.bb --run $(if $(SLOW),--slow) $(REV)

pull:
	"$(GUIX)" pull -C "$(CHANNELS)"

channels-current:
	@pinned=$$(grep -o '(commit "[0-9a-f]*")' "$(CHANNELS)" | sort); \
	pulled=$$("$(GUIX)" describe -f channels | grep -o '(commit "[0-9a-f]*")' | sort); \
	test "$$pinned" = "$$pulled" || { echo '$(CHANNELS) differs from the pulled Guix; run make pull' >&2; exit 1; }

check: system-facts build

system-facts: channels-current
	"$(GUIX)" repl -L "$(MODULES)" -- desktop/system-facts.scm

dry-run: channels-current
	"$(GUIX)" system build --dry-run $(COMMON) "$(CONFIG)"

build: channels-current nixpkgs-import
	GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  "$(GUIX)" system build $(COMMON) "$(CONFIG)"

repro-build: nixpkgs-import
	GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  "$(GUIX)" time-machine -C "$(CHANNELS)" -- \
	  system build $(COMMON) "$(CONFIG)"

switch: channels-current system-facts nixpkgs-import
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

home-build: channels-current nixpkgs-import
	"$(GUIX)" home build $(COMMON) "$(HOME_CONFIG)"

# nixpkgs binaries enter /gnu/store signed by NIXPKGS_KEY; the daemon trusts
# its public half, $(NIXPKGS)/signing-key.pub, through asahi.scm.
NIXPKGS := modules/engstrand/nixpkgs
NIXPKGS_KEY ?= $(HOME)/.config/guix/nixpkgs-bridge/signing-key.sec
NIXPKGS_BRIDGE := "$(GUIX)" repl $(COMMON) -- desktop/build/nixpkgs/bridge.scm

nixpkgs-lock:
	$(NIXPKGS_BRIDGE) lock $(NIXPKGS)/packages.sexp $(NIXPKGS)/lock.sexp $(NIXPKGS_REV)

nixpkgs-key:
	test ! -e "$(NIXPKGS_KEY)" || { echo '$(NIXPKGS_KEY) exists' >&2; exit 1; }
	$(NIXPKGS_BRIDGE) key "$(NIXPKGS_KEY)"
	cp "$(basename $(NIXPKGS_KEY)).pub" $(NIXPKGS)/signing-key.pub

# The first switch imports before it installs the key from asahi.scm, so a new
# key must be trusted by the running daemon once; the switch then keeps it.
nixpkgs-authorize:
	sudo "$(GUIX)" archive --authorize < $(NIXPKGS)/signing-key.pub

nixpkgs-import:
	$(NIXPKGS_BRIDGE) import $(NIXPKGS)/lock.sexp "$(NIXPKGS_KEY)"

nixpkgs-verify:
	$(NIXPKGS_BRIDGE) verify $(NIXPKGS)/lock.sexp

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

SKILL_DIET := desktop/pi/agent/skills/skill-diet
skill-diet-check:
	node $(SKILL_DIET)/scripts/eval.mjs check $(SKILL_DIET)
	bb desktop/build/skill-diet/adapter-test.bb

PIERRE_DIFF := desktop/build/pierre-diff-ansi

$(PIERRE_DIFF)/node_modules: $(PIERRE_DIFF)/package.json $(PIERRE_DIFF)/bun.lock
	cd $(PIERRE_DIFF) && bun install --frozen-lockfile
	touch $@

pierre-diff-ansi: $(PIERRE_DIFF)/node_modules

stow: pi-sci pierre-diff-ansi
	"$(HOME)/.guix-home/profile/bin/bb" desktop/stow-home

home-apply: channels-current nixpkgs-import
	"$(GUIX)" home reconfigure $(COMMON) "$(HOME_CONFIG)"
	$(MAKE) stow

dusk:
	desktop/build/dusk/build.sh

dusk-test:
	desktop/build/dusk/test.sh

dusk-verify:
	"$(HOME)/.guix-home/profile/bin/bb" desktop/build/dusk/verify.bb

# Doom's CLI reads a piped stdin to EOF before running, so an agent's open pipe
# hangs it; give it /dev/null.
# doom doctor exits 0 even when an enabled module lacks its tools, so compare its
# warnings, prefixed with their module, against an accepted baseline.
DOOM ?= $(HOME)/.config/emacs/bin/doom
DOOM_BASELINE := desktop/build/doom/doctor-baseline.txt
DOOM_WARNINGS = out=$$(cd "$(HOME)" && "$(DOOM)" doctor 2>&1 </dev/null) || { printf '%s\n' "$$out" >&2; exit 1; }; \
	printf '%s\n' "$$out" | awk '/^ *> :/ { m = $$2 " " $$3 ": " } /^ *! / { sub(/^ *! /, ""); print m $$0 }' | LC_ALL=C sort

doom-check:
	@warnings=$$($(DOOM_WARNINGS)) || exit 1; \
	new=$$(printf '%s\n' "$$warnings" | LC_ALL=C comm -23 - "$(DOOM_BASELINE)"); \
	test -z "$$new" || { printf 'New doom doctor warnings (accept with make doom-baseline):\n%s\n' "$$new" >&2; exit 1; }; \
	echo 'doom doctor: no new warnings'

# The simulator parses desktop/kanata.kbd with the kanata version Guix installs.
KANATA_VERSION := 1.11.0
KANATA_SRC := $(HOME)/.cache/kanata-sim/$(KANATA_VERSION)
KANATA_SIM := $(KANATA_SRC)/target/release/kanata_simulated_input

$(KANATA_SIM):
	rm -rf "$(KANATA_SRC)"
	git clone --quiet --depth 1 --branch v$(KANATA_VERSION) https://github.com/jtroo/kanata "$(KANATA_SRC)"
	cd "$(KANATA_SRC)" && "$(GUIX)" shell rust rust:cargo gcc-toolchain -- cargo build --release --quiet -p kanata-sim

kanata-check: $(KANATA_SIM)
	@test "$$("$(GUIX)" show kanata | sed -n 's/^version: //p')" = "$(KANATA_VERSION)" || \
	  { echo 'Guix kanata differs from KANATA_VERSION; update it in the Makefile' >&2; exit 1; }
	bb desktop/build/keybindings/kanata-test.bb "$(KANATA_SIM)"

# Tests that need the keybinding log set KEYLOG_FILE themselves; the rest must
# not write to the real one.
keybindings-check: export KEYLOG_FILE = /dev/null
keybindings-check: kanata-check
	bb desktop/build/keybindings/generate.bb --check
	Hyprland --verify-config -c desktop/configs/hypr/hyprland.lua
	bb desktop/build/keybindings/test.clj
	bb desktop/build/keybindings/leader-test.clj
	bb desktop/build/keybindings/review-test.clj
	bb desktop/build/keybindings/palette-test.clj
	bb desktop/build/keybindings/winhint-test.clj
	bb desktop/build/keybindings/usage-test.clj
	emacs --batch -Q -l desktop/build/keybindings/navigate-test.el -f ert-run-tests-batch-and-exit
	emacs --batch -Q -l desktop/build/keybindings/coach-test.el -f ert-run-tests-batch-and-exit
	emacs --batch -Q -l desktop/build/keybindings/palette-test.el -f ert-run-tests-batch-and-exit
	nvim --headless '+lua local ok, err = pcall(require("fennel").dofile, "desktop/build/keybindings/nvim-test.fnl"); if not ok then print(err); vim.cmd("cquit 1") end'
	log=$$(mktemp) && env -u HERDR_PANE_ID -u HERDR_ENV -u HERDR_SOCKET_PATH KEYLOG_FILE=$$log nvim --headless '+lua local ok, err = pcall(require("fennel").dofile, "desktop/build/keybindings/nvim-leader-test.fnl"); if not ok then print(err); vim.cmd("cquit 1") end'; status=$$?; rm -f $$log; exit $$status

hypr-nested-check:
	bb desktop/build/keybindings/hypr-nested.bb

doom-baseline:
	@$(DOOM_WARNINGS) > "$(DOOM_BASELINE)"
