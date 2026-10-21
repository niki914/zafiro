---
name: add-worktree
description: Use when the user wants to start work in a new git worktree — "new worktree", "spin up a worktree/branch for this", "work on X in another worktree", "branch this off". Allocates a reusable slot (<repo parent>/worktrees/zafiro/slot-X) off origin/main with a meaningful branch name.
---

# Add Worktree

Worktrees live in reusable slot directories in a sibling directory of the primary repo:

    <parent of primary repo>/worktrees/zafiro/slot-${index}

Fixed slots (`slot-1`, `slot-2`, ...) allow Android Studio and Gradle to preserve and reuse build caches, project indexing, and window configurations across branches, while keeping Git branch names meaningful.

## 1 — Name the branch from the requirement

`<type>/<kebab-case summary>`, following the repo's branch convention (`feat/agent-status`, `refactor/aidl-state-sync`).

| Type | For |
|------|-----|
| `feat` | new capability |
| `fix` | bug fix |
| `refactor` | behaviour-preserving restructure |
| `perf` | speed / memory |
| `chore` | build, deps, tooling |
| `test` | tests only |
| `docs` | docs only |

Rules: lowercase ASCII, hyphens only, summary describes the change (`fix/latex-blank-render`, not `fix/niki-work`); ≤ 40 characters total; no ticket numbers, no dates.

The summary comes from what the user asked for. If the user asked for a worktree without specifying the task, ask before naming.

## 2 — Run the allocation script

Run the bundled script in this skill:

```bash
# Locate script relative to primary repo or execute directly
SCRIPT_PATH="$(git rev-parse --show-toplevel)/.agents/skills/add-worktree/scripts/add-worktree.sh"
# Or resolve from common dir if already inside a worktree:
GIT_COMMON_DIR="$(git rev-parse --path-format=absolute --git-common-dir)"
PRIMARY_REPO="$(cd "$GIT_COMMON_DIR/.." && pwd -P)"
SCRIPT_PATH="$PRIMARY_REPO/.agents/skills/add-worktree/scripts/add-worktree.sh"

"$SCRIPT_PATH" <branch-name> [base-ref]
```

### What the script handles automatically:
1. **Primary repo & path resolution**: Dynamically locates the repo root via `--git-common-dir` and calculates `<repo parent>/worktrees/zafiro/` without hardcoding absolute paths.
2. **Branch validation & duplicate check**: Verifies branch format and rejects collisions with existing local or remote branches.
3. **Lowest slot allocation**: Scans from `slot-1` upward:
   - Skips slots registered in `git worktree list`.
   - If an unregistered directory exists on disk, logs a warning and skips to the next slot without modifying it.
   - Allocates the lowest clean slot.
4. **Base ref**: Defaults to `origin/main` (after a non-fatal fetch), unless another base ref is passed.

Report the absolute worktree path, slot index, and branch back to the user.

## 3 — Remove a worktree

Run removal only on explicit request:

```bash
git worktree remove "<parent of primary repo>/worktrees/zafiro/slot-<index>"
git worktree prune
```
