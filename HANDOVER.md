# Handover：feat/viewtree-latency-optimization 横评任务

> 写给接手本分支的 agent。你存在的唯一使命在「使命」一节；其余是完成使命所需的最小背景。

## 使命

日志基建（**新设计，非 xevent，设计未定**）落地后，横评 **有本 PR vs 无本 PR**（基线 `1d13338`）两种实现的 `waitForStable()` 表现——**耗时 + 正确率**。横评结论必须能支撑两个决策：

1. 噪声容忍阈值 `noiseToleranceMatches`：当前 6，候选 3（合并前定）
2. 本分支：合并 or 放弃

## 状态（2026-08-04）

- 分支 `feat/viewtree-latency-optimization`，提交 `48b44dd`，WIP PR [#106](https://github.com/niki914/agentic-nexus/pull/106)，**已推送未合入**
- 阻塞原因：无法量化收益，等待日志基建
- 完整背景见 `PRD.md`（本文件同目录，工作区未提交）；改动见 `git show 48b44dd`

## 机制速览（30 秒版）

`waitForStable()` 从"事件静默 300ms + 原始树 hash 相邻一致"改为**双通道退出**：

- **通道 A（事件静默）**：相邻两采样 fingerprint 一致 + 事件流静默 ≥300ms → 保留低事件 app（Settings 等）旧行为
- **通道 B（语义稳定）**：`UiStabilityTracker` 3 个相同 fingerprint 跨 ≥150ms → 容忍弱事件噪声
- **噪声容忍（方案 B，用户拍板）**：fingerprint 连续 6 个样本不变时，即使强事件持续（generation 递增）也放行 → 修复 Spotify 播放页"树不变但强事件刷"导致每步 timeout 2–5s 的问题
- **fingerprint**：`TreeFormatter` 一次裁剪同时产出 YAML + 64 位 FNV-1a 指纹（覆盖 type/text/desc/bounds/clickable/checked/moreSummary/截断，忽略 index/version/className）
- 稳定或超时都**直接返回已判样本**，不再二次抓树

关键文件：`AccessibilityController.kt`（waitForStable + StableSample + 双通道）、`TreeFormatter.kt`（computeSemanticFingerprint）、`UiStabilityTracker.kt`（状态机 + 事件强弱分类）。

## 已有实测数据（QA 2026-08-02/03，RMX3850/Spotify，勿重复测量）

| 场景 | 旧实现 | 本分支 |
|---|---|---|
| 普通点击（无持续事件） | 2000ms（打满 timeout） | ~950ms |
| 播放页（强事件刷、树不变） | 2–5s（每步 TIMEOUT） | ~1.1s（噪声容忍放行） |
| progressbar 更新**进入 YAML**（时间文本/bounds 变） | 2000ms | 2000ms（**未解决**，PRD §10.4 按设计如此） |

额外发现：**timeout note 反馈环**——模型在结果里看到 `# Note: settle timed out` 后会把 `wait_ms` 灌到 3–5s（QA 实测 elapsedMs=5021）。本分支让播放页退出时不带 note，这个通胀会自然回落。这是比表面延迟更重要的收益，横评要覆盖。

## 横评设计（按新基建 schema 落地时调整，事件契约先行）

**埋点契约（现在就可定义，与基建无关）**：每个 `waitForStable()` 退出时记录一条事件，字段：
`{elapsedMs, exitReason: EVENT_IDLE|SEMANTIC_STABLE|TIMEOUT, appPackage, settleTimeoutMs, sampleCount, operationType}`

PRD F-09 已要求此日志；本分支已留单行 `Log.d("NexusStable", ...)`（`logSettleResult`，合并前保留）。基建落地后把同一事件埋进**两个版本**。

**耗时指标**：按 app × 操作类型分组的 `elapsedMs` 分位数（p50/p90/max）+ TIMEOUT 占比。样本量：每场景 ≥30 次操作。

**正确率（无 ground truth，用代理指标）**：
- **TIMEOUT 率**：结果含 timeout note 的比例（越低越好）
- **旧树误返回**：操作后返回树不含预期效果（如点击后节点状态未变）。脚本抽查：连续 N 次同一操作，检查返回树关键节点
- **wait_ms 通胀**：模型传入的 `wait_ms` 分布是否回落（验证反馈环修复）

**无 PR 版本获取**：旧算法已删除，从基线 `git worktree add` 基于 `1d13338` 的干净工作区编译旧版 APK，与分支版分别真机跑同一操作序列（点击/播放暂停/滚动/文本输入），logcat 采集对比。注意基线无埋点，需手工在旧版加同一埋点事件。

**输出契约**：一张对比表（app × 指标 × 版本），结论给"阈值选 6 还是 3"+"合并 or 放弃"。

## 待定决策与已知坑

- **阈值 6 vs 3**：方案 B 文档（progress.md）写 8，代码落 6，均未确认。3 的理由：容忍窗口与 fingerprint 路径 3×150ms 是同一种保证，6×150ms 只是保守过头；播放页可从 ~1.1s 降到 ~500ms
- **progressbar 进 YAML 场景无解**：任何"YAML 变就不稳定"的算法只能等 deadline。唯一出路是重构 fingerprint 语义（忽略不可操作节点的 bounds/text 变化——语义规则，非 class 特判），那是新 PRD
- **PRD 未修订**：方案 B 偏离 PRD F-04/AC-04（强事件必须重置），progress.md 记录为"未定项"；合并前要么改 PRD 要么 PR 描述声明偏离
- **QA 诊断代码**（`computeDebugChannels`/`sampleClassSignatures`/`lastStrongEventType` 等，已标"Temporary diagnostic (QA)"）：合并前删除，保留 `logSettleResult` 单行
- **ProviderSpec.kt 混入** `deepseek-v4-flash` 改动：与本分支无关，合并前移出

## 负向约束

- 日志基建落地前**不要动 waitForStable 算法**（含阈值调整）——那是横评之后的事
- 不要造第二套临时观测系统；现状就是 `logSettleResult` 单行 + QA 诊断块
- 不要用 xevent（用户已决定弃用，新设计未定）
- 不要引入 app/class 特判（用户明确否决硬编码路径）
- `docs/.asc_task/` 是任务文档，不是实现证据
