---
name: release-new-version
description: Use when the user wants to release a new Zafiro version — drafting bilingual release notes, deciding the next version number, and running the automated release script to publish to GitHub (and optionally the Xposed repo).
---

# Release New Version

Release workflow for Zafiro.

Repos involved:

| Repo | Tag format | Release notes |
|------|-----------|---------------|
| `niki914/zafiro` (main, open source) | `v<code>-<name>` e.g. `v8-1.1.0` | Full bilingual notes |
| `Xposed-Modules-Repo/com.niki914.zafiro` (closed) | `<code>-<name>` e.g. `8-1.1.0` | Same feature entries, closing line points back to the main repo. **Optional — ask the user every time.** |

CI builds and signs one APK when a tag is pushed to the main repo, stamps channel `github` into its APK Signing Block, and uploads that file as the GitHub Release asset. The Xposed repo has no CI; its release is automated by the release script via `tools/apk_channel.py stamp`. Never build locally.

> [!IMPORTANT]
> **Zero Manual File Editing During Release**: The release script (`release-exec.sh`) automatically updates `versionCode` and `versionName` in `app/build.gradle.kts`, counts lines and updates badges in `README.md` and `README_CN.md`, commits, tags, and pushes. Do **NOT** manually edit `app/build.gradle.kts` or `README.md`.

## Phase 1 — Gather facts

Collect all of this before asking the user anything:

1. **Local version**: read `versionCode` / `versionName` from `app/build.gradle.kts`.
2. **Latest release baseline**: `gh release view -R niki914/zafiro` (returns the latest release). Parse its tag `v<code>-<name>`.
3. **Changes since last release**: commits and merged PRs from the last release tag to `origin/main`:
   ```bash
   git log v<code>-<name>..origin/main --oneline --no-merges
   gh pr list -R niki914/zafiro --state merged --limit 50 --json number,title --jq '.[] | "\(.number) \(.title)"'
   ```
4. If local `versionCode`/`versionName` do not match the latest release tag: **stop** and ask the user how to proceed. Do not guess.

Summarize the changes for the user in plain language before drafting.

## Phase 2 — Draft release notes (iterate until explicit approval)

Write bilingual notes (**English first, `---`, then Chinese**) from the change list. Apply these rules strictly — they are derived from every historical release:

1. **Only user-perceivable changes.** New features, visible improvements, refactors with user-facing impact. Never list chores, CI fixes, string cleanups, dependency bumps, internal renames.
2. **Bug fixes are always vague.** Collapse all fixes into one line: "Fixed several issues and improved overall stability" / "修复了一些问题，优化整体体验". Optionally append one phrase naming the improved area (e.g. "优化了虚拟屏幕执行效率"). Never enumerate specific bugs.
3. **4–8 numbered entries.** Feature names are brand terms (Phone Use, Skills System, Virtual Display) — keep them consistent with past usage.
4. **Breaking changes get a blockquote before the list**, in both languages, telling users what to do.
5. **Milestone items** (e.g. "仓库已开源，欢迎 Star、提 Issue、参与贡献") go as the final entry when applicable.
6. **Format rules**:
   - English list first, followed by a blank line, `---`, blank line, then Chinese list.
   - No language headers (no `**English**` or `**中文**` labels).
   - Lists start directly with `1. ...`.

Present the draft. The user will revise it — redraft and repeat until the user explicitly approves. Approval is an explicit statement from the user, not silence.

Few-shot (real entries from past releases):

- Good feature entry: `1. Add Virtual Display: support expandable floating window with touch injection`
- Good vague fix entry: `6. Fixed several issues and improved overall interaction experience`
- Bad entry (do not write): `fix: hold a still frame across the virtual display morph` — too specific; fold into "Fixed several issues"
- Bad entry (do not write): `chore: update dependencies` — not user-perceivable, drop entirely

## Phase 3 — Propose version number (iterate until explicit approval)

Semver `x.y.z`:
- **Pump X** — proud version: milestone, rebranding, headline feature, open-source moment.
- **Pump Y** — normal update: regular features.
- **Pump Z** — shamed version: fixing a severe/P0 bug.

Version code is strictly sequential integer (`currentCode + 1`).

Propose the new `versionCode` and `versionName` with a one-line justification referencing the change list. Iterate with the user until explicit approval of the version numbers.

## Phase 4 — Execute

Confirm once more before anything irreversible (exact version number, tag, target commit, and whether to publish to Xposed).

1. Ask whether to publish to Xposed repo:
   - "这次要发 Xposed 仓库吗？"
2. Save the approved bilingual release notes into a temporary file (e.g. `/tmp/zafiro-release-notes.md`).
3. Run the automated release execution script from repo root:

```bash
# Main repo only:
bash .agents/skills/release-new-version/scripts/release-exec.sh <new code> <new name> /tmp/zafiro-release-notes.md

# Main repo + Xposed repo:
bash .agents/skills/release-new-version/scripts/release-exec.sh <new code> <new name> /tmp/zafiro-release-notes.md --xposed
```

The script automates:
1. Re-counting code lines and updating the badge in `README.md` and `README_CN.md` (`update-code-badge.sh`).
2. Updating `versionCode` and `versionName` in `app/build.gradle.kts`.
3. Committing `release: bump to <new name>` and pushing to `origin main`.
4. Creating tag `v<code>-<name>` and pushing to `origin`.
5. Waiting for GitHub Actions release build CI to succeed (`gh run watch`).
6. Updating the GitHub Release title to `Release - <name>` and replacing CI notes with approved bilingual notes.
7. If `--xposed` is given: downloading the GitHub Release APK, stamping channel `xposed` (`tools/apk_channel.py stamp`), and creating the release on `Xposed-Modules-Repo/com.niki914.zafiro`.

## Known pitfalls

- **CI build failure: Aliyun Maven mirror 502.** Overseas GitHub Actions runners intermittently fail on the Aliyun mirror if it is ordered before `mavenCentral()` / `gradlePluginPortal()` in `settings.gradle.kts`. Standard sources go first, Aliyun after. If CI fails on dependency resolution, check the mirror order.
- **Channel stamp rejected.** `tools/apk_channel.py stamp` exits non-zero when `apksigner` is missing, the APK has no v2/v3 signing block, `apksigner verify` fails, the signer certificate changes, or the channel does not read back. `apksigner` is `build-tools/<version>/apksigner` under `ANDROID_HOME`, or the `APKSIGNER` path.
- **Tag already exists / CI failed and needs a re-trigger.** The same tag cannot be pushed twice:
  ```bash
  git push origin :v<code>-<name>   # delete remote tag
  git tag -d v<code>-<name>         # delete local tag
  git tag v<code>-<name>            # re-tag at latest commit
  git push origin v<code>-<name>
  ```

## Hard rules

- Never build locally. CI builds everything.
- Never edit `app/build.gradle.kts` or `README.md` manually for release — `release-exec.sh` handles all version and badge updates.
- Stamp a distribution channel only with `tools/apk_channel.py stamp`. CI stamps `github` on the GitHub Release asset. The Xposed publish path stamps `xposed`.
- Never modify `versionName` without explicit user approval.
- Never push a tag without showing the user the exact tag and target commit first.
- If any state is ambiguous (local ≠ remote, tag exists, CI red), stop and ask.
