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

- Release APK: `.github/workflows/release.yml` (signed + minified, normal GitHub release). Needs repo secrets RELEASE_KEYSTORE_B64, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS=zafiro, added by the OWNER (the agent may not write secrets). Key was generated 2026-11-10 and handed to the owner; losing it means no in-place updates. Debug builds (rolling pre-release) are signed with a different, public key, so switching between them needs an uninstall.
- Upstream sync: `.github/workflows/upstream-sync.yml` weekly; issue or `sync/upstream-<date>` branch. Re-run english_only.py after merging.
- Tools added: wait, device_info, clipboard, http_request, take_over, compose_message (draft only). Not built: long-press/double-tap, schedule_task, contacts/location/notification reading, real SMS/email send, MCP OAuth.

- Releases: bump `versionName`/`versionCode` in app/build.gradle.kts BEFORE pushing a `release/**` branch; the in-app updater compares versionName with the latest release tag (semver). Update check reads TricretA/my-zafiro. Agent cannot push tags (proxy); branch push triggers release.yml.
- Skills: ~175 bundled in app/src/main/assets/skills; ids listed in skills_disabled_by_default.txt start OFF. Source: plugin libraries under ~/.claude/plugins/synced (check each plugin's LICENSE first).
- Agents (v1.12.0): profiles in autonomy.db, `AgentDelegator` service (business:api) implemented by `DelegateRunner`, `agent` builtin tool, learning = reflection turn + RunHistory in prompts. Tool/skill lists are prompt guidance only. Sidebar = History page menu (no sliding drawer). Device admin = lock_screen only.
- NOT built: hard per-agent tool sandbox, true drawer, MCP OAuth, long-press/double-tap, schedule_task, contacts/location/notification reading.

## Status
Evidence-based status: ROADMAP.md and TESTING.md. As of 2026-11-10: `feat/foundation` builds and passes the CI gate (APK artifact `zafiro-autonomous-debug-apk` on the run); **nothing has been verified on a physical phone yet**; `main` is still pristine upstream; no PR opened.

## Findings from owner's real backup (2026-11-15, app 1.12.0) — VERIFIED
- The owner's Zafiro already runs a Python-engine team built BY the agent: 9 agent_profiles, 15 tasks (Substack-focused, 80-100 step budgets), upstream `settings/agents/*` registry (incl. jh_* job-hunt agents) is a SECOND agent system beside our `agent_profile` table. Workspace dirs (files/zafiro-autonomous, substack, jobhunt, turing, journal.md, rish) were NOT in the backup: BackupManager only includes settings/, skills/, local_settings.json → BUG, a restore would lose the engine. Fix: back up the whole filesDir minus caches.
- Real failures seen: `Transport` provider errors (no fallback configured), `Busy` start, MISSED/LATE (phone asleep), INTERRUPTED (process killed), PIN-locked screen during unattended runs, Shizuku down. "SUCCEEDED" runs were date-guard no-ops → success must be judged by outcome evidence, not DONE.
- Plan in progress: "Workspaces" (per-platform bundles: agents+skills+tasks+memory+MCP+routines), Files viewer, Dashboard, verified screen routines. Plan awaiting owner approval; do not build before.

---

# Upstream engineering rules (from niki914/zafiro AGENTS.md, Chinese)

# About

## 项目简介 - 详见 README.md

---

## 项目知识

### 仓库本体

```
./
├── .agents/skills/      # SKILLs 真实目录
├── .claude/skills/      # .agents/skills 的软链，不用修改这里的文件
├── .githooks/           # 提交前坏味道检查；规则、放行、清理方式见其 README.md
├── app/                 # 主应用：Compose UI、AgentRuntimeService、Xposed 钩子
├── business/            # 业务核心层（契约与实现分离）
│   ├── api/             # 业务对外公共契约接口
│   ├── agent/           # Agent 核心逻辑、会话编排与状态流
│   ├── files/           # 文件附件与路径解析
│   ├── notification/    # 常驻通知、前台服务与通知渠道管理
│   ├── permission/      # 权限管理中心（Root / Shizuku 自动授权、统一审批）
│   └── application/     # 应用管理与生命周期
├── agent-runtime/       # Agent 运行时：LLM 调用、工具/Skill/MCP 执行、Python 运行时
├── xsettings/           # 轻量配置模块（业务按需依赖）
├── store/               # Store 持久化、IPC 桥（XIpcBridge）
├── ui-kit/              # 共享 Compose 组件（Zafiro* 元器件）、ZafiroScreen 壳、导航
├── remote-view/         # 跨进程 / 悬浮窗 RemoteView 视图
├── xposed-api/          # Xposed 事件类型、共享常量（主应用与宿主进程共享）
├── xposed-runtime/      # Xposed 运行时、Hook 基类
└── libs/
    ├── logging/         # 日志库
    ├── okia/            # Okia Agent 运行时基础库
    └── libterm/         # 终端库（含多个后端：libsu, shizuku, ssh 等）
```

### MVI 架构

项目强制使用 MVI 心智：UI 只发意图、只读 VM 的 State，VM 通过更新 State 驱动 UI。基类见 ui-kit 的 ComposeMVIViewModel

State 与 Effect 的区别是持久与一次性，分界的判据是「换个时间再读还成立吗」：列表数据、弹窗开关属于 State；Toast、聚焦、退出页面属于 Effect，只发生一次、没接住就没了

弹窗最容易误判：触发弹窗是一次动作，但「弹窗开着」是界面当前的样子，重组、旋转、被压栈后回来都要能读到同一个答案，所以它是 State

### 宿主 / Host

本项目通过 LSPosed 对 com.heytap.speechassist 等应用做 Hook，将系统语音助手的回答换成 Zafiro Agent 回答，具体实现是通过 Binder + AgentRuntimeService。当前的实现重点是，复杂的数据结构从不穿过 Binder，IPC 传递纯语义数据（Thinking、Tools、Content）。宿主架构采用分层治理：上层 `*Hook`（如 `BreenoHook`）为纯胶水调度层，严禁堆砌反射与类名硬编码

我们把取代原生语音助手的功能称为 换源 / takeover / 接管。宿主是比较边缘的业务，在重要决策时，不应该为了宿主的业务而去妥协，应该牺牲宿主

### 主界面 / Home / Compose

这些都是指应用的主入口，Compose UI，具体的对话是在 HomeChatViewModel 内通过 Agent API 维护。Compose 必须是无状态的，从配套的 MVI ViewModel 里取数据

这是整个应用的根基，它的重要性和优先级最高

### UI 框架 / ui-kit

导航、导航栏、弹窗、设置项都用 ui-kit 里既定的那套，不要另起一套。实现涉及导航、ViewModel、弹窗的业务前 MUST 调研已有代码作为参考

#### 风格

业务组件统一使用 ui-kit 导出的 Zafiro* 基础元器件（ZafiroDialog 等），底层实现了多种主题分流。现存的 UI 风格有：LiquidGlass、Material3、Material3Expressive。其中 LiquidGlass 是一种设计语言，和 Material 是一样的，它使用 `kyant0:backdrop` UI 库实现，因此任何 UI 必须使用了 backdrop API 才能命名为 Liquid 或 Backdrop

#### 导航

导航是自行实现的（ui-kit 的 nav 包），而不是使用 nav3。每个 entry 自带 viewmodel 生命周期，页面 VM 要通过框架拿，不要自己去取

push 分正推与从左推（onPushFromLeft）两个方向，返回要配对使用；返回按钮的位置也跟着方向走，正向推入在左上角，从左推入在右上角

#### 导航栏与左右上角按钮

导航栏指 ZafiroTopBar，有两个图标槽，内容由页面通过 PageChromeContribution 覆盖，页面不自己画

背景色与标题由滚动状态自行感知切换，大部分页面不需要参与；左右按钮通常指这两个图标槽

#### 弹窗类

约定使用 ZafiroDialog（ZafiroConfirmationDialog、ZafiroSingleChoiceDialog、ZafiroInputDialog 等），它必须挂在 ZafiroScreen 的内容树里，返回键由弹窗自己接管

BottomSheet 用 OptionSheet

#### 设置项

设置列表页统一基于 SettingsPageSpec + SettingsSpecPageContent，编辑详情页统一使用 EditableSettingsDetailChrome + FormSpec 骨架；底层通用卡片与行使用 ui-kit 的 Settings* 系列，如无明确要求，禁止手搓

设置项读写需要经过 SettingsJsonCodecUtils 校验

### 对话列表

AI 对话通常是以 list 的形式存放 messages，同时只会有一个对话在进行，数据结构：Conversation - List<Message>。应用通过 Room 数据库，将每次 AI 对话的内容持久化。对话列表指的是对话的列表，数据结构：ConversationList - List<Conversation>

这是一个不应混淆的概念，通常在提及对话列表的时候，就是在指后者，而不是 HomeChat 对话界面

### AI 对话数据结构

- Conversation - 单次对话中的消息的集合
- Turn - Agent 通常会与 Tool 交互多个回合才结束。因此，我们将一条用户消息与其产生的若干 Agent 回答、Tool 调用、Tool 结果的集合称为一个 Turn。便于管理

### 持久化

项目有着多种持久化方案：

- 维护对话列表：使用 Room 数据库，
- 其他：通过在沙箱内直接读写文件实现，主要通过多 Json 单元（见 XRepo API）

### IPC

目前来说，Zafiro Agent 的对话功能是限定在主进程之内的，虽然有一个宿主的业务，Agent 相关的调用逻辑依然用 Binder 包裹在主进程之内

项目通过 Chaquopy 实现 Python 能力支持，在单独的 py 进程中运行代码

### 日志与调试

如果身处 lima Linux 环境, 请调用 `host-command-bridge` 来与主机 macOS 环境交互。调试构建额外把同一条日志落到沙箱，如果 logcat 被冲掉了可以用 adb 把日志拉出来看。详情通过调用 `zafiro-qa` 技能来了解

---

## HARD GATE | MUST FOLLOW

### 共识

- 若某项功能实现难度高（难度评分超过 6/10）且 ROI 较低，在着手开发前应先与用户协商功能范围
- 提交信息和 PR 标题均使用英文，采用 `feat: did something` 这样的格式；标题简洁明了，不带模块名，补充说明写在正文中。PR 使用 gh CLI 完成
- 搜索用 `rg`，不要 `grep -r`。 `rg` 默认读 `.gitignore`，自动排除 `**/build/`；`grep -r` 会扫到 build 与 `.git`

### 边界

- 没有明确要求提交时，不提交。等待用户验收
- 未经允许禁止对做安卓做安装应用等写操作
- 用户提问（尤其是探讨、假设性问题或一般疑问句）时必须直接作答，严禁产生例如代码变更等副作用。典型反面例子：用户问“这个地方如果我做 XX 操作会怎样？”或询问某一情况，AI 却以发现了漏洞或其他原因擅自开始修改
- 理解用户指令必须紧密结合上下文，严禁盲目粗暴操作。典型反面例子：用户要求“确保工作区的改动干净”，AI 却不过脑子直接全盘回退导致有效工作全部丢失

### 架构决策

- 架构决策应着眼于长远。不要接受那种仅能暂时应付、日后还需替换的 Workaround
- 拒绝处理人工操作无法复现（诸如在几十毫秒内迅速点击的、高手速要求的操作）的并发/竞争类 issue，这类问题 ROI 极低，且导致过度防御和复杂化
- 不必维持向后兼容性。移除过时的路径，而不是添加兼容层、回退机制或迁移逻辑
- 采用能完全满足当前需求的、最简单的实现方案。避免过度抽象、过多的配置项以及不必要的间接层
- 采用分层方式构建系统。从能实现端到端功能的最小版本起步，在现有可用产品的基础上逐步增加新功能。切勿为了尚未完成的复杂设计而牺牲现有的可用产品
- 在开辟大型业务时，优先考虑用 ServiceRegistry 来做依赖注入，避免在构造函数、方法签名里面堆砌太多字段
- 若能降低整体复杂度或提高可靠性，应优先使用成熟且维护良好的现有库。除非有充分理由，否则不要重复实现通用功能
- 在自行编写实现或引入新包之前，应优先利用项目中已有的依赖项。在未查阅文档和类型定义之前，切勿主观臆断某个库不具备某项功能

### 实现代码

- 参考多个类似业务的实现，尤其是设置页表单
- 坏味道由 `.githooks/` 在提交前拦（监视器锁、变化叙述注释、内联全限定名、绕过 Logger 的日志、日志 TAG 前缀）；规则、放行标记、按模块清理方式见 `.githooks/README.md`。需要提交时，无须提前阅读 Hooks，正常提交即可，有问题会被 Hooks 扫出
- 不要使用后台任务进行编译或单测，使用同步方法
- 禁止出现 [改两行 ui 字符串 -> 编译 -> 再改] 的行为，应该在确实需要时（比如做了重型重构后）编译
- 开发过程中需要实现多语言。面向用户的文本放入 res，面向 Agent 的文本可硬编码

### 单测

- 写或修改任何单测之前，**必须先调用 `test-triage` skill**：先判断这个测试该不该存在（拦截力），通过了再写。单测规则以该 skill 为准

### requireService<>()

- 此方法正是为了做依赖控制，应直接在函数体或成员变量处调用，必须避免在构造函数或者方法签名里面使用
- 典型的场景是传递 Context 或 Application，这类需求完全可以通过 Service 来实现
- 当新/旧业务适合通过一个简洁调用面提供出来并被广泛使用时，应考虑封装为一个 Service

---

## Preferred Skills

### 认知对齐

不仅在与用户对话时会有误差，与 subagent 打交道时同样会出现误差，误差的后果是，后者做出来的东西并不符合前者的预期，因此描述任务的人必须尽可能确保他们的任务一清二楚

- 在一个需求开始时，如果用户没有带着详细的计划，优先通过 `grill-me` 或 `grill-with-docs` 来快速与用户对齐认知
- 在需要派发 subagent 的任务中，通过 `prompt-engineering` 或 `writing-for-agents` 来与它们对齐

### 讲解（推荐）

- 需要向用户阐述复杂内容时（如需要使用时序图、树状图、流程图等），可以通过 `answer-me-with-html` 帮助解答。

### 其他

- 单元测试: 写或修改单测前调用 `test-triage`；测试执行超过 30s 或疑似挂起时调用 `test-hang-watchdog`
- 发版: 准备发布新版本时使用 `release-new-version`

## v1.13.0 facts (2026-11-15) — built from owner's plan; UNVERIFIED on device
- Workspaces: assets/workspaces/*.json generated by `tools/autonomous/gen_workspaces.py` (re-run after editing; output is committed and parsed by CI tests). Agents/tasks are installed lazily on first switch-on (WorkspaceManager). Task ids `ws.<workspace>.<key>`; heartbeat key `heartbeat`.
- Upstream is NOT a git ancestor of our history (squashed fork): sync = `git diff <last-synced-upstream-commit> upstream/main | git apply --3way --exclude='app/src/main/res/values-*'`, then english_only.py + dedupe strings.xml. Last synced upstream: 57db66f2 (2026-11-15). Upstream replaced screen tools with V2 (`screen_operation`, `screenshot`, `open`, `screens`); we keep `open_uri`/`launch_app`.
- Routine labels are guesses: first run on the phone needs calibration (edit files/routines/*.json). Never claim they work before the owner confirms.
- MCP OAuth: token saved as Authorization header; refresh loop in App every 10 min while the process lives.
- Owner removed phone blockers (no PIN, app never killed, charged) and asked everything to run day and night.
