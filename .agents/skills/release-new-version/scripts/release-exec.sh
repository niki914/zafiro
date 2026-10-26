#!/usr/bin/env bash
# release-exec.sh — Automate Phase 4 (and optional Phase 5) of Zafiro releases.
#
# Usage:
#   release-exec.sh <version_code> <version_name> <notes_file> [--xposed]
#
# Example:
#   bash .agents/skills/release-new-version/scripts/release-exec.sh 14 1.8.0 /tmp/notes.md --xposed

set -euo pipefail

# Repo root directory
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

SKILL_DIR="$REPO_ROOT/.agents/skills/release-new-version"
MAIN_REPO="niki914/zafiro"
XPOSED_REPO="Xposed-Modules-Repo/com.niki914.zafiro"

if [ "$#" -lt 3 ]; then
  echo "Usage: $0 <version_code> <version_name> <notes_file> [--xposed]" >&2
  exit 1
fi

VERSION_CODE="$1"
VERSION_NAME="$2"
NOTES_FILE="$3"
PUBLISH_XPOSED=false

if [ "${4:-}" = "--xposed" ]; then
  PUBLISH_XPOSED=true
fi

if [ ! -f "$NOTES_FILE" ]; then
  echo "Error: Notes file '$NOTES_FILE' not found!" >&2
  exit 1
fi

TAG_NAME="v${VERSION_CODE}-${VERSION_NAME}"
XPOSED_TAG="${VERSION_CODE}-${VERSION_NAME}"
RELEASE_TITLE="Release - ${VERSION_NAME}"

echo "================================================="
echo " Zafiro Automated Release Execution"
echo " Version: ${VERSION_NAME} (code: ${VERSION_CODE})"
echo " Main Tag: ${TAG_NAME}"
if [ "$PUBLISH_XPOSED" = true ]; then
  echo " Xposed Tag: ${XPOSED_TAG} (${XPOSED_REPO})"
fi
echo "================================================="

# 1. Ensure no unexpected unstaged/uncommitted files outside release scope
dirty_files=$(git status --porcelain | grep -vE 'app/build\.gradle\.kts|README.*\.md|\.agents/skills/release-new-version' || true)
if [ -n "$dirty_files" ]; then
  echo "Error: Unexpected tracked or untracked changes outside release scope:" >&2
  echo "$dirty_files" >&2
  exit 1
fi

# 2. Update code lines badge
echo "==> [1/7] Updating code lines badge..."
bash "$SKILL_DIR/scripts/update-code-badge.sh"

# 3. Bump version in app/build.gradle.kts
echo "==> [2/7] Updating app/build.gradle.kts..."
sed -i.bak -E \
  -e "s/(versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1${VERSION_CODE}/" \
  -e "s/(versionName[[:space:]]*=[[:space:]]*)\"[^\"]+\"/\1\"${VERSION_NAME}\"/" \
  app/build.gradle.kts
rm -f app/build.gradle.kts.bak

# Verify changes in build.gradle.kts
if ! grep -q "versionCode = ${VERSION_CODE}" app/build.gradle.kts || ! grep -q "versionName = \"${VERSION_NAME}\"" app/build.gradle.kts; then
  echo "Error: Failed to verify version bump in app/build.gradle.kts!" >&2
  git diff app/build.gradle.kts
  exit 1
fi

# 4. Commit and push main
echo "==> [3/7] Committing and pushing to main..."
git add app/build.gradle.kts README.md README_CN.md .agents/skills/release-new-version/
git commit -m "release: bump to ${VERSION_NAME}"
git push origin main

# 5. Tag and push tag
echo "==> [4/7] Tagging ${TAG_NAME} and pushing..."
git tag "${TAG_NAME}"
git push origin "${TAG_NAME}"

# 6. Wait for GitHub Actions release build CI
echo "==> [5/7] Waiting for GitHub Actions release build CI for ${TAG_NAME}..."
RUN_ID=""
for i in {1..15}; do
  RUN_ID=$(gh run list -R "$MAIN_REPO" --workflow=release.yml --limit=5 --json databaseId,headBranch --jq ".[] | select(.headBranch == \"${TAG_NAME}\") | .databaseId" | head -n 1)
  if [ -n "$RUN_ID" ] && [ "$RUN_ID" != "null" ]; then
    break
  fi
  echo "Workflow run not ready yet, waiting 5s (attempt $i/15)..."
  sleep 5
done

if [ -z "$RUN_ID" ] || [ "$RUN_ID" = "null" ]; then
  echo "Error: Timed out waiting for CI workflow run for tag ${TAG_NAME} to appear!" >&2
  exit 1
fi

echo "Watching GitHub Actions run ID: ${RUN_ID}..."
gh run watch "$RUN_ID" -R "$MAIN_REPO" --exit-status

# 7. Update main repo release notes & title
echo "==> [6/7] Updating GitHub Release on ${MAIN_REPO}..."
gh release edit "${TAG_NAME}" -R "$MAIN_REPO" \
  --title "${RELEASE_TITLE}" \
  --notes-file "${NOTES_FILE}"

echo "Main release updated: https://github.com/${MAIN_REPO}/releases/tag/${TAG_NAME}"

# 8. Optional: Publish to Xposed repository
if [ "$PUBLISH_XPOSED" = true ]; then
  echo "==> [7/7] Publishing to Xposed repository (${XPOSED_REPO})..."
  DOWNLOAD_DIR=$(mktemp -d -t zafiro-release-XXXXXX)
  
  gh release download "${TAG_NAME}" -R "$MAIN_REPO" -p "*.apk" --dir "$DOWNLOAD_DIR"
  APK_FILE=$(ls "$DOWNLOAD_DIR"/*.apk | head -n 1)

  if [ -z "$APK_FILE" ] || [ ! -f "$APK_FILE" ]; then
    echo "Error: Failed to download APK from main release!" >&2
    rm -rf "$DOWNLOAD_DIR"
    exit 1
  fi

  # GitHub Release asset is channel github. Xposed gets the same bytes with xposed.
  echo "Stamping xposed channel into $(basename "$APK_FILE")..."
  if ! python3 "$REPO_ROOT/tools/apk_channel.py" stamp "$APK_FILE" "$APK_FILE" xposed; then
    rm -rf "$DOWNLOAD_DIR"
    exit 1
  fi

  # Transform release notes for Xposed repository:
  # Replace contribution line with open source link as per skill convention
  XPOSED_NOTES_FILE="${DOWNLOAD_DIR}/xposed_notes.md"
  sed -E \
    -e 's/欢迎 Star、提 Issue、参与贡献/项目已开源：https:\/\/github.com\/niki914\/zafiro/g' \
    -e 's/Welcome to Star, Issue, and contribute/Open source: https:\/\/github.com\/niki914\/zafiro/g' \
    "$NOTES_FILE" > "$XPOSED_NOTES_FILE"

  # If notes do not contain the open source link, append it to both English and Chinese blocks
  if ! grep -q "https://github.com/niki914/zafiro" "$XPOSED_NOTES_FILE"; then
    awk '
      /^---$/ {
        print "\nOpen source: https://github.com/niki914/zafiro\n\n---"
        next
      }
      { print }
      END {
        print "\n项目已开源：https://github.com/niki914/zafiro"
      }
    ' "$XPOSED_NOTES_FILE" > "${XPOSED_NOTES_FILE}.tmp" && mv "${XPOSED_NOTES_FILE}.tmp" "$XPOSED_NOTES_FILE"
  fi

  gh release create "${XPOSED_TAG}" "$APK_FILE" \
    -R "$XPOSED_REPO" \
    --title "${RELEASE_TITLE}" \
    --notes-file "$XPOSED_NOTES_FILE" \
    --latest

  rm -rf "$DOWNLOAD_DIR"
  echo "Xposed release published: https://github.com/${XPOSED_REPO}/releases/tag/${XPOSED_TAG}"
else
  echo "==> [7/7] Skipped Xposed repository release as requested."
fi

echo "================================================="
echo " Release ${VERSION_NAME} completed successfully! "
echo "================================================="
