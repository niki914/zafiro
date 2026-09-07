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
| `Xposed-Modules-Repo/com.niki914.nexus.agentic` (closed) | `<code>-<name>` e.g. `8-1.1.0` | Same feature entries, closing line points back to the main repo. **Optional — ask the user every time.** |

One APK, two notes. CI builds and signs the APK and creates the Release when a tag is pushed to the main repo. There is no CI in the Xposed repo — its release is a manual `gh` operation. Never build locally.

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

## Phase 4 — Execute (main repo)

Confirm once more before anything irreversible. Then:

1. Update the code-line badge so it lands in the release commit:
   ```bash
   bash scripts/update-code-badge.sh   # relative to the skill directory; the script cd's to the repo root itself
   ```
   It recounts production Kotlin + Python lines (excluding tests / build / generated) and rewrites the `kotlin-XX.Xk` badge in both READMEs. Include the README changes in the release commit.
2. Update `versionCode = <new code>`, `versionName = "<new name>"` in `app/build.gradle.kts`, plus any other files from the approved list. Do not touch any other build config.
3. Commit: `release: bump to <new name>`, push to `origin main`.
4. Tag and push:
   ```bash
   git tag v<code>-<name>
   git push origin v<code>-<name>
   ```
   Tag push triggers CI: build → sign → create Release. CI reads signing config from GitHub Secrets; do not expect signing to work anywhere else.
5. Wait for CI:
   ```bash
   gh run list --workflow=release.yml --limit=1    # find the latest run
   gh run watch <run-id> --exit-status             # wait for it to finish
   gh run view <run-id> --log-failed               # on failure, read only the failed steps
   gh run rerun <run-id>                           # rerun after fixing
   ```
6. Replace CI-generated release notes (they are a PR list) with the approved draft:
   ```bash
   gh release edit <tag> -R niki914/zafiro --title "Release - <name>" --notes "<approved notes>"
   ```
   CI generates the release title as the raw tag name (e.g. `v9-1.2.0`); the historical format is `Release - <name>` — pass `--title` explicitly or the wrong title stays.

### Release notes format

Historical releases follow this format strictly (no deviations):

- **English first, Chinese after, separated by a single `---` line.** Never reverse the order.
- **No language headers** (no `**English**` / `**中文**` labels) — the notes start directly with the numbered list. Blank line separates the `---` from the lists.
- The `--title "Release - <name>"` from step 6 is part of the format, not optional.

## Phase 5 — Xposed repo (optional, ask first)

Ask: "这次要发 Xposed 仓库吗？" If no, state clearly "本次未发 Xposed 仓库" and stop. If yes:

```bash
# download the APK from the main repo release
gh release download <main-tag> -R niki914/zafiro -p "*.apk" --dir /tmp/

# create the Xposed release
gh release create <code>-<name> /tmp/<apk> \
  -R Xposed-Modules-Repo/com.niki914.nexus.agentic \
  --title "Release - <name>" \
  --notes "<approved notes>"
```

`gh` operates on the current repo's remote by default — external repos always need `-R owner/repo`, and the token must have write access there.

The Xposed notes use the same feature entries, but the closing line is `项目已开源：https://github.com/niki914/zafiro` (and its English counterpart) instead of a contribution invitation.

## Known pitfalls

- **CI build failure: Aliyun Maven mirror 502.** Overseas GitHub Actions runners intermittently fail on the Aliyun mirror if it is ordered before `mavenCentral()` / `gradlePluginPortal()` in `settings.gradle.kts`. Standard sources go first, Aliyun after. If CI fails on dependency resolution, check the mirror order.
- **Tag already exists / CI failed and needs a re-trigger.** The same tag cannot be pushed twice:
  ```bash
  git push origin :v<code>-<name>   # delete remote tag
  git tag -d v<code>-<name>         # delete local tag
  git tag v<code>-<name>            # re-tag at latest commit
  git push origin v<code>-<name>
  ```

## Hard rules

- Never build locally. CI builds everything.
- Never modify `versionName` without explicit user approval.
- Never push a tag without showing the user the exact tag and target commit first.
- If any state is ambiguous (local ≠ remote, tag exists, CI red), stop and ask.
