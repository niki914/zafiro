# OKIA 实现进度（Progress）

用途：会话恢复锚点。工作流 = 每完成一个功能点提交 git → 对话回退到任意点位 → 新会话从本文件恢复。本文件随每次提交更新，回退到任意提交时，该提交内的本文件独立支撑恢复。

## 当前状态

| 项 | 值 |
|---|---|
| 阶段 | T6 已完成，T7 未开始 |
| 契约 | 已冻结 + T2-T6 回写（docs/okia.md §8.11-§8.15） |
| 最近提交 | 6d24e4f（T6 工具循环） |
| 测试 | 234 个全绿（T6 新增 35：工具循环 12 + 工具段 17 + registry 6） |
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
| T2 | 垂直切片：RealOkia + 最小 AgentLoop + fake 协议/传输 | ~490 | ~650 | 已完成 |
| T3 | transport：SseLineParser + SseEventParser + loop 前置校验 | ~220 | ~500 | 已完成 |
| T4 | protocol：DeepSeekChatCompletionProtocol（独立实现）+ Mapper.from 委托壳 | ~260 | ~40 用例 | 已完成 |
| T5 | hooks 接线：holder write 全部实现 + 链式分发 + Input/Serialization/Request 三对时机接入 | ~200 | ~350 | 已完成 |
| T6 | tooling：DefaultToolRegistry + 工具循环（批量并行，保序提交） | ~490 | ~650 | 已完成 |
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
| D31 | 工具执行模式：pi 批量并行（消息完整后执行 + 并发 + 保序提交），放弃 kai 流水线 | 延迟不敏感；loop 结构简单；已派发列表可推导（零收集） |
| D32 | outcome → 事件映射：Success→Succeeded；Failure→Failed；Intercepted 按 isError；Interrupted/Unknown→Failed | 事件均携带完整 outcome，映射只决定事件类型 |
| D33 | executor 违反「永不抛异常」契约 → 回合 Failed(ToolExecutionFailed)，不打包回喂 | 业务方 bug 应显形，模型无法修正代码 bug |
| D34 | 工具段 hook 异常 → 该工具 Failure outcome，回合继续；阻断（writeOutcome）跳过 afterToolCall | §8.4 #13；对齐 pi immediate result |
| D35 | 新增 DefaultToolRegistry（无锁，snapshot 复制），EmptyToolRegistry 保留 | host 无需自实现注册表；契约保证回合外写 |
| D36 | 已派发列表无收集：stop 时从会话树推导（批量模式下已派发 = 已提交 Assistant 中的 ToolCall） | 零新增 API；调用在 T7 |
| D4 | RealConversation 内部状态 = 不可变 State 快照 + @Volatile 引用 | suspend Mutex 与同步 getter 共存：写入在 mutex 内构建新快照，读取免锁（不可变读安全）；KMP 兼容（kotlin.concurrent.Volatile） |
| D5 | 条目 id = kotlin.uuid.Uuid.random()；timestamp = kotlin.time.Clock.System | KMP 兼容；自增计数器在 restore 乱序 id 时可能冲突，已排除 |
| D6 | 构造时校验重复 id / 悬挂 leafId（fail-fast） | 与 §8.7 #4 rewind 存在性校验同一原则：客观可校验、快速失败 |
| D7 | SessionCodec 默认实现 = JsonSessionCodec（kotlinx.serialization 默认 JSON） | §5.13 JsonCodec 删除后 dataclass 直接 @Serializable；非法输入抛异常 |
| D8 | 对照模型（oracle）必须共享被测对象产出的 id | 模型独立重算投影，但不独立造身份（占位 id 无法映射到真实树，随机序列测试曾因此失败） |
| D9 | 回合终态必须中断流收集：collect 内终态（Completed/Error）抛 StreamTerminated 哨兵异常 | 无限流（SharedFlow）不自然结束，return@collect 只退出 action、collect 继续挂起等下一事件，turn 永不完成（T2 实测暴露，fix 前 2 个测试超时 60s）；哨兵非 CancellationException，不被取消机制误判 |
| D10 | RealOkia turnScope 可注入（internal 构造参数） | 测试注入 TestDispatcher 获得可控时序；默认真实线程池，契约无感 |
| D11 | export 在活跃回合时抛 IllegalStateException | 契约 §8.7 #5 列表未含 export，但回合中树在提交中、导出的快照不一致；按 rewind/update 一致性补充 |
| D12 | 默认 HttpEngine 未实现（T8），config.httpEngine 为空时 send 抛 IllegalStateException | 契约说 null 时门面自建，T8 落地；T2 明确失败而非静默 |
| D13 | 外部取消（调用方协程取消）传播 CancellationException，不产生 Aborted(External) | 协程取消语义优先（rethrow）；StopCause.External 路径待真实消费者出现后定（T2 不删枚举值，契约不动） |
| D14 | Completed 事件 stopReason 为 Error/Aborted/ToolUse/Pending 时按失败处理 | 明确失败优于自动修复；T2 fake 不发此类事件 |
| D15 | 事件流 replay=0 + extraBufferCapacity=64；一次性事件语义 | 订阅晚的事件不补发；宿主 IPC 与 UI 各自消费 |
| D16 | StreamResponse sealed 化（Ok / Error 两态）；传输失败抛异常 | 三可空字段靠约定表达三态易误用；sealed 让 when 穷举、Ok 拿不到 body、Error 拿不到 lines；suspend 抛网络异常符合 Kotlin 取消语义（codex transport 同构） |
| D17 | 新增 SseLineParser（Flow\<String\> → Flow\<SseLine\>） | 行切分是 T4 parseStream 与 T8 默认 HttpEngine 的共同前置；纯逻辑独立可测；W3C 行解析语义 |
| D18 | 新增 SseEvent(data, event) + SseEventParser；聚合器输出结构化事件而非 data 文本 | MCP 实锤用 event 字段（codex rmcp-client 过滤非 message）；Codex 因 data-only 聚合器服务不了 MCP 被迫写两套；一个聚合器服务模型流与 MCP 两端 |
| D19 | loop 前置校验：非 2xx 不进 parseStream；content-type text/html 黑名单 | 风控 HTML 真实 case（用户实测）；非 2xx 错误 body 是文本不是 SSE；黑名单避免白名单误伤改 content-type 的真实网关 |
| D20 | 非 2xx 错误码映射 429→RateLimit / 401,403→Auth / 5xx→Overloaded / 其他→Transport；body 截断 2000 字符进 message | LLMErrorCode 已有分类直接复用；message 是 UI 详情非完整响应 |
| D21 | T4：DeepSeekChatCompletionProtocol 独立实现，不复用 OpenAI 通用层 | 避免未来 DeepSeek 私有字段（reasoning_content 等）牵动通用逻辑；即使 pi 复用 openai-completions 也不学它（用户裁决） |
| D22 | T4：协议层 Completed = 单次模型流结束（消息级）；finish_reason=tool_calls → Completed(ToolUse)，回合未结束（T6 继续工具循环）；错误 finish_reason / EOF 无 finish_reason → Error 事件 | 骨架注释「消息级结束原因」+ pi done 事件（每次流都发）；T2 判非 Stop/Length 为异常是工具循环未实现前的占位，T6 改 |
| D23 | T4：encodeToolResult 不加工错误内容：tool 消息 content = outcome.content 原样（null 用空串） | 错误表达由下游在 outcome.content 决定（用户裁决）；骨架「Interrupted/Unknown 编码为错误文本」注释作废 |
| D24 | T4：Image 块 buildRequest 抛 IllegalStateException（M2 前），不写专门测试 | 明确失败优于自动修复；异常消息讲清楚即可（用户裁决） |
| D25 | T4：ThinkingSignature 对 DeepSeek 不产出；signature 字段为 null；assistant 无思考补 reasoning_content 空串 | DeepSeek 无签名机制（Anthropic 语义）；requiresReasoningContentOnAssistantMessages=true |
| D26 | T5：holder write 全部实现（改值 + lastWriter，覆盖语义），落点按消费点接——Serialization → buildRequest、HttpRequest → stream、Input → 请求历史投影；ToolCall/ToolResult 字段就绪落点 T6 | 骨架已声明全部 write 签名，实现 = 填方法体零契约增量；无落点的 write 在 T5 无调用路径（无工具执行点），无静默风险 |
| D27 | T5：LLMErrorCode 新增 HookFailed（不可重试） | 模型段 hook 异常 → 回合失败需要稳定 code 供 host 映射文案；枚举增值有先例（§8.8 #3 UnknownTool） |
| D28 | T5：hook 异常 → 回合 Failed(HookFailed)；取消传播；afterRequest 只在 stream 成功返回后触发 | §8.4 #13 落地；请求未完成不触发 after（§8.10 #1 只读实际发出请求） |
| D29 | T5：链式分发 = RealAgentLoop 内按注册顺序 for 循环，无独立分发器 | 如无必要不增实体；顺序执行 + mutation 可见由循环天然表达 |
| D30 | T5：Input 改写落点 = 替换 history 末尾 User 的文本块（保留图像等非文本块）；无 User/无文本块不替换；TurnStarted 保持原始 input | pi 在消息组装前替换将进入 LLM 的文本（无树）；okia 树不变量（§5.8）使落点为 buildRequest 历史投影；事件反映事实 |

## 下一步

开始 T6：tooling——ToolExecutor / ToolRegistry 默认实现 + 工具循环（LLM ↔ 工具多轮）+ ToolCall 时机接入（beforeToolCall 改参 / writeOutcome 阻断短路 + afterToolCall + ToolResultHolder 落点）。前置待裁决：ToolCallOutcome 5 态在工具执行结果中的映射（§5.6 已定，落地确认）。等待用户确认后开始。
