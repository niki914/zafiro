# grouping PRD —— 命令运行 / 终端会话 的划分与组织

> 状态：发散草稿，未收敛成方案。目的：把痛点、真实 case 与可能的解方向全部摊开，供后续决策取舍。
> 范围：只覆盖 "terminal" 这一个内置工具及其周边（自定义工具、Python、工具开关 UI）。不涉及 Linux/Alpine 环境。

## 1. 背景

当前内置工具 `terminal` 把四个互不相同的维度压进一个 name、一条 schema、一个描述：

| 维度 | 取值 | 心智 |
|---|---|---|
| 触发形态 | `command`（执行）/ `action`（读/写/提交/关会话） | 两种调用签名挤在同一 schema |
| 执行语义 | 一次性（前台，能拿到 exit_code）/ 后台轮询 / 交互会话 | "跑完拿结果" 与 "进环境干活" 混合 |
| 身份 | user / root / shizuku | 权限阶梯 |
| 后端 | local / ssh | 本地设备 / 远端主机 |

一次 LLM 调用要面对：18 个 schema 字段、双模式路由规则、SSH 强制后台特例、身份枚举、超时语义。工具的"宽度"已经超过模型与用户两边的心智负担。

## 2. 痛点（含实际 case）

### P1. 查询类小事被迫走重型调用

Case：用户问"帮我看看手机剩多少内存"。模型需要 `free -m` 或 `cat /proc/meminfo`，一个前台一次性命令即可。

现状开销：
- 每次调用携带 18 字段 schema + 长 description 进 context（token 成本，每次对话反复出现）；
- 模型要在双模式里决策：传 `command` 直接跑，还是先 `open` 拿 session；
- 返回结构带 session 语义字段（status、elapsed_seconds），查询类结果被包装在会话框架里。

问题：90% 的调用是这种"跑一下拿结果"，却背负了为 10% 的会话场景设计的全部复杂度。

### P2. SSH 的强制后台特例

Case：模型想通过 SSH 在远端跑 `ls /opt`。

现状：`backend=ssh` 必须同时传 `background=true`，否则返回 invalidRequest："SSH backend requires background=true. SSH sessions cannot reliably detect command completion in foreground mode."。

问题：
- 这是一个"用户读不懂为什么"的特例。为什么本地可以前台、远端必须后台？底层原因是完成检测不可靠，但对调用方是不可见的设计债；
- 违背直觉：`background=true` 的语义在本工具里对不同 backend 含义不同（本地=后台执行，SSH=开启交互通道），同一参数两种解释；
- 模型踩错一次就是一次无效 RTT + 错误恢复轮次。

### P3. 本地一次性调用的状态语义含糊

Case：模型分三步：`cd /data/foo`、`./build.sh`、`cat log.txt`。

现状：工具描述声称 "Working directory and filesystem state persist between calls within a session"，但前台一次性调用每次 `openAndExecute` 都开新会话、跑完即关，**跨调用的 cwd/env 实际上不保留**。描述语与行为不一致：
- 模型被描述误导，可能以为 `cd` 后有状态，第二次调用省略绝对路径；
- 结果：第二次命令在默认 cwd 下跑，要么报错要么跑错目标。

### P4. 长任务的 RTT 翻倍

Case：`pip install` / 构建 / 大文件下载，单条命令超过前台 timeout（默认 180 s）。

现状：必须 `background=true` 两步走——第一步 start 拿 session_id，第二步 `action=read` 轮询。拿最终结果至少两轮，且中途需要多轮 read。

问题：长任务在会话模型里"天然两轮"，而它本可以用"run + 等结果"一次语义表达（像 execute_python 的 timeout 到顶返回部分输出）。

### P5. 凭证进模型上下文

Case：经 SSH 操作内网服务器，密码每次随调用传入。

现状：
- schema 明示 `password` 字段，工具 hint 的示例直接含 `"password":"..."`；
- 密码随每次调用的 argumentsJson 进入模型上下文，也进日志/历史记录；
- description 只承诺 "Credentials are not stored by this tool"，回避了"曾经过模型上下文"的事实。

问题：安全边界模糊。用户想"允许执行命令"与"允许 SSH 到生产服务器 + 交出密码"是两档意愿，现在一档开关覆盖。

### P6. 开关粒度：一个工具 = 一档权限

Case：用户只想给模型本地查系统/跑脚本的能力，不想暴露 SSH（跳板机、内网、生产环境）。

现状：`terminal` 一个开关同时控制 local 与 ssh。开启即两者都可用，关闭两者都不可用。
- 安全上无法"给命令不给 SSH"；
- 反过来，用户开了但从不 SSH，为不用的能力承担了密码泄露面。

### P7. schema 与描述的 token 负担

现状量级（估算）：
- `TERMINAL_SCHEMA` 18 个 property，其中 7 个（host/port/username/password/connect_timeout/server_alive_interval/backend）只服务 SSH，查询类调用完全用不到；
- description 四段约 600+ 字符，覆盖命令/后台/后端/action 四种心智；
- 每次对话这条工具定义都进 context，且无法按调用裁剪。

问题：为长尾能力（SSH）支付全面的上下文开销。

### P8. 自定义工具与内置 terminal 的关系混乱

Case：用户建自定义工具 `deploy`，里面是 `cd /data/app && ./deploy.sh`。

现状：`ToolManager.withCustomShellGuidance` 给所有自定义工具的 description 追加：
> "For commands that need root or Shizuku privileges, use the terminal builtin tool with identity=root or identity=shizuku instead."

问题：
- 自定义工具的命令明明就是 shell 命令（走 `executeCustomCommand`），却要被引导去"换个工具跑"；
- 两条 shell 执行路径（自定义工具、terminal）并存，心智不统一：同样的命令，一个在 custom shell 里跑（固定 user 身份、cwd 不持久），一个在 terminal 里跑（可选身份）——模型的决策成本上升。

### P9. 会话的生命周期对用户不可见

Case：模型开了 SSH 会话，任务中断（用户打断 / 工具超时 / 进程被杀）。

现状：会话由 `TerminalSessionPool` 持有（已核实无空闲回收/过期机制，仅每条命令有 timeout），模型忘记 `close` 就泄漏进程/连接。

问题：对用户与模型来说，会话的存在、数量、存活状态透明性不足；没有"会话列表/清理"入口。

## 3. 场景 case 分类

| 分类 | 示例 | 期望承载 | 现状承载 |
|---|---|---|---|
| 查询 | `free -m`、`getprop`、`dumpsys pkg` | 一次调用拿结果，零会话 | terminal 前台一次性（带 18 字段 schema） |
| 脚本执行 | 跑一段 shell 脚本、`./build.sh` | 一次调用，可选超时与部分输出 | terminal 前台/后台 |
| 多步工作流 | cd + 编译 + 查日志（本地） | 保持 cwd/env 的持续会话 | 无（前台每次全新会话） |
| 远端操作 | SSH 到服务器部署、查日志 | 交互会话，凭证管理 | terminal SSH（强制后台、密码随调用） |
| 异步长任务 | 安装、下载、批处理 | run 后异步轮询，单轮发起 | terminal background 两步 |
| 文件操作 | 读配置、写脚本、列目录 | 独立工具（或命令组合） | 命令组合，无独立入口 |

## 4. 可能的解方向（发散，未收敛）

1. **两工具划分**：`run_command`（一次性、零会话、直接拿结果）+ `terminal`（会话/SSH/异步 job）。查询类走轻量通道，会话类走重型通道。
2. **三工具划分**：进一步把 SSH 独立为 `ssh` 工具（凭证/连接/交互自成一体），解决 P5/P6 的权限与安全诉求，代价是工具数量与心智数量同步增长。
3. **参数折叠**：不拆工具，但按调用裁剪 schema（动态生成子 schema：command-only 模式不暴露 SSH 字段），解决 P1/P7 的 token 负担，不解决 P2/P6。
4. **会话内持久化**：补"本地可复用会话"能力（open 后 exec 保持 cwd/env），让 terminal 的多步语义名副其实，解决 P3。
5. **异步逃生口**：`run_command` 增加 async 参数（返回 job_id 轮询），长任务单轮发起，解决 P4。
6. **凭证管理**：SSH 主机/凭证从调用参数移出到设置侧（存主机配置，调用只传 host 名），密码不再经过模型上下文，解决 P5。
7. **工具组概念**：若拆分成多工具且有联动开启诉求（如"SSH 只有与 terminal 同开才有意义"），引入组开关：一组工具只能同开同关，UI 层面合并展示。Eta 未采用此机制（其 run_command 与 terminal 独立开关、无运行期依赖）；是否引入取决于拆分后是否产生"只开一半会坏"的真实耦合。
8. **统一执行核心**：无论拆几个工具，底层共用 `TerminalSessionPool` 与一个命令执行函数（Eta 中 `run_command` 与 `terminal.open_and_exec` 即同一实现），避免维护两条 shell 路径，解决 P8 的部分混乱。

## 5. 开放问题

1. 本地"可复用会话"是否值得新做一层？现状没有，只有一次性与后台任务。投入 vs 用例密度（多步本地工作流出现频率）。
2. SSH 是否值得独立成工具？独立后安全边界最干净（P5/P6 全解），但工具数量、schema、prompt 引导成本随之上升。
3. `run_command` 是否给 async 逃生口？给了会模糊"一次性"语义，不给则长任务必须靠 terminal。
4. 工具组概念是否引入？触发条件：拆分后是否存在"只开一半导致功能割裂"的真实场景。目前未观察到与 Eta 结构不同的证据。
5. 凭证管理是否在本次范围内？它独立于工具划分，但 P5 是当前最明确的用户侧风险。

## 6. 事实引用（代码位置，供后续核实）

- `agent-runtime/.../buildin/impl/TerminalBuiltin.kt`：18 字段 schema；SSH 强制 `background=true`（`handleCommand` → `handleSshCommand`）；hint 示例含明文密码；description 声称 session 内 cwd/env 持久。
- `agent-runtime/.../buildin/BuiltinToolRegistry.kt`：12 个内置工具注册点。
- `app/.../repo/XRepo.kt`（BuiltinToolApi）：`name→enabled` 扁平 flags 存储，无分组概念。
- `app/.../ui/content/BuiltinToolsSettingsContent.kt`：工具开关页为每个工具一个 Toggle，无分组 UI。
- `agent-runtime/.../agentic/python/PyRuntime.kt`：`execute_python` 走独立 `:python` 进程（嵌入式 runtime），与 terminal 无共享执行路径。
- `app/.../repo/ToolManager.kt`（agent-runtime 下）`withCustomShellGuidance`：自定义工具描述被引导去用 terminal 的高权限身份。