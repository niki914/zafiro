# OKIA 实现进度（Progress）

用途：会话恢复锚点。工作流 = 每完成一个功能点提交 git → 对话回退到任意点位 → 新会话从本文件恢复。本文件随每次提交更新，回退到任意提交时，该提交内的本文件独立支撑恢复。

## 当前状态

| 项 | 值 |
|---|---|
| 阶段 | T1 已完成（待提交），T2 未开始 |
| 契约 | 已冻结（docs/okia.md §8.10 第七轮 CR，2026-08-14） |
| 最近提交 | bf4008f（骨架冻结合并） |
| 阻塞项 | 无 |

## 恢复步骤

1. 读本文件
2. 读 docs/okia.md（契约事实源；与源码冲突时以源码为准并回写）
3. 读当前任务涉及的源码（libs/okia/src/main/java/com/niki914/okia/）
4. 从「下一步」开始执行

## 实现计划

| 任务 | 内容 | 主代码 | 测试 | 状态 |
|---|---|---|---|---|
| T1 | 对话树：RealConversation + SessionCodec | ~150 | ~300 | 已完成 |
| T2 | 垂直切片：RealOkia + 最小 AgentLoop + fake 协议/传输 | ~450 | ~450 | 未开始 |
| T3 | transport：SseLine 流式解析 | ~150 | ~200 | 未开始 |
| T4 | protocol：ProtocolCompatMapper.from + M0 DeepSeek 映射 | ~350 | ~300 | 未开始 |
| T5 | hooks 接线：holder write 语义 + loop 内时机 | ~250 | ~250 | 未开始 |
| T6 | tooling：ToolExecutor/ToolRegistry + 工具循环 | ~300 | ~300 | 未开始 |
| T7 | 取消/重试/idle：kill-then-stop + RetryPolicy + idle 超时 | ~300 | ~300 | 未开始 |
| T8 | MCP + M0 默认协议 + 默认 HttpEngine + 持久化入口 | ~400 | ~300 | 未开始 |

顺序：T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8。T2 是暴露骨架契约风险的主要手段（LoopRequest / onCommit / 事件序列 / 并发契约）。每任务完成时更新本表状态列，随提交提交本文件。

## 工作纪律

1. 任务粒度：每任务 ≤1000 行（含测试）。超预算拆分为「一个测试文件 + 其覆盖的实现」。
2. 测试方法论：
   - 测试断言公开面可观察的行为（docs/okia.md 承诺的能力），不依赖实现内部结构
   - 不变量测试：随机 append/rewind 序列后断言不变量恒成立（leafId 指向真实条目、投影 leaf→root、快照构造即复制）
   - 对照模型：测试内维护朴素预期模型，同一操作序列逐步对比
   - 并发测试：并发 append 无丢失、顺序保持、条目 id 唯一
   - 往返测试：export → encode → decode → restore → re-export 深度相等（含 leafId / timestamp / rewind 位置）
   - 失败路径：非法输入触发规定异常或快速失败
   - 切片测试用 hand-written fake（独立 canned 行为），不用 mock 框架
   - 时间相关测试用 kotlinx-coroutines-test 虚拟时间
   - 禁止：扫描源码字符串的检测；「不崩溃」类同义反复断言
3. 契约改动纪律：任务内若测试逼出契约改动，停下与用户讨论并回写 docs/okia.md，不在任务中途静默改签名。
4. 提交纪律：仅当用户要求时提交；提交前更新本文件（当前状态 + 决策记录 + 计划状态列）；commit message 用 conventional commits 风格（参照仓库历史）。
5. KMP 红线：共享代码零 Android 引用（不 import android.*）；依赖保持 KMP 兼容（coroutines / serialization）；HTTP 只经 HttpEngine 接口。

## 决策记录

| # | 决策 | 原因 |
|---|---|---|
| D1 | 不拆接口/实现两个 Gradle 模块，保持单模块 libs:okia | 公开签名泄漏 StateFlow/Json（api 依赖），纯接口模块不可达；internal 可见性已隔离实现；KMP 迁移是单模块 source-set 工作，拆模块使接线翻倍 |
| D2 | 实现从 T1 对话树开始，T2 垂直切片紧随 | 门面 send() 依赖对话树；垂直切片先行验证集成契约 |
| D3 | 实现期不并行开发 | 用户决定（2026-08-16） |
| D4 | RealConversation 内部状态 = 不可变 State 快照 + @Volatile 引用 | suspend Mutex 与同步 getter 共存：写入在 mutex 内构建新快照，读取免锁（不可变读安全）；KMP 兼容（kotlin.concurrent.Volatile） |
| D5 | 条目 id = kotlin.uuid.Uuid.random()；timestamp = kotlin.time.Clock.System | KMP 兼容；自增计数器在 restore 乱序 id 时可能冲突，已排除 |
| D6 | 构造时校验重复 id / 悬挂 leafId（fail-fast） | 与 §8.7 #4 rewind 存在性校验同一原则：客观可校验、快速失败 |
| D7 | SessionCodec 默认实现 = JsonSessionCodec（kotlinx.serialization 默认 JSON） | §5.13 JsonCodec 删除后 dataclass 直接 @Serializable；非法输入抛异常 |
| D8 | 对照模型（oracle）必须共享被测对象产出的 id | 模型独立重算投影，但不独立造身份（占位 id 无法映射到真实树，随机序列测试曾因此失败） |

## 下一步

开始 T2：垂直切片——RealOkia 门面 + 最小 AgentLoop + fake 协议/传输，用注入的 fake 跑通 send() → TurnResult 全链路（对照 docs/okia.md §5.1 / §5.2 / §5.4 / §5.15）。这是暴露骨架契约风险的主要手段。
