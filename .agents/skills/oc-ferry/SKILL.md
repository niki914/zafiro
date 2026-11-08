---
name: oc-ferry
description: 把免费模型池摆渡成 Zafiro 可用的 OpenAI 兼容本机端点；当用户要刷新免费模型、体检某个模型、启动/停止转发服务、导入模型配置或排查 OC Ferry 状态时加载。
---

# OC Ferry（手机端 / Zafiro Skill）

你是 Zafiro Agent。本 Skill 让你在 Android 上以「端侧 Python 3 本机转发服务」的方式使用免费模型池，无需内置 OpenCode / Node / Electron 运行时。

## 何时加载

只在出现下列意图时加载本 Skill：

- 「刷新 / 找一下现在有哪些免费模型」
- 「**导出免费模型清单 / 生成一份文档**」（只需公开目录，不需要任何 API Key）
- 「体检 / 测一下某个模型能不能用工具」
- 「启动 / 停止 OC Ferry 服务」
- 「把模型导入 Zafiro 自定义模型配置」
- 「看一下现在模型状态 / 为什么某个模型不可用」

用户只是普通聊天、或者明确不想启动本机服务时，**不要**加载，也不要擅自执行任何命令。

## 怎么调用

所有能力都在 `.agents/skills/oc-ferry/oc_ferry.py`，纯 Python 3 标准库，Python 3.8+ 可用（Zafiro 通过 Chaquopy 在独立进程运行）。命令一律用 `python <skill>/oc_ferry.py --config <config.json> <子命令>`：

| 子命令 | 联网 | 作用 |
| --- | --- | --- |
| `doctor` | 否 | 离线自检：配置、数据目录、缓存、纯逻辑冒烟测试；不联网、不写文件 |
| `refresh` | 是 | 拉模型目录 → 发现免费模型 → 并发体检 → 写缓存与目录快照 |
| `probe <model> [--token T]` | 是 | 只体检一个模型并打印判定结果 |
| `import` | 否 | 把当前可用模型安全写入 `models_file` |
| `serve` | 是（有请求时） | 启动 `127.0.0.1:41980` 的 OpenAI 兼容服务（阻塞） |
| `status` | 否 | 打印缓存判定表与当前选中模型 |
| `cleanup` | 否 | 摘掉自己打过标记的条目，保留用户手写配置 |
| `export` | 默认否 | 把免费模型清单导出成 `md` / `json` / `csv` 文档：`--online` 只读公开目录（无需密钥）、`--catalog <file>` 或本地快照均离线 |

`export` 常用形式：`export --format md --online --output <路径>`；
`--no-cache` 只出纯清单不合并体检状态。**导出文档不向模型发送任何推理请求、不消耗额度**。

全局参数：`--config PATH` 指定配置文件，`--data-dir PATH` 覆盖数据目录。
**只有 `refresh` / `probe` / `serve` 才必须填 `upstream_base_url`**；
`doctor` / `status` / `cleanup` / `import` / `export` 都可以在没有上游密钥的情况下使用。

推荐顺序：`doctor` → 配置 `config.json` → `refresh` → `import` → `serve`。`serve` 优雅退出（Ctrl-C）时会自动执行 `cleanup`。

## 数据目录

默认 `data_dir`（示例为 `/sdcard/Documents/oc-ferry`，务必指向可写目录），其中包含：

- `models.json`：客户端模型配置文件（由 `import` 写入 / `cleanup` 清场）
- `api-key`：本机 Bearer key，首次 `serve`/`import` 自动生成，权限 0600
- `probe_cache.json`：体检判定缓存
- `catalog_snapshot.json`：最近一次发现的模型快照，`serve` 启动时零联网加载
- `selection.json`：`/admin/selection` 记录的当前选中模型
- `*.oc-ferry.bak` / `*.lock` / `*.corrupt-<ts>`：备份、文件锁、损坏文件备份

`doctor` 只读不写；`status`、`cleanup`、`import` 不联网。

## 在 Zafiro 里配置

自定义模型供应商填：

- Base URL：`http://127.0.0.1:41980/v1`
- API Key：`data_dir/api-key` 里的内容（或 `config.json` 的 `api_key`）
- 模型：从 `GET /v1/models` 取，id 形如 `OC · <名字>`；上游 id 保持 `opencode/` 前缀

服务路由共 **8 个数据/管理接口 + 1 个面板入口（8+1）**：

| 方法 | 路径 | 鉴权 |
| --- | --- | --- |
| GET | `/health` | 免 Bearer、免 Origin，仅回环、只回非敏感存活状态 |
| GET | `/panel`（`GET /` 是同一页面） | **免 Bearer、免 Origin**，只返回零数据静态 HTML |
| GET | `/v1/models` | Bearer |
| POST | `/v1/chat/completions`（支持 `stream` 的 SSE） | Bearer |
| POST | `/admin/refresh` | Bearer |
| POST | `/admin/probe` | Bearer |
| POST | `/admin/import` | Bearer |
| GET / POST | `/admin/selection` | Bearer |
| GET | `/admin/status` | Bearer |

除 `/health` 与 `/panel`（含 `/`）外，全部要求 `Authorization: Bearer <api-key>`（`hmac.compare_digest` 定时比较）。**没有** `/admin/cleanup` HTTP 路由：清场由 CLI `cleanup` 或 `serve` 退出时完成。`/panel` 的 `panel.html` 缺失时返回明确的 503 JSON 错误。`Origin` 围栏只作用于 `/v1/*` 与 `/admin/*`（非同源 → 403，同源放行以便面板 `fetch`）。

## 手机端控制面板

在**设备本机**浏览器 / Zafiro WebView 打开 **`http://127.0.0.1:41980/panel`**（`serve` 运行时有效；`GET /` 是同一页面）：

- **页面免鉴权但不返回数据**：`GET /panel` / `GET /` 免 Bearer、免 Origin 检查，只返回同目录 `panel.html` 的静态 UI 骨架，**不含任何模型 / 状态 / 密钥信息**，因此用户还没输 key 也能打开页面。所有数据由 `/health`、`/v1/*`、`/admin/*` 提供并严格鉴权。
- 本机 api-key 位于 `<data_dir>/api-key`（`serve` 启动时会打印）；在面板输入框粘贴一次即可。key 只存浏览器 `localStorage`，**不写进 URL、不发到任何第三方、不写服务端日志**。填入后数据接口带 `Authorization: Bearer <key>` 返回内容。
- 顶部显示服务状态 / 端口 / 上游 / 已缓存模型数（来自免鉴权的 `/health` 存活探针）；四档状态卡片（可用 / 仅对话 / 限流 / 下线）；每个模型可展开详情并「只重测这一个」。
- 操作按钮：检测全部（`refresh`）、写入客户端（`import`）、重新体检（`probe`）、每行勾选与「取消选中」（`selection`）。**「清理自己写入的条目」按钮只给出明确提示**：服务刻意不提供 HTTP 清理路由，请执行 CLI `cleanup`（或停止 `serve` 时自动清场），不要伪造接口。
- **面板只在设备本机可达**（只绑 `127.0.0.1`），不对外暴露；不要把它放到公网，也不要改 `host`。

> 手机端差异：桌面端不给浏览器用，任何带 `Origin` 的请求一律 403；手机端控制面板必须能从本机 WebView / 浏览器打开，因此桌面端围栏被**收窄**为「只在 `/v1/*` 与 `/admin/*` 上拒绝**非同源** `Origin`」，同源请求放行（浏览器对同源 POST 会自动带 `Origin`，一并拒绝会让面板失效）。整体边界为 **只绑 loopback + 数据接口 Bearer + 静态页零数据**。

## 四档状态

| 状态 | 含义 | 是否提供给客户端 |
| --- | --- | --- |
| `available` 可用 | 本次体检成功产生了一次合法的 Read 动作 | 是，带工具 |
| `chat_only` 仅对话 | 只回文本（`no_action`）或格式不支持（`invalid_model_output` / `invalid_tool_call`）；关闭工具 | 是，不带工具 |
| `rate_limited` 限流 | 上游 429/503；负载状态只保留 10 分钟 | 否 |
| `offline` 下线 | 动作不符（`probe_mismatch`）、超时、网络/HTTP 错误 | 否 |

判定保留 6 小时，按「模型 ID + 能力指纹」失效；能力变化会自动重新体检。

## 安全红线（必须遵守）

1. 服务**只绑定 `127.0.0.1`**；`Origin` 围栏**只保护数据接口**（`/v1/*` 与 `/admin/*`：非同源 `Origin` → 403，同源放行），`/panel`、`/`、`/health` 不做 Origin 检查。`GET /panel` / `GET /` **免 Bearer、免鉴权但不返回任何数据**（静态页），文件缺失返回 503 JSON；`/health` 是免鉴权的非敏感存活探针；**所有模型数据与全部管理操作**由 `/v1/*`、`/admin/*` 用 `hmac.compare_digest` 定时比较强制 Bearer 鉴权。安全边界 = 只绑 loopback + 数据接口 Bearer + 静态页零数据。请求体上限 8MB，并发上限 4。
2. **审批围栏**：模型只能用 JSON 信封（`oc_ferry_envelope`）表达「想做什么」。任何本机动作（shell / file / exec / localhost …）一律拒绝，并转交为 `external_tool_call` 信封。**执行权始终在客户端（你）手里，服务端永不执行本机动作**。收到 `external_tool_call` 时，你应先向用户说明并确认，再决定是否用 Zafiro 自己的工具去做。
3. **绝不触碰用户手写配置**：`import` / `cleanup` 只增删带 `_oc_ferry.managed` 标记的条目；写前备份、加锁、`os.replace` 原子替换；内容没变不落盘；文件损坏时拒绝覆盖。
4. `refresh` / `probe` 会**自动向上游发送简短请求并消耗少量免费额度**，执行前要让用户知情。
5. 通过只代表「这一次能按协议产生动作」；上游不提供剩余额度查询；免费池随时变化；不可用模型不会提供给客户端。

## 判定码速查

`ok` / `no_action` / `probe_mismatch` / `invalid_model_output` / `invalid_tool_call` 来自桌面版 `src/probe.js`；手机端另加运输层分级 `timeout` / `network` / `http_error` / `rate_limited`。仅 `no_action` 与 `probe_mismatch` 重试 1 次，超时与格式失败不重试。
