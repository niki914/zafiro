# Nexus Agent 启动文档

## 作用

本文件用于在每次会话开始前为 Agent 注入最小且稳定的仓库上下文，使其不必从零猜测项目结构、信息来源与首选工作路径。

## 项目定位

Nexus 是一个 Android Xposed 模块。它在语音助手 App 中截获用户 query，交给 LLM 运行时生成回答，再注入回宿主 UI 替换原生回复。

### 术语（你在对话中会用到这些词）

- **宿主 / Host**：被 Hook 的语音助手 App。Breeno（ColorOS，`com.heytap.speechassist`）和 XiaoAi（HyperOS，`com.miui.voiceassist`）
- **主 App**：Nexus 自己的进程（`com.niki914.nexus.agentic`），跑设置 UI + Runtime Service
- **takeover**：本轮 query 的接管决策——`InjectedLLM`（Nexus 替换回答）或 `NativeTakeover`（放行原生回复），由 `TakeoverResolver` 判定
- **turn**：一轮"用户问 → 回答呈现"的完整生命周期，`ConversationTurnState` 跟踪
- **store**：一个命名的 JSON 持久化单元（如 `agent.main.config`、`rules.takeover`），原子写入 `filesDir/` 下对应路径
- **render pipeline**：LLM 响应注入宿主 UI 的链路。Breeno 走卡片全量刷新，XiaoAi 走响应目标捕获 + 指令分片注入

### 进程与 IPC 骨架

- **宿主进程**：Xposed Hook 注入点。通过 `AgentRuntimeClient`（Binder）向主 App 提交 LLM 查询，通过 `XIpcBridge.StoreClient`（Binder）读写 settings store
- **主 App 进程**：运行 `AgentRuntimeService`（前台 Service，Binder IPC），持有 store 持久化的本地访问权
- **两条 IPC 通道**：`XIpcBridge`（配置读写/通知，走 `StoreClient` Binder 接口） + `AgentRuntimeService`（LLM query 提交与 `RenderFrame` 流式回调）

## 工作原则

- 默认先理解上下文，再动手修改
- 当前实现以源码为准，设计文档仅代表意图
- 只在任务明确需要时扩散阅读范围，避免盲目全仓搜索
- Do not preserve backward compatibility. Remove obsolete paths instead of adding compatibility layers, fallbacks, or migrations.
- Choose the simplest implementation that fully meets the current requirements. Avoid speculative abstractions, configuration, and indirection.
- Grow the system in layers. Start from the smallest version that works end to end, and add each new capability on top of a product that already works. Never trade a working product for unfinished complexity.
- Keep components modular and concerns clearly separated.
- Prefer established, well-maintained libraries when they reduce overall complexity or improve reliability. Do not reimplement common functionality without a clear reason.
- Lean on the dependencies already in the project before writing your own implementation or adding packages. Do not assume a library lacks a capability without checking its documentation and types.
- Make architectural decisions for the long term. Do not accept a stopgap that only works for now and is meant to be replaced later.

1. 使用标准术语。已有通用说法的概念不要另造表述。
2. 不要使用比喻。如果一个说法需要读者推断其指代对象，改为直接说明。
3. 表头和分类名使用中性名词，如“问题”“现象”“影响”“结果”。
4. 不使用“是 X，不是 Y”的对比句式。
5. 表格条目不要求每条都包含数字或结论。部分条目可以只说明发生了什么。
6. 未查明原因的问题写“原因未查明”。
7. 不使用口语词。
8. 不使用拟人表述。

规则 1：使用标准术语

超长度 / 超不超长度 → 截断 / 是否触及长度上限
Ray 的端口会跟自己撞 → 多个任务的 Ray 抢占同一端口
臂 → 方案
只看长度那条线 → 长度启发式基线
刷奖励 → 奖励被优化但评测指标未改善
存档 → checkpoint

规则 2：不发明比喻作为术语

血统 → 基座来源
8 个不同血统 → 8 个不同基座的模型
那是尺寸差异，不是血统差异 → 原有区间由 Qwen 的 14B、32B、72B 构成，差异来自参数量而非基座
已经吃掉一半空间 → 已覆盖一半区间
变成了一个认题目的检索器 → 退化为问题识别，与学习价值无关
挑出来的题目看上去健康得多 → 选中问题的截断比例更低
误差范围还盖着基准线 → 置信区间与基线重叠

规则 3：表头和分类名使用中性名词

坑 / 代价和教训 → 问题 / 影响
把握 → 确认程度
怎么做的 → 实验设置
重挑一次还剩多少重合 → 重采样后的重合率
有几种不同取值 → 取值数量
还在跑的 → 进行中
一个必须关掉的开关 → 需要关闭的过滤器

章节状态标签需要保持一致。

如果前面使用：

已完成
已确认
未达到基线
结论待定

后面出现了：

做通了
意外发现
需要修
没有买到想要的

统一使用前面部分的中性状态标签。

规则 4：不使用“是 X，不是 Y”的对比句式

梯度对齐：是噪声，不是信号 → 梯度对齐：三项检查结果均在噪声范围内
我们买到了血统上的多样性，但没有买到能力上的多样性 → 新增模型覆盖了更多基座，但正确率均低于原有区间下界
考法和用法对不上 → 评测口径为成对比较，与实际使用方式不一致
稳定是靠粗糙换来的 → 取值数量少的指标重合率高
越稳定的指标越挑不出东西，越挑得出东西的指标越不稳定 → 重合率与取值数量呈反向关系

规则 5：允许条目不包含数字或结论

例如：

对照组为随机选取的 32 个问题。

这条内容已经完整，不需要继续补充其他方案与对照组的关系。

规则 6：未查明原因时直接写“原因未查明”

前文已经写明“原因未查明”时，后文不要继续补充未经验证的解释。

这可能也解释了前文那个现象 → 该现象与截断的关系尚未验证

第十章“后来查明了机制”之后的内容已经得到确认，可以保留。

规则 7：不使用口语词

赢得很干脆 → 差值为 0.030
测得更准 / 测得更糙 → 估计精度更高 / 更低
可选的余地很小 → 候选范围小
基本上等于抓阄 → 接近随机选取
本来就分不出高下 → 真实差距低于可分辨范围
白跑 / 白占 → 无效运行 / 空占
不是白捡的 → 需要 79 GPU·小时
一个致命问题 → 主要问题
有事后找补的嫌疑 → 该切分方式在观察结果之后确定
原因不复杂 → 删除
既然预测这条路走不通，那就退一步 → 删除，直接进入实验设置

规则 8：不使用拟人表述

天生需要几百到上千次采样 → 该信号所需的采样量为几百到上千次
一旦超就制造出参差 → 截断发生时会增大奖励方差
24 步训练只挪动 0.05 → 24 步训练后正确率变化为 0.05
超得越彻底反而越稳定 → 截断率越高，奖励方差越低
回答内容本身的好坏参差 → 答案质量的差异

## 首选模式

- 需要项目上下文、架构事实、源码入口、能力现状时，直接读源码定位：grep / 文件浏览找入口，再读关键文件
- Kai（`libs/kai/`）重设计的需求事实源是 `docs/kai-prd.md`；实现现状以 `libs/kai/` 源码为准

## Skill 路由

- `asc-director-old`
  - 用于新增功能、方案设计、技术调研、任务拆解、页面开发、模块重构、Bug 修复方案
  - 适合多阶段任务，不适合回答单个局部源码问题
- `context-engineering`
  - 用于编写或修改提示词、Agent 文档、Skill 文档、任务说明
  - 凡是目标读者主要是 Agent 而不是人类用户，优先加载它
- `release-new-version`
  - 用于 Nexus 发版提交流程（同步 app/build.gradle.kts 版本字段与 GitHub release）

## 默认执行顺序

1. 判断任务是否需要仓库上下文
2. 若需要，直接读源码定位（grep / 文件浏览）
3. 若任务是方案或复杂执行编排，切到 `asc-director-old`
4. 若任务是提示词或 Agent 文档编写，切到 `context-engineering`
5. 完成路由后再读源码、给结论或实施修改
