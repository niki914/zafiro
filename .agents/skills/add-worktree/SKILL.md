---
name: add-worktree
description: Use when the user wants to start work in a new git worktree — "new worktree", "spin up a worktree/branch for this", "work on X in another worktree", "branch this off". Names the branch from the request and creates `<repo parent>/zafiro-worktrees/<branch>` off origin/main.
---

# Add Worktree

Worktrees live in a sibling directory of the primary repo, one directory per branch:

    <parent of primary repo>/zafiro-worktrees/<branch name>

The directory is the branch name, character for character. A slashed name (`fix/latex-render`) nests: `zafiro-worktrees/fix/latex-render`. `dev-to-main` at `/Users/niki/.repo/android/zafiro-worktrees/dev-to-main` is a lived example of the layout.

## 1 — Name the branch from the requirement

`<type>/<kebab-case summary>`, following the repo's existing branch convention (`feat/agent-status`, `refactor/aidl-state-sync`).

| Type | For |
|------|-----|
| `feat` | new capability |
| `fix` | bug fix |
| `refactor` | behaviour-preserving restructure |
| `perf` | speed / memory |
| `chore` | build, deps, tooling |
| `test` | tests only |
| `docs` | docs only |

Rules: lowercase ASCII, hyphens only, summary describes the change (`fix/latex-blank-render`, not `fix/niki-work`); ≤ 40 characters total, since the name is also the directory name; no ticket numbers, no dates.

The summary comes from what the user asked for. If the user asked for a worktree without saying what the work is, ask before naming — a branch name guessed from nothing is a rename later.

## 2 — Resolve the paths

```bash
REPO=$(git rev-parse --show-toplevel)
WT_ROOT="$(dirname "$REPO")/zafiro-worktrees"
BRANCH=fix/latex-blank-render          # from step 1
git worktree list                      # refuse to reuse a path or branch already listed
```

Derive `WT_ROOT` from the repo; do not hardcode an absolute path.

## 3 — Create it

Base is `origin/main` unless the user names another base (`dev`, a release tag).

```bash
git fetch origin
git worktree add -b "$BRANCH" "$WT_ROOT/$BRANCH" origin/main
git -C "$WT_ROOT/$BRANCH" status -sb | head -3
```

To continue an existing branch instead of creating one, drop `-b`.

Report the absolute worktree path back to the user.

## 4 — Remove it

```bash
git worktree remove "$WT_ROOT/<branch>"
git worktree prune
```

Run removal only on explicit request; leftover worktrees with uncommitted work are the user's to discard.
