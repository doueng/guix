# pi-org.nvim Test Suite

Headless test suite for the `pi-org` Neovim plugin, using
[plenary.nvim](https://github.com/nvim-lua/plenary.nvim) as the test runner
and [Fennel](https://fennel-lang.org) for spec files.

## Running

There is currently no wired-up test runner in this repository. Run the
compile-check and headless specs separately; `minimal-init.lua` documents the
Neovim invocation. Compile-check the `*_spec.fnl` files before spawning test
processes: a Fennel parse error inside `plenary.busted.run` can hang Neovim.

## Structure

```
desktop/tests/nvim/pi-org/
├── minimal-init.lua        # Test bootstrap: Fennel loader, lazy.nvim, pi-org.setup
├── compile-check.lua       # Pre-compiles .fnl specs to catch syntax errors early
├── fixtures/
│   ├── stub-pi             # Babashka script: deterministic stand-in for `pi --mode rpc`
│   ├── turn-text-only.jsonl  # Fixture: streaming text turn
│   └── turn-with-tool.jsonl  # Fixture: turn with thinking + tool call + result
└── spec/
    ├── helpers.fnl         # Shared helper: ensure-setup (re-initializes pi-org)
    ├── jsonl_spec.fnl      # 9 tests — JSONL framing
    ├── config_spec.fnl     # 13 tests — config defaults + validation
    ├── rpc_spec.fnl        # 8 tests — RPC dispatch + correlation
    ├── render_spec.fnl     # 11 tests — org-mode rendering
    ├── ui_spec.fnl         # 3 tests — window layout + keymaps
    └── workflow_spec.fnl   # 3 tests — full integration journey
```

## Writing Specs

Specs are written in **Fennel** and use plenary's `busted`-compatible syntax
(`describe`, `it`, `before_each`, `after_each`). The Fennel searcher is
installed by `minimal-init.lua`, and `loadfile` is patched to compile `.fnl`
files so plenary's runner can load them.

### Fennel/Lua interop conventions

When testing Fennel modules from Fennel specs, hyphenated keys are natural:

```fennel
;; Good — Fennel handles hyphens natively
(render.make-handler state)
(. session.state :trans-buf)
```

### Fennel syntax pitfalls

These are the most common mistakes when writing Fennel specs. The
compile-check step catches most of them before runtime, but knowing them
saves iteration time:

1. **Method calls**: Fennel doesn't support Lua's `obj:method()` syntax.
   Use `(: obj :method args)`:
   ```fennel
   ;; Wrong: (s:match "pattern")
   ;; Right: (: s :match "pattern")
   ```

2. **Table indexing**: Fennel doesn't support `tbl[1]`. Use `(. tbl 1)`:
   ```fennel
   ;; Wrong: (lines[1]:find "a")
   ;; Right: (: (. lines 1) :find "a")
   ```

3. **Multiple return values**: `(local a b expr)` is invalid. Use
   destructuring:
   ```fennel
   ;; Wrong: (local st buf (fresh-state))
   ;; Right: (local (st buf) (fresh-state))
   ```

4. **Table literals with hyphenated values**: `{: key val-with-hyphen}` fails
   because the parser splits at the hyphen. Attach the key to the opening
   brace:
   ```fennel
   ;; Wrong: {: win trans-win}   ; parses as :win, trans, -win (3 values)
   ;; Right: {:win trans-win}    ; parses as :win=trans-win (2 values)
   ```

5. **`assert.is_true` checks for exactly `true`**, not truthy. `:match` and
   `:find` return strings/numbers, so wrap with `(not= ... nil)`:
   ```fennel
   ;; Wrong: (is-true (: s :match "pattern"))   ; returns string, not true
   ;; Right: (is-true (not= (: s :match "pattern") nil))
   ```

### Shared state across `before_each`/`it`

Plenary's `busted` runner uses coroutines, and `var` upvalues at the
`describe` level are **not shared** between `before_each` and `it` closures.
Use a shared table instead:

```fennel
(describe "my module"
  (fn []
    (local ctx {})

    (before_each
      (fn []
        (tset ctx :client (make-client))))

    (it "does something"
      (fn []
        (local client ctx.client)
        ...))))
```

## UI testing constraints (nvim 0.12+)

nvim 0.12 removed in-process access to `nvim_ui_attach` (and related
`nvim_ui_*` functions) — they're remote-RPC-only. This means the classic
`nvim_ui_attach` + `screenstring` virtual-screen approach **does not work**
headless in 0.12+.

Instead, assert on APIs that are queryable without a virtual screen:

- **Window options**: `nvim_get_option_value("winbar", {win=...})`
- **Window geometry**: `nvim_win_get_position`, `nvim_win_get_height/width`
- **Buffer-local keymaps**: `nvim_buf_get_keymap`
- **Buffer contents**: `nvim_buf_get_lines`
- **Cursor position**: `nvim_win_get_cursor`

## Headless nvim quirks

- **One spec per nvim process**: `plenary.busted.run()` calls `vim.cmd('qa')`
  when done in headless mode, killing the process. Run each spec file in a
  separate `nvim --headless` invocation.
- **`XDG_CONFIG_HOME`**: Set to a temp dir so nvim doesn't load the system
  `init.lua` (which may require plugins not installed in the test env).
- **`PlenaryBustedDirectory`** is async and doesn't print results or exit
  cleanly headless — don't use it. Use `plenary.busted.run(file)` instead.
