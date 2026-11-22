CONFIG ?= desktop/system.scm
HOME_CONFIG ?= desktop/home.scm
CHANNELS ?= channels.scm
MODULES ?= modules

RECONFIGURE_FLAGS ?= --no-kexec

GC_AGE ?= 2w

GUIX := $(HOME)/.config/guix/current/bin/guix
JOBS ?=
JOBS_FLAG = $(if $(JOBS),--max-jobs=$(JOBS))
COMMON := -L "$(MODULES)"
# Fails when a built Home's user shepherd.conf would not load at login (T216).
CHECK_SHEPHERD := bb desktop/build/home/check-shepherd.bb

.PHONY: help lint affected verify pull channels-current check system-facts dry-run build repro-build switch gc \
        home-build home-apply stow pierre-diff-ansi pierre-diff-ansi-test bbh-web-highlights dusk dusk-test dusk-verify rexmux rexmux-test rexmux-verify rexmux-tla ghostty-rex ghostty-rex-test ghostty-rex-verify ghostty-rex-reload mux oilfm oilfm-test pi-sci pi-sci-test \
        builder-create builder-deploy prebuild builder-check builder-test reader-push reader-status second-brain-status second-brain-test updates-test md-view-test ext-reload-test chrome-e2e \
        doom-check doom-env-test doom-baseline bbh-runs bbh-tla-check bombadil skill-diet-check keybindings-check kanata-check hypr-nested-check chrome-palette-check chrome-launch-check chrome-fullscreen-check test-display-check clipboard-check clipboard-e2e \
        clipboard-history-test taildrop-test parinfer-check parinfer-fix

help:
	@echo 'lint          fast checks for every change: Scheme compiles, scripts parse, Lisp is parinfer-stable, docs agree'
	@echo 'parinfer-check list Lisp files parinfer would change on open (ROOT=dir for another repo)'
	@echo 'parinfer-fix  reindent those files with parinfer paren mode (ROOT=dir for another repo)'
	@echo 'affected      list the checks covering files changed in REV (default @)'
	@echo 'verify        run lint and the affected checks; SLOW=1 adds the slow ones'
	@echo 'pull          update the user Guix profile from $(CHANNELS)'
	@echo 'channels-current fail unless the pulled Guix matches $(CHANNELS)'
	@echo 'check         check system facts, then build system and Home without activating'
	@echo 'system-facts  print folded daemon, sysctl, Shepherd and mount config; lint mounts'
	@echo 'dry-run       show what system build would fetch or build'
	@echo 'build         build $(CONFIG) without activating it; fail if its Home user Shepherd config would not load'
	@echo 'repro-build   build system using the pinned channels via time-machine'
	@echo 'switch        check system facts, build and check, then activate system (sudo; writes ESP)'
	@echo 'gc            delete generations older than $(GC_AGE), then collect garbage'
	@echo 'builder-create once: create the Hetzner builder from desktop/builder.scm (spends money)'
	@echo 'builder-deploy reconfigure the builder over the tailnet'
	@echo 'prebuild      push @ to the builder and build system, Home and pull there'
	@echo 'builder-check builder publish reachable, last prebuild, nothing to build locally at the channels.scm pins'
	@echo 'builder-test  test builder-prebuild status alerts and root pruning'
	@echo 'reader-push   copy the reader checkout (READER_DIR) to the builder and restart it'
	@echo 'reader-status the builder reader services and the headlines it serves'
	@echo 'second-brain-status the builder second brain: service, items, its page'
	@echo 'second-brain-test test the second brain glue on the builder'
	@echo 'updates-test  test the updates cache, rows and changelog links'
	@echo 'md-view-test  test the markdown-to-Chrome renderer'
	@echo 'ext-reload-test build, reload and retab a test extension through the ext-reload hub in a headless Chrome'
	@echo 'chrome-e2e    run the e2e suite of each Chrome extension (bb e2e in desktop/build/chrome/*) in headless Chrome'
	@echo 'home-build    build Home without activating it; fail if its user Shepherd config would not load'
	@echo 'home-apply    home-build, activate Home, then stow live configs'
	@echo 'stow          build SCI and install live config links using GNU Stow'
	@echo 'pi-sci        compile the pinned SCI codemode runtime'
	@echo 'pi-sci-test   build and verify SCI codemode with the installed Pi'
	@echo 'bbh-runs      write the newest $(BBH_RUNS) bbh conversations, prompts, tool calls and workflows to $(BBH_RUNS_OUT)'
	@echo 'bbh-tla-check model-check the bbh super and wf state machines with TLA+ (desktop/build/bbh/verify/tla)'
	@echo 'bombadil      Bombadil types into the bbh Inbox, then the Worker view, in a capped emacs -nw for BOMBADIL_TIME s each (default 120); VIEW=inbox|worker runs one, SEED=N replays a fixture'
	@echo 'skill-diet-check check the skill-diet links and the bbh eval adapter'
	@echo 'pierre-diff-ansi install the highlighter behind jjui diff previews'
	@echo 'pierre-diff-ansi-test highlight a sample diff and check the bbh highlights.js bundle'
	@echo 'bbh-web-highlights bundle that highlighter for the bbh website'
	@echo 'dusk          build the dusk shell (desktop/build/dusk)'
	@echo 'dusk-test     run isolated runtime and headless GPUI tests'
	@echo 'dusk-verify   run dusk end-to-end checks on the live session'
	@echo 'rexmux        build the rexmux Rex client (desktop/build/rexmux)'
	@echo 'rexmux-test   rexmux sidecar unit and property tests (make lint runs them)'
	@echo 'rexmux-verify rexmux live tests on a throwaway Rex server: the sidecar, then Bombadil over rexmux tty (REXMUX_BOMBADIL_TIME s, default 120)'
	@echo 'rexmux-tla    model-check the rexmux link, pane, stream and agent model with TLA+'
	@echo 'ghostty-rex   build rexg-render, the C half of the ghostty-rex client, into its target/ (desktop/build/ghostty-rex)'
	@echo 'ghostty-rex-test ghostty-rex client unit and property tests: keys, model, hints, screen (make lint runs them)'
	@echo 'ghostty-rex-verify ghostty-rex live tests on the hidden test display and a throwaway Rex server, then Bombadil over the client'
	@echo 'ghostty-rex-reload restart the open ghostty-rex window'\''s client on the same session, so bb and config.edn edits take effect'
	@echo 'mux           choose the terminal multiplexer: MUX=rex builds rexmux, MUX=ghostty-rex uses Ghostty with the bb client, MUX=herdr rolls back (sets %terminal-mux, then home-apply)'
	@echo 'oilfm         build the oilfm file manager (~/projects/oilfm)'
	@echo 'oilfm-test    run oilfm tests, headless GPUI ones included'
	@echo 'doom-check    fail on doom doctor warnings missing from the baseline'
	@echo 'doom-env-test check that doom env and daemon frames keep the Hyprland signature current'
	@echo 'doom-baseline accept the current doom doctor warnings'
	@echo 'kanata-check  run kanata.kbd chord tests in the kanata simulator'
	@echo 'keybindings-check kanata-check plus generated-binding, Hyprland, Emacs and Neovim tests'
	@echo 'hypr-nested-check test Hyprland keybindings in a nested session'
	@echo 'chrome-palette-check run launcher Chrome rows (passwords, extensions, pin, mute) in a Chrome on the hidden test display'
	@echo 'chrome-launch-check run Cmd+Space o n as the launcher does on the hidden test display, with Chrome closed and running'
	@echo 'chrome-fullscreen-check press F on a video in a throwaway Chrome in nested Hyprland: fills the screen, Esc back to the tile'
	@echo 'test-display-check open a headed Chrome in the headless test display and prove the user'\''s screen and focus stay untouched'
	@echo 'clipboard-check yank and paste between Emacs, Neovim and the Wayland clipboard (uses the live clipboard)'
	@echo 'clipboard-e2e Cmd+C and Cmd+V right after a focus switch in Chrome, terminal Emacs and Neovim, on the hidden test display'
	@echo 'clipboard-history-test check what clipboard-history stores and skips, in a throwaway cliphist db'
	@echo 'taildrop-test unit checks for the Taildrop send and receive script'

REV ?= @
parinfer-check:
	"$(HOME)/.guix-home/profile/bin/bb" desktop/build/checks/parinfer.bb $(if $(ROOT),--root "$(ROOT)")

parinfer-fix:
	"$(HOME)/.guix-home/profile/bin/bb" desktop/build/checks/parinfer.bb --fix $(if $(ROOT),--root "$(ROOT)")

lint:
	"$(HOME)/.guix-home/profile/bin/bb" desktop/build/checks/lint.bb

affected:
	@"$(HOME)/.guix-home/profile/bin/bb" desktop/build/checks/affected.bb $(REV)

verify:
	"$(HOME)/.guix-home/profile/bin/bb" desktop/build/checks/affected.bb --run $(if $(SLOW),--slow) $(REV)

pull:
	"$(GUIX)" pull $(JOBS_FLAG) -C "$(CHANNELS)"

channels-current:
	@pinned=$$(grep -o '(commit "[0-9a-f]*")' "$(CHANNELS)" | sort); \
	pulled=$$("$(GUIX)" describe -f channels | grep -o '(commit "[0-9a-f]*")' | sort); \
	test "$$pinned" = "$$pulled" || { echo '$(CHANNELS) differs from the pulled Guix; run make pull' >&2; exit 1; }

check: system-facts build

system-facts: channels-current
	"$(GUIX)" repl -L "$(MODULES)" -- desktop/system-facts.scm

dry-run: channels-current
	"$(GUIX)" system build --dry-run $(COMMON) "$(CONFIG)"

build: channels-current
	out=$$(GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  "$(GUIX)" system build $(JOBS_FLAG) $(COMMON) "$(CONFIG)") && echo "$$out" && \
	  $(CHECK_SHEPHERD) "$$out"

repro-build:
	GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  "$(GUIX)" time-machine -C "$(CHANNELS)" -- \
	  system build $(COMMON) "$(CONFIG)"

switch: channels-current system-facts build
	sudo rm -f \
	  /boot/efi/m1n1/boot.bin.old \
	  /boot/efi/m1n1/boot.bin.new
	sudo env GC_FREE_SPACE_DIVISOR=$${GC_FREE_SPACE_DIVISOR:-1} \
	  "$(GUIX)" system reconfigure $(RECONFIGURE_FLAGS) $(JOBS_FLAG) \
	    -L "$(abspath $(MODULES))" \
	    "$(abspath $(CONFIG))"
	@# A reconfigure leaves kanata running on the old /etc/kanata/kanata.kbd.
	-desktop/bin/hyprkan reload

gc:
	sudo "$(GUIX)" system delete-generations $(GC_AGE)
	"$(GUIX)" home delete-generations $(GC_AGE)
	"$(GUIX)" pull --delete-generations=$(GC_AGE)
	"$(GUIX)" gc

home-build: channels-current
	out=$$("$(GUIX)" home build $(JOBS_FLAG) $(COMMON) "$(HOME_CONFIG)") && echo "$$out" && \
	  $(CHECK_SHEPHERD) "$$out"

BUILDER_TOKEN ?= $(HOME)/.config/guix-builder/hcloud-token

builder-create:
	test -r "$(BUILDER_TOKEN)" || { echo '$(BUILDER_TOKEN) is missing; see the plan, phase 2' >&2; exit 1; }
	GUIX_HETZNER_API_TOKEN="$$(cat "$(BUILDER_TOKEN)")" BUILDER_BOOTSTRAP=1 \
	  "$(GUIX)" deploy $(COMMON) desktop/builder.scm

builder-deploy:
	"$(GUIX)" deploy $(COMMON) desktop/builder.scm

prebuild:
	desktop/bin/builder-prebuild build

builder-check:
	desktop/bin/builder-prebuild check

builder-test:
	bb desktop/build/builder/test.bb

reader-push:
	desktop/bin/reader-push code

reader-status:
	desktop/bin/reader-push status

second-brain-status:
	desktop/bin/second-brain-push status

second-brain-test:
	bb desktop/build/second-brain/test.bb

updates-test:
	bb desktop/build/updates/test.bb

md-view-test:
	bb desktop/build/md-view/test.bb

ext-reload-test:
	node desktop/build/chrome/ext-reload/test.mjs

chrome-e2e:
	for d in desktop/build/chrome/*/bb.edn; do (cd "$$(dirname "$$d")" && bb e2e) || exit 1; done

PI_SCI := desktop/build/pi-sci
PI_SCI_OUTPUTS := $(PI_SCI)/dist/sci.js $(addprefix $(PI_SCI)/dist/pi-,grep.mjs find.mjs read.mjs)
PI_SCI_SOURCES := $(addprefix $(PI_SCI)/,build.bb compiler.edn deps.edn externs.js \
                    package-lock.json scripts/prepare-search.mjs) \
                  $(wildcard $(PI_SCI)/src/pi/*)

$(PI_SCI_OUTPUTS) &: $(PI_SCI_SOURCES)
	$(PI_SCI)/build.bb

pi-sci: $(PI_SCI_OUTPUTS)

pi-sci-test: pi-sci
	desktop/build/pi-sci/verify.bb

BBH_RUNS ?= 10
BBH_RUNS_OUT ?= $(HOME)/.cache/bbh/runs.html
bbh-runs:
	cd desktop/build/bbh && bb runs:html $(BBH_RUNS) "$(abspath $(BBH_RUNS_OUT))"

bbh-tla-check:
	cd desktop/build/bbh && bb verify:tla

BOMBADIL_TIME ?= 120
BOMBADIL_VIEWS = $(or $(VIEW),inbox worker)
bombadil:
	cd desktop/build/bbh && for view in $(BOMBADIL_VIEWS); do bb test:bombadil --view $$view --time $(BOMBADIL_TIME) $(if $(SEED),--seed $(SEED)) || exit 1; done

SKILL_DIET := desktop/pi/agent/skills/skill-diet
skill-diet-check:
	node $(SKILL_DIET)/scripts/eval.mjs check $(SKILL_DIET)
	bb desktop/build/skill-diet/adapter-test.bb

PIERRE_DIFF := desktop/build/pierre-diff-ansi

$(PIERRE_DIFF)/node_modules: $(PIERRE_DIFF)/package.json $(PIERRE_DIFF)/bun.lock
	cd $(PIERRE_DIFF) && bun install --frozen-lockfile
	touch $@

pierre-diff-ansi: $(PIERRE_DIFF)/node_modules

pierre-diff-ansi-test: pierre-diff-ansi
	bb $(PIERRE_DIFF)/test.bb

bbh-web-highlights: $(PIERRE_DIFF)/node_modules
	cd $(PIERRE_DIFF) && bun build browser.ts --target=browser --format=iife --minify --outfile=../bbh/src/bbh/web/highlights.js

stow: pi-sci pierre-diff-ansi
	"$(HOME)/.guix-home/profile/bin/bb" desktop/stow-home

home-apply: channels-current home-build
	"$(GUIX)" home reconfigure $(JOBS_FLAG) $(COMMON) "$(HOME_CONFIG)"
	$(MAKE) stow

dusk:
	desktop/build/dusk/build.bb

dusk-test:
	desktop/build/dusk/test.bb

dusk-verify:
	"$(HOME)/.guix-home/profile/bin/bb" desktop/build/dusk/verify.bb

rexmux:
	desktop/build/rexmux/build.bb

rexmux-test:
	cd desktop/build/rexmux && "$(HOME)/.guix-home/profile/bin/bb" test

rexmux-verify:
	cd desktop/build/rexmux && "$(HOME)/.guix-home/profile/bin/bb" verify

rexmux-tla:
	cd desktop/build/rexmux && "$(HOME)/.guix-home/profile/bin/bb" verify:tla

ghostty-rex:
	cd desktop/build/ghostty-rex && "$(HOME)/.guix-home/profile/bin/bb" build

ghostty-rex-test:
	cd desktop/build/ghostty-rex && "$(HOME)/.guix-home/profile/bin/bb" test

ghostty-rex-verify:
	cd desktop/build/ghostty-rex && "$(HOME)/.guix-home/profile/bin/bb" verify

ghostty-rex-reload:
	rex -C ghostty-rex do mx.client.reload

mux:
	@test "$(MUX)" = rex -o "$(MUX)" = ghostty-rex -o "$(MUX)" = herdr || { echo 'usage: make mux MUX=rex|ghostty-rex|herdr' >&2; exit 2; }
	@if [ "$(MUX)" = rex ]; then $(MAKE) rexmux; fi
	"$(HOME)/.guix-home/profile/bin/bb" desktop/build/rexmux/switch.bb $(MUX)
	$(MAKE) home-apply
	-hyprctl reload >/dev/null
	@echo 'Now on $(MUX): Super+B opens it. make mux MUX=$(if $(filter herdr,$(MUX)),ghostty-rex,herdr) switches back.'

OILFM ?= $(HOME)/projects/oilfm

oilfm:
	cd "$(OILFM)" && scripts/vendor-gpui && scripts/cargo build --release -p oilfm

oilfm-test:
	cd "$(OILFM)" && scripts/vendor-gpui && scripts/cargo test --workspace --features oilfm/ui-tests

# Doom's CLI reads piped stdin to EOF (hangs under an open pipe), so give it /dev/null.
# doom doctor exits 0 despite missing tools, so diff its warnings against a baseline.
DOOM ?= $(HOME)/.config/emacs/bin/doom
DOOM_BASELINE := desktop/build/doom/doctor-baseline.txt
DOOM_WARNINGS = out=$$(cd "$(HOME)" && "$(DOOM)" doctor 2>&1 </dev/null) || { printf '%s\n' "$$out" >&2; exit 1; }; \
	printf '%s\n' "$$out" | awk '/^ *> :/ { m = $$2 " " $$3 ": " } /^ *! / { sub(/^ *! /, ""); print m $$0 }' | LC_ALL=C sort

doom-check:
	@warnings=$$($(DOOM_WARNINGS)) || exit 1; \
	new=$$(printf '%s\n' "$$warnings" | LC_ALL=C comm -23 - "$(DOOM_BASELINE)"); \
	test -z "$$new" || { printf 'New doom doctor warnings (accept with make doom-baseline):\n%s\n' "$$new" >&2; exit 1; }; \
	echo 'doom doctor: no new warnings'

KANATA_VERSION := 1.11.0
KANATA_SRC := $(HOME)/.cache/kanata-sim/$(KANATA_VERSION)
KANATA_SIM := $(KANATA_SRC)/target/release/kanata_simulated_input

$(KANATA_SIM):
	rm -rf "$(KANATA_SRC)"
	git clone --quiet --depth 1 --branch v$(KANATA_VERSION) https://github.com/jtroo/kanata "$(KANATA_SRC)"
	cd "$(KANATA_SRC)" && "$(GUIX)" shell rust rust:cargo gcc-toolchain -- cargo build --release --quiet -p kanata-sim

clipboard-check:
	bb desktop/build/clipboard/check.bb

doom-env-test:
	emacs --batch -l desktop/build/doom/session-env-test.el

clipboard-e2e:
	test-display --name clipboard-e2e -- bb desktop/build/clipboard/e2e.bb

clipboard-history-test:
	bb desktop/build/clipboard/history-test.bb

taildrop-test:
	bb desktop/build/taildrop/test.bb

kanata-check: $(KANATA_SIM)
	@test "$$("$(GUIX)" show kanata | sed -n 's/^version: //p')" = "$(KANATA_VERSION)" || \
	  { echo 'Guix kanata differs from KANATA_VERSION; update it in the Makefile' >&2; exit 1; }
	bb desktop/build/keybindings/kanata-test.bb "$(KANATA_SIM)"

# Tests must not write the real keybinding log; those that need one set KEYLOG_FILE.
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
	log=$$(mktemp) && env -u HERDR_PANE_ID -u HERDR_ENV -u HERDR_SOCKET_PATH KEYLOG_FILE=$$log nvim --headless '+lua local ok, err = pcall(require("fennel").dofile, "desktop/build/keybindings/nvim-leader-test.fnl"); if not ok then print(err); vim.cmd("cquit 1") end'; status=$$?; rm -f $$log; exit $$status

hypr-nested-check:
	bb desktop/build/keybindings/hypr-nested.bb

chrome-palette-check:
	node desktop/build/keybindings/chrome-palette-e2e.mjs

chrome-launch-check:
	bb desktop/build/keybindings/chrome-launch-e2e.bb

chrome-fullscreen-check:
	bb desktop/build/hypr/chrome-fullscreen-check.bb

test-display-check:
	desktop/bin/test-display check

doom-baseline:
	@$(DOOM_WARNINGS) > "$(DOOM_BASELINE)"
