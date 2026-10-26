---
name: release-new-version
description: Use when the user wants to release a new Zafiro version — drafting bilingual release notes, deciding the next version number, bumping app/build.gradle.kts, tagging, and publishing to GitHub (main repo, optionally the Xposed repo).
---

# Release New Version

Release workflow for Zafiro.

Repos involved:

| Repo | Tag format | Release notes |
|------|-----------|---------------|
| `niki914/zafiro` (main, open source) | `v<code>-<name>` e.g. `v8-1.1.0` | Full bilingual notes |
| `Xposed-Modules-Repo/com.niki914.zafiro` (closed) | `<code>-<name>` e.g. `8-1.1.0` | Same feature entries, closing line points back to the main repo. **Optional — ask the user every time.** |

CI builds and signs one APK when a tag is pushed to the main repo, stamps channel `github` into its APK Signing Block, and uploads that file as the GitHub Release asset. The Xposed repo has no CI. Its release is a manual `gh` operation: download that asset, stamp channel `xposed` over it, and upload the result. The signature and certificate stay the same. Never build locally.

## Phase 1 — Gather facts

Collect all of this before asking the user anything:

1. **Local version**: read `versionCode` / `versionName` from `app/build.gradle.kts`.
2. **Latest release baseline**: `gh release view -R niki914/zafiro` (returns the latest non-draft, non-prerelease release by default). Parse its tag `v<code>-<name>`.
3. **Version string locations**: find every place the current version literal lives:
   ```bash
   rg -n '<current versionName>' -g '*.{md,kt,kts,txt,py}' .
   ```
   Only scan these extensions: `md`, `kt`, `kts`, `txt`, `py`. Nothing else. rg respects `.gitignore` by default — do not disable that.
4. **Changes since last release**: commits and merged PRs from the last release tag to `origin/main`:
   ```bash
   git log v<code>-<name>..origin/main --oneline --no-merges
   gh pr list -R niki914/zafiro --state merged --limit 50 --json number,title --jq '.[] | "\(.number) \(.title)"'
   ```
5. If local `versionCode`/`versionName` do not match the latest release tag: **stop** and ask the user how to proceed. Do not guess.

Summarize the changes for the user in plain language before drafting.

## Phase 2 — Draft release notes (iterate until explicit approval)

Write bilingual notes (Chinese first, `---`, then English) from the change list. Apply these rules strictly — they are derived from every historical release:

1. **Only user-perceivable changes.** New features, visible improvements, refactors with user-facing impact. Never list chores, CI fixes, string cleanups, dependency bumps, internal renames.
2. **Bug fixes are always vague.** Collapse all fixes into one line: "修复了一些问题" / "Fixed several issues". Optionally append one phrase naming the improved area (e.g. "优化了 Phone Use 执行效率"). Never enumerate specific bugs.
3. **4–8 numbered entries.** Feature names are brand terms (Phone Use, Skills System) — keep them consistent with past usage.
4. **Breaking changes get a blockquote before the list**, in both languages, telling users what to do (e.g. the 1.1.0 package-name rename note told users to set up the new version as a fresh start).
5. **Milestone items** (e.g. "仓库已开源，欢迎 Star、提 Issue、参与贡献") go as the final entry when applicable.

Present the draft. The user will revise it — redraft and repeat until the user explicitly approves. Approval is an explicit statement from the user, not silence.

Few-shot (real entries from past releases):

- Good feature entry: `1. 新增 Phone Use：支持自动点按、滑动、滚动、输入文字、打开 App`
- Good vague fix entry: `7. 修复了一些问题`
- Bad entry (do not write): `修复了 composer 光标在深色主题下的偏移` — too specific; fold into "修复了一些问题"
- Bad entry (do not write): `chore: remove 13 unreferenced UI strings` — not user-perceivable, drop entirely

## Phase 3 — Propose version number (iterate until explicit approval)

Semver `x.y.z`:
- **Pump X** — proud version: milestone, rebranding, headline feature, open-source moment.
- **Pump Y** — normal update: regular features.
- **Pump Z** — shamed version: fixing a severe/P0 bug.

Propose one number with a one-line justification referencing the change list. Also report every location found in Phase 1 step 3 that needs the version string updated. Iterate with the user until explicit approval of both the number and the file list.

## Phase 4 — Execute

Confirm once more before anything irreversible (exact version number, tag, target commit, and whether to publish to Xposed).

Save the approved release notes into a temporary file (e.g. `/tmp/zafiro-release-notes.md`).

### Automated Execution (Preferred)

Run the release execution script from repo root:

```bash
# Main repo only:
bash .agents/skills/release-new-version/scripts/release-exec.sh <new code> <new name> /tmp/zafiro-release-notes.md

# Main repo + Xposed repo:
bash .agents/skills/release-new-version/scripts/release-exec.sh <new code> <new name> /tmp/zafiro-release-notes.md --xposed
```

The script automates:
1. Re-counting code lines badge (`update-code-badge.sh`).
2. Updating `versionCode` and `versionName` in `app/build.gradle.kts`.
3. Committing `release: bump to <new name>` and pushing to `origin main`.
4. Creating tag `v<code>-<name>` and pushing to `origin`.
5. Waiting for GitHub Actions release build CI to succeed (`gh run watch`).
6. Updating the GitHub Release title to `Release - <name>` and replacing CI notes with approved bilingual notes.
7. If `--xposed` is given: downloading the GitHub Release APK, stamping channel `xposed` (`tools/apk_channel.py stamp`, which checks read-back and `apksigner verify`), generating Xposed notes (with open-source links), and creating the Xposed release with `--latest` on `Xposed-Modules-Repo/com.niki914.zafiro`.

### Manual Fallback Steps

If running manually without the script:

1. Update the code-line badge so it lands in the release commit:
   ```bash
   bash .agents/skills/release-new-version/scripts/update-code-badge.sh
   ```
2. Update `versionCode = <new code>`, `versionName = "<new name>"` in `app/build.gradle.kts`.
3. Update module structure in both READMEs (`README.md` and `README_CN.md`) and AGENTS.md if applicable.
4. Audit and complete multilingual string parity across all modules.
5. Commit: `release: bump to <new name>`, push to `origin main`.
6. Tag and push:
   ```bash
   git tag v<code>-<name>
   git push origin v<code>-<name>
   ```
7. Wait for CI:
   ```bash
   gh run watch <run-id> -R niki914/zafiro --exit-status
   ```
8. Replace CI notes:
   ```bash
   gh release edit v<code>-<name> -R niki914/zafiro --title "Release - <name>" --notes "<approved notes>"
   ```
9. (Optional) Xposed repo. Stamp `xposed` on the downloaded asset before uploading. `stamp` exits non-zero unless the channel reads back and `apksigner verify` passes:
   ```bash
   mkdir -p /tmp/zafiro-xposed
   gh release download v<code>-<name> -R niki914/zafiro -p "*.apk" --dir /tmp/zafiro-xposed/
   APK="$(echo /tmp/zafiro-xposed/*.apk)"
   python3 tools/apk_channel.py stamp "$APK" "$APK" xposed
   gh release create <code>-<name> "$APK" -R Xposed-Modules-Repo/com.niki914.zafiro --title "Release - <name>" --notes "<approved notes>" --latest
   ```

### Release notes format

Historical releases follow this format strictly (no deviations):

- **English first, Chinese after, separated by a single `---` line.** Never reverse the order.
- **No language headers** (no `**English**` / `**中文**` labels) — the notes start directly with the numbered list. Blank line separates the `---` from the lists.
- The `--title "Release - <name>"` is part of the format, not optional. Set the title via `--title`, never inside the release note body.

## Phase 5 — Xposed repo (optional, ask first)

Ask: "这次要发 Xposed 仓库吗？" If yes, pass `--xposed` to `release-exec.sh` or run step 9 manually. The file uploaded there is the `xposed` stamp, not the GitHub asset as-is.
If no, run without `--xposed` and state clearly "本次未发 Xposed 仓库". The GitHub Release asset still carries channel `github`.

## Known pitfalls

- **CI build failure: Aliyun Maven mirror 502.** Overseas GitHub Actions runners intermittently fail on the Aliyun mirror if it is ordered before `mavenCentral()` / `gradlePluginPortal()` in `settings.gradle.kts`. Standard sources go first, Aliyun after. If CI fails on dependency resolution, check the mirror order.
- **Channel stamp rejected.** `tools/apk_channel.py stamp` exits non-zero when `apksigner` is missing, the APK has no v2/v3 signing block, `apksigner verify` fails, the signer certificate changes, or the channel does not read back. `apksigner` is `build-tools/<version>/apksigner` under `ANDROID_HOME`, or the `APKSIGNER` path. CI has it after the release build. The Xposed stamp runs on the machine that runs this skill, so that machine needs it too. Do not upload a rejected file. Re-run the release CI; leave the keystore alone.
- **Tag already exists / CI failed and needs a re-trigger.** The same tag cannot be pushed twice:
  ```bash
  git push origin :v<code>-<name>   # delete remote tag
  git tag -d v<code>-<name>         # delete local tag
  git tag v<code>-<name>            # re-tag at latest commit
  git push origin v<code>-<name>
  ```

## Hard rules

- Never build locally. CI builds everything.
- Stamp a distribution channel only with `tools/apk_channel.py stamp`. CI stamps `github` on the GitHub Release asset. The Xposed publish path stamps `xposed`. Names match `[a-z0-9_]{1,32}`. The stamp does not use the keystore.
- Never modify `versionName` without explicit user approval.
- Never push a tag without showing the user the exact tag and target commit first.
- If any state is ambiguous (local ≠ remote, tag exists, CI red), stop and ask.
