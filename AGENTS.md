# CLAUDE.md — Zafiro Autonomous (this fork's permanent instructions)

Also read `AGENTS.md`: it contains upstream's engineering rules (written in Chinese; MVI, ui-kit components, `ServiceRegistry`,
`.githooks` smell checks, test-triage). They still apply to any upstream-style code we touch.

> This file is the project's persistent memory. **Update it whenever something new is learned, decided, or proven to work**
> (owner's standing instruction). Tag facts: VERIFIED / SECONDARY / PROPOSED / UNVERIFIED.

## What this is
Zafiro Autonomous = fork of `niki914/zafiro` (MIT, Kotlin Android agent) → an unattended "digital worker": scheduled and
event-triggered tasks, goal-driven execution with budgets and recovery, MCP/Skills ecosystem, multi-provider models
(NVIDIA NIM etc.), memory, voice input, optional remote control.
Fork: `TricretA/my-zafiro` (origin). Upstream: `niki914/zafiro` (remote `upstream`, push disabled).

## Owner & devices
- Owner TricretA, Nairobi (`Africa/Nairobi`, UTC+3, no DST). **No local emulator — all builds/tests run in GitHub Actions.**
- Main phone: **Tecno BF7 / Pop 7 Pro, Android 12 (API 31), 4 GB RAM**. Also Tecno Pop 20, various Samsungs; must work on "every phone".
  Tecno (HiOS) and Samsung are aggressive background killers → reliability work is a first-class feature (see SECURITY/ARCHITECTURE §Reliability).
- Android 12 note: `SCHEDULE_EXACT_ALARM` is pre-granted for apps on API 31–32 (denied-by-default applies from API 33+), but the user can still revoke it.
- Use-cases the owner wants: apply/do jobs autonomously, run social media like a human, create videos/images/posts, use any app on the phone, connect MCPs/Skills.

## Owner priorities
Speed to revenue · operational simplicity · automation leverage · strategic defensibility · cash-flow survival.
Truth over tone; say plainly when something is weak. A feature is done only when implemented, tested and demonstrated.

## Decisions made (owner, 2026-11-10)
- **No** DroidBridge OpenAI tunnel. **No** OpenCyvis code needed (still free to use Apache-2.0 ideas).
- **Telemetry disabled** (done: blank URL + default off).
- **App identity**: `applicationId = com.niki914.zafiro.auto` (debug: `.auto.debug`), label "Zafiro Autonomous"; installs beside upstream. Must keep the `com.niki914.zafiro.` prefix (store module's `XRes` matches on it).
- **English only** UI/docs (done via `tools/autonomous/english_only.py`; re-run after upstream merges). Source comments upstream are Chinese; we do not mass-translate them.
- Wants **voice-to-text** input and the capabilities of **"Ruto-GLM"** (not found by name; owner to provide link — until then implement AutoGLM-style visual agent ideas).
- NVIDIA key will be added by the owner.

## Objectives (order)
1. Autonomous scheduler (primary). 2. Goal-driven execution with limits. 3. Reliability on Tecno/Samsung. 4. Voice input.
5. Tool ecosystem (MCP OAuth, skills). 6. Providers/NIM/fallback. 7. Memory & learned workflows. 8. Remote (Telegram), multi-agent, virtual display.
Do not polish UI before the engine works.

## Architecture (one paragraph)
Upstream modules (`app`, `agent-runtime`, `business/*`, `libs/okia`, …) per ARCHITECTURE.md §1. We add `autonomy/core`
(pure Kotlin/JVM: tasks, recurrence, state machine, budgets — testable without Android) and `autonomy/android` (Room
`autonomy.db`, AlarmManager projection, receivers, `ScheduledRunService`, `RunExecutor`, approvers). Scheduled runs call
`AgentManager.createTaskAgent()` — never a second agent loop. DB is truth; **one** exact alarm is a projection; WorkManager
only for constraints. ADRs 0001–0008.

## Hard rules
- Additive changes; upstream-file edits only via the touch list in UPSTREAM_SYNC.md.
- Never push to `upstream`; never force-push `main`; work on `feat/*` and PR.
- No LGPL (Operit) or personal-use (Blurr) code. Apache-2.0 only with NOTICE.
- Secrets never in logs/prompts/commits; keys encrypted at rest (ADR-0006, not yet done). Unattended approvals default to **Deny**.
- Never bypass CAPTCHA, account security, consent screens, FLAG_SECURE or the secure keyguard → `NEEDS_HUMAN`.
- No fake functionality; no "tests passed" without a CI result; failing tests are reported, never hidden.

## Build / test
CI is the build loop (sandbox cannot get the Android SDK). `.github/workflows/ci.yml` runs on push to main/feat/fix/sync:
assembleDebug → testDebugUnitTest → lintDebug, uploads the debug APK artifact and **publishes a results bundle to branch
`ci-logs`** (`runs/<branch>/summary.md`, `*.tail.log`, `failures.txt`) because the agent's proxy blocks the Actions API.
Read it with: `git fetch origin ci-logs && git show origin/ci-logs:runs/<branch>/summary.md`.
Pure-Kotlin core can be tested in the sandbox: `cd autonomy/core && gradle test` (see its README).
Toolchain: JDK 17, Python 3.11 (Chaquopy), AGP 9.1.1, Gradle 9.3.1, Kotlin 2.2.0, compileSdk 37, minSdk 26, targetSdk 34.
Owner prerequisite: Actions must be **enabled** on the fork (Actions tab → "I understand my workflows, go ahead and enable them").

## Known facts
- Upstream has zero scheduling code; approvers attach only to the main agent; no step/time/cost limits; API keys plaintext; telemetry opt-out to upstream author's server (now disabled).
- Exact alarms are exempt from background-FGS-start limits; `specialUse` FGS has no timeout; alarms vanish on reboot/force-stop; revocation of SCHEDULE_EXACT_ALARM cancels them. (Android docs, read 2026-11-10.)
- Device Admin does NOT make an app unkillable or higher priority (see ARCHITECTURE §Reliability). Real levers: foreground service, battery-optimisation exemption, OEM autostart/whitelist, Shizuku/root-granted app-ops/standby bucket, watchdog + missed-run recording.
- DroidBridge scheduling design is the model (Rust code not reusable); Operit = WorkManager-only + LGPL; Blurr = personal-use license.

## Lessons learned (append as we go)
- Upstream package prefix is `com.niki914.zafiro.*` (api, service, business…). Ours is `com.tricreta.zafiro.autonomy.*`. Mixing them up produced "Unresolved reference 'api'" (our prefix resolves, the sub-package doesn't) — read the compiler message before suspecting the build.
- Upstream has an architecture guard test (`PermissionEntryGuardTest`): permissions are requested ONLY through `PermissionManager` (never `checkSelfPermission(`, `requestPermissions(`, or `POST_NOTIFICATIONS` outside manifests). We added `Permission.MICROPHONE` there (Shizuku/root can grant it silently).
- Pristine upstream fails 6 unit tests on Linux CI (listed in `tools/autonomous/known_test_failures.txt`); CI fails only on NEW failures or if fewer than MIN_TESTS ran. Lint has 3 pre-existing errors in `app`; lint is reported, not gating.
- Pushing to a branch cancels its in-flight CI (concurrency). Don't push while waiting for a run you need.
- JVM method names in tests can't contain `:` (e.g. "08:00") — use `0800`.
- Maven Central via the sandbox proxy sometimes returns 429: just retry `gradle test` in `autonomy/core`.

- Debug builds also contain LeakCanary, which adds its own launcher alias ("Leaks"); it is disabled via `app/src/debug/res/values/leakcanary.xml`. Verify launcher count by reading the built APK, not by assuming.
- Android's `SpeechRecognizer` is single-utterance. Continuous dictation = restart loop (`voice/.../dictation/DictationSession`). Never treat its first result as "done".
- Never parse user-typed times: use system pickers. Tasks store a blank zone = follow the device zone (`ZoneResolver`).
- UI for features belongs in the app module using ui-kit settings components (template: Text actions / Python tools pages); `autonomy` has no UI.
- `AgentSessionEngineOkiaTest.ensureSession_createsInstanceAndReturnsTreeId` is a flaky upstream test (listed as `flaky:` in known_test_failures.txt).
- I can inspect built APKs in the sandbox (`pyaxmlparser` in a venv) to verify manifest facts, but cannot run them.

- Dictation bug root cause (2nd round): UI state tied to composable lifetime. The compact<->expanded composer switch disposed the mic button and with it the recognizer. Long-lived work (recognizer, timers) must be owned above the layout that shows it.
- `TurnCompleted` in Okia fires once per user turn (last message only). Per-request usage lives on each committed assistant message: meter by watching committed history, de-duplicated by entry id.
- Okia keeps the user message committed when a request fails. A clean retry on another model = restore a pre-turn `export()` snapshot via `refresh(restore=...)` (works for empty conversations; `rewind` cannot).
- Fallback is only safe before any output/tool call (`FallbackPolicy`); recovery after output continues the SAME conversation instead (never re-run finished work).
- Room: bump version + write a `Migration` + test it by opening a hand-built old-version file (see `MigrationTest`); a bad migration crashes every schedule.
- Backups contain API keys in plain text (ADR-0006 deferred by owner). Restore is staged and applied in `App.onCreate` before any DB/settings file opens.
- Owner rule: research + LIST for approval before adding abilities/skills/MCPs/actions (`docs/APPROVAL_LIST.md`).
- Pure Kotlin logic (even from `app`) can be verified in the sandbox by copying it into a scratch copy of `autonomy/core` (junit available); used for BackupArchive.

## Status
Evidence-based status: ROADMAP.md and TESTING.md. As of 2026-11-10: `feat/foundation` builds and passes the CI gate (APK artifact `zafiro-autonomous-debug-apk` on the run); **nothing has been verified on a physical phone yet**; `main` is still pristine upstream; no PR opened.
