#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat << 'EOF'
Usage: add-worktree.sh [options] <branch-name> [base-ref]

Allocates the lowest available slot (slot-1, slot-2, ...) under
<repo-parent>/worktrees/zafiro/ and creates a git worktree.

Options:
  -b, --base <ref>    Base ref to branch off (default: origin/main)
  -h, --help          Show this help message

Arguments:
  <branch-name>       Name of the branch to create (e.g. feat/my-feature)
  [base-ref]          Base ref to branch off (positional alternative to -b)

Examples:
  ./add-worktree.sh feat/copy-assist
  ./add-worktree.sh fix/dialog-leak dev
EOF
    exit 1
}

BRANCH=""
BASE_REF="origin/main"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            usage
            ;;
        -b|--base)
            if [[ $# -lt 2 ]]; then
                echo "Error: --base requires a reference argument." >&2
                exit 1
            fi
            BASE_REF="$2"
            shift 2
            ;;
        -*)
            echo "Error: Unknown option '$1'" >&2
            usage
            ;;
        *)
            if [[ -z "$BRANCH" ]]; then
                BRANCH="$1"
            elif [[ "$BASE_REF" == "origin/main" ]]; then
                BASE_REF="$1"
            else
                echo "Error: Unexpected additional argument '$1'" >&2
                usage
            fi
            shift
            ;;
    esac
done

if [[ -z "$BRANCH" ]]; then
    echo "Error: Branch name is required." >&2
    usage
fi

# Validate branch name format
if ! git check-ref-format --branch "$BRANCH" 2>/dev/null; then
    echo "Error: '$BRANCH' is not a valid git branch name." >&2
    exit 1
fi

# Locate primary repo and root
GIT_COMMON_DIR="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" || {
    echo "Error: Not inside a git repository." >&2
    exit 1
}
PRIMARY_REPO="$(cd "$GIT_COMMON_DIR/.." && pwd -P)"
PARENT_DIR="$(dirname "$PRIMARY_REPO")"
WT_ROOT="$PARENT_DIR/worktrees/zafiro"

# Fetch latest refs from origin (non-fatal if offline)
echo "Fetching origin..."
git fetch origin --prune 2>/dev/null || echo "Warning: Failed to fetch from origin, using local refs." >&2

# Validate base-ref exists
if ! git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
    echo "Error: Base ref '$BASE_REF' does not exist." >&2
    exit 1
fi

# Check for duplicate branch names
if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
    echo "Error: Local branch '$BRANCH' already exists." >&2
    exit 1
fi

if [[ "$BASE_REF" == "origin/main" ]] && git show-ref --verify --quiet "refs/remotes/origin/$BRANCH"; then
    echo "Error: Remote branch 'origin/$BRANCH' already exists. Choose a different name or specify base explicitly." >&2
    exit 1
fi

# Ensure worktree root directory exists
mkdir -p "$WT_ROOT"

# Get currently active worktree paths
ACTIVE_WORKTREES="$(git worktree list --porcelain | grep '^worktree ' | cut -d' ' -f2-)"

# Find lowest free slot (starting from slot-1)
INDEX=1
TARGET_SLOT=""
while true; do
    SLOT_PATH="$WT_ROOT/slot-$INDEX"

    IS_REGISTERED=0
    if echo "$ACTIVE_WORKTREES" | grep -Fxq "$SLOT_PATH"; then
        IS_REGISTERED=1
    fi

    EXISTS_ON_DISK=0
    if [[ -e "$SLOT_PATH" ]]; then
        EXISTS_ON_DISK=1
    fi

    if [[ "$IS_REGISTERED" -eq 1 ]]; then
        INDEX=$((INDEX + 1))
        continue
    fi

    if [[ "$EXISTS_ON_DISK" -eq 1 ]]; then
        echo "Warning: '$SLOT_PATH' exists on disk but is not a registered worktree. Skipping it and picking next slot." >&2
        INDEX=$((INDEX + 1))
        continue
    fi

    TARGET_SLOT="$SLOT_PATH"
    break
done

echo "Allocating $TARGET_SLOT (slot-$INDEX) for branch '$BRANCH' (based on '$BASE_REF')..."
git worktree add -b "$BRANCH" "$TARGET_SLOT" "$BASE_REF"

BASE_COMMIT="$(git rev-parse --short "$BASE_REF")"
echo ""
echo "Worktree created successfully:"
echo "  Branch: $BRANCH"
echo "  Slot:   slot-$INDEX"
echo "  Path:   $TARGET_SLOT"
echo "  Base:   $BASE_REF ($BASE_COMMIT)"
echo ""
git -C "$TARGET_SLOT" status -sb
