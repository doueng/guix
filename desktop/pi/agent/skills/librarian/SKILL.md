---
name: librarian
description: "Cache and refresh remote git repositories under ~/.cache/checkouts/<host>/<org>/<repo> so future references can reuse a local copy. Use this skill when the user points you to a remote git repository as reference or you encountered a remote git repo through other means."
---

Use this skill when the user points you to a remote git repository (GitHub/GitLab/Bitbucket URLs, `git@...`, or `owner/repo` shorthand).

The goal is to keep a reusable local checkout that is:
- **stable** (predictable path)
- **up to date** (periodic fetch + fast-forward when safe)
- **efficient** (partial clone with `--filter=blob:none`, no repeated full clones)

## Cache location

Repositories are stored at:

`~/.cache/checkouts/<host>/<org>/<repo>`

Example:

`github.com/mitsuhiko/minijinja` → `~/.cache/checkouts/github.com/mitsuhiko/minijinja`

## Command

```bash
bash checkout.sh <repo> --path-only
```

Examples:

```bash
bash checkout.sh mitsuhiko/minijinja --path-only
bash checkout.sh github.com/mitsuhiko/minijinja --path-only
bash checkout.sh https://github.com/mitsuhiko/minijinja --path-only
```

The script will:
1. Parse the repo reference into host/org/repo.
2. Clone if missing.
3. Reuse existing checkout if present.
4. Fetch from `origin` when stale (default interval: 300s).
5. Attempt a fast-forward merge if the checkout is clean and has an upstream.

## Update strategy

- Default behavior is **throttled refresh** (every 5 minutes) to avoid unnecessary network calls.
- Force immediate refresh with:

```bash
bash checkout.sh <repo> --force-update --path-only
```

## Recommended workflow

1. Resolve repository path via `checkout.sh --path-only`.
2. Use that path for searching, reading, and analysis. Read-only Git metadata queries (for example, `git -C <path> tag --sort=-version:refname`) are allowed; do not mutate the shared checkout.
3. On later references to the same repo, call `checkout.sh` again; it will find and update the cached checkout.

The Pi Git guard uses the target of `git -C`, not the shell's initial directory. Literal paths and simple literal assignments such as `C=~/.cache/checkouts/github.com/owner/repo; git -C "$C" log` are supported. For computed paths, resolve the path first and pass it explicitly.

Read-only exports are allowed to stdout or files under `/tmp`:

```bash
git -C ~/.cache/checkouts/github.com/owner/repo archive HEAD path | tar -x -C /tmp/task-copy
git -C ~/.cache/checkouts/github.com/owner/repo archive HEAD path > /tmp/source.tar
git -C ~/.cache/checkouts/github.com/owner/repo diff OLD NEW -- path > /tmp/delta.patch
git -C ~/.cache/checkouts/github.com/owner/repo show HEAD:path | diff - /tmp/local-copy
```

Create the extraction directory first. `ls-tree`, `tag` listing and `worktree list` are also allowed; commands that create tags or modify worktrees remain blocked. Redirections and export output options must not write into the shared cache. The guard is a best-effort shell policy, not a sandbox.

## If edits are needed

Prefer not to edit directly in the shared cache. Create a separate worktree or copy from the cached checkout for task-specific modifications.

## Notes

- `owner/repo` defaults to `github.com`.
