# OC Ferry（Zafiro 手机端 Skill）

把免费模型池「摆渡」成 Android 上 Zafiro 可直接使用的 OpenAI 兼容本机端点。
这是桌面版 [HRuiCcc/RuiC-oc-ferry](https://github.com/HRuiCcc/RuiC-oc-ferry) 的手机端移植：
**Skill（面向 Agent 的说明）+ 单文件 Python 3 端侧转发服务**，纯标准库、无第三方依赖、兼容 Chaquopy 的 Python 3.8+。

> 手机端与桌面端的根本差异：Android 上不可能内置隔离的 OpenCode / Node / Electron 运行时。
> 因此上游推理改为**用户自己配置的 OpenAI 兼容端点**，免费模型目录从
> `https://models.dev/api.json`（或自填 `catalog_url`）读取；五条核心链路的逻辑与安全边界保持不变。
> 详见 `docs/OC_FERRY_MOBILE.md`。

---

## 1. 安装到 Zafiro Skills

Zafiro 的 Skill 真实目录是 `.agents/skills/`（`.claude/skills` 是软链，**不要改软链**）。
把整个目录原样放进仓库 / 设备：

```
.agents/skills/oc-ferry/
├── SKILL.md             # 面向 Zafiro Agent（YAML frontmatter + 中文正文）
├── oc_ferry.py          # 单文件核心实现
├── panel.html           # 手机端控制面板（单文件、零依赖）
├── config.example.json  # 配置样例
├── test_oc_ferry.py     # 全离线 unittest 套件（123 用例）
└── README.md            # 本文件
```

上面是本 Skill 目录的 6 个文件；**整个 drop-in patch 共 14 个文件**：本目录 6 个
（含 `test_oc_ferry.py` 的 123 个离线用例）、`docs/OC_FERRY_MOBILE.md`（设计文档）、
根 `README.md`（交付说明）、`TERMUX.md`（手机 Termux 指南）与 `termux/` 下 5 个文件
（`config.termux.json` + 4 个脚本）。
根 `README.md` 只是本地交付说明，**合入 upstream 时请用 `git apply --exclude=README.md`
排除它**（上游已有自己的 README），实际落地 13 个文件。
生成的 `FREE_MODELS.*` 是会过时的数据快照，**不打进 patch**。
完整清单见 `docs/OC_FERRY_MOBILE.md` 第 10 节。

Zafiro 通过 Chaquopy 在独立 Python 进程运行 Python 3，直接执行 `python oc_ferry.py ...` 即可，不需要 pip 安装任何东西。

复制配置并编辑：

```bash
cp .agents/skills/oc-ferry/config.example.json .agents/skills/oc-ferry/config.json
```

`config.json` 被 `.gitignore` 与否由你决定；**它包含 `upstream_api_key`，请勿提交到公开仓库**。

---

## 2. 配置项含义

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `data_dir` | `~/.oc-ferry` | 数据目录。**手机上务必改成可写目录**（示例 `/sdcard/Documents/oc-ferry`）；也可用 `--data-dir` 覆盖 |
| `port` | `41980` | 本机端口 |
| `host` | `127.0.0.1` | **强制回环**，改成别的值会被 `validate()` 拒绝 |
| `upstream_base_url` | 空 | 必填（除 `doctor`/`status`/`cleanup` 外）。用户自己的 OpenAI 兼容端点，如 `https://openrouter.ai/api/v1` |
| `upstream_api_key` | 空 | 上游 Bearer key，可选；只发给上游，绝不下发给客户端 |
| `catalog_url` | `https://models.dev/api.json` | 免费模型目录来源 |
| `catalog_file` | 空 | 可选：本地目录 JSON 路径，便于离线 `refresh`（优先级高于 `catalog_url`） |
| `probe_concurrency` | `2` | 体检并发（手机省电，可配） |
| `success_ttl_minutes` | `360` | 成功判定保留 6 小时 |
| `load_ttl_minutes` | `10` | 当时负载状态只保留 10 分钟 |
| `models_file` | `<data_dir>/models.json` | 客户端模型配置文件（由 `import`/`cleanup` 维护） |
| `api_key_file` | `<data_dir>/api-key` | 本机 Bearer key 存放路径，首次自动生成（0600） |
| `api_key` | 空 | 也可直接在此填本机 key（优先级高于 `api_key_file`） |
| `request_timeout` | `60` | 单次上游请求超时（秒） |
| `max_body_bytes` | `8388608` | 请求体上限 8MB |
| `max_inflight` | `4` | 并发上限 |
| `embed_api_key` | `true` | 是否在 `models.json` 条目里写入 api_key；若担心明文可设 `false` 并在 Zafiro 手填 |
| `upstream_model_map` | `{}` | 目录模型 id → 上游 model id 的覆盖表（上游不认 `<provider>/<model>` 时用） |
| `cache_file` | `<data_dir>/probe_cache.json` | 体检缓存 |
| `state_file` | `<data_dir>/catalog_snapshot.json` | 目录快照，`serve` 启动时零联网加载 |
| `selection_file` | `<data_dir>/selection.json` | `/admin/selection` 的当前选中模型 |

> `config.example.json` 本身是合法 JSON，**不含注释**（JSON 不支持注释）；上表就是它的注释说明。

---

## 3. CLI 命令

统一形式：`python .agents/skills/oc-ferry/oc_ferry.py --config <config.json> <子命令>`

| 命令 | 联网 | 说明 |
| --- | --- | --- |
| `doctor` | **否** | 离线自检：Python 版本、路径可写性、缓存/快照状态、发现与判定的冒烟测试；不联网、不写文件 |
| `refresh` | 是 | 拉目录 → 只按价格字段发现免费模型 → 并发体检 → 更新缓存与快照 |
| `probe <model> [--token T]` | 是 | 只体检一个模型（如 `opencode/demo/free-model`），打印判定码与状态 |
| `import` | 否 | 把当前 `available` / `chat_only` 模型安全合并进 `models_file` |
| `serve` | 有请求时 | 启动 `127.0.0.1:41980` 的 OpenAI 兼容服务（阻塞）；Ctrl-C 退出时自动 `cleanup` |
| `status` | **否** | 打印缓存判定表、四档状态说明、当前选中模型 |
| `cleanup` | **否** | 摘掉自己打过标记的条目，用户手写配置原样保留 |
| `export` | **默认否** | 把免费模型清单导出成 `md` / `json` / `csv` 文档 |

全局参数：`--config PATH`、`--data-dir PATH`。
**只有 `refresh` / `probe` / `serve` 需要 `upstream_base_url`**；
`doctor` / `status` / `cleanup` / `import` / `export` 均可在无上游密钥时使用。

典型流程：

```bash
python .agents/skills/oc-ferry/oc_ferry.py --config .agents/skills/oc-ferry/config.json doctor
python .agents/skills/oc-ferry/oc_ferry.py --config .agents/skills/oc-ferry/config.json refresh
python .agents/skills/oc-ferry/oc_ferry.py --config .agents/skills/oc-ferry/config.json status
python .agents/skills/oc-ferry/oc_ferry.py --config .agents/skills/oc-ferry/config.json import
python .agents/skills/oc-ferry/oc_ferry.py --config .agents/skills/oc-ferry/config.json serve
```

### 3.1 文档导出（不需要任何 API Key）

`export` 只读取公开目录 / 本地目录 / 上次快照，**不向模型发送推理请求、不消耗额度**，
直接生成一份中文免费模型清单：

```bash
# 拉最新公开目录（唯一联网路径，无需密钥）
python .agents/skills/oc-ferry/oc_ferry.py --config <cfg> export \
  --format md --online --output ~/FREE_MODELS.md

# 用自己的目录文件，完全离线
python .agents/skills/oc-ferry/oc_ferry.py --config <cfg> export \
  --format json --catalog ./my-catalog.json --output ./free.json

# 用上次 refresh 的本地快照，完全离线
python .agents/skills/oc-ferry/oc_ferry.py --config <cfg> export --format csv
```

| 参数 | 说明 |
| --- | --- |
| `--online` | 拉 `catalog_url` 公开目录（无需密钥、不消耗额度） |
| `--catalog PATH` | 本地目录 JSON，优先级最高，离线 |
| 不带上述参数 | 用本地快照 `state_file`，完全离线 |
| `--format md\|json\|csv` | 默认 `md` |
| `--output PATH` | 默认 `<data_dir>/free-models.<format>` |
| `--no-cache` | 不合并体检缓存，只出纯清单 |

文档含：数据来源、判定规则、免费模型总数与供应商数、四档状态统计、
按供应商分组的明细表、使用说明与诚实交代。
手机 Termux 的一键方式见根 `TERMUX.md` 与 `termux/export.sh`。

---

## 4. 在 Zafiro 里配置自定义模型

Zafiro 支持自定义模型供应商（BYOK）。在「设置 → 模型供应商 / 自定义」里填：

| 项目 | 值 |
| --- | --- |
| Base URL | `http://127.0.0.1:41980/v1` |
| API Key | `<data_dir>/api-key` 文件的内容（`serve` 启动时会打印） |
| 模型列表 | `GET /v1/models` 返回的 id，形如 `OC · <名字>` |

也可以先 `import` 把条目写进 `models_file`，再让 Zafiro 读取。客户端可见 id 前缀是 `OC · `，转发时服务端会把它解析回保持 `opencode/` 前缀的上游 id，再按 `upstream_model_map`（或默认剥前缀）映射成上游端点认识的 model。

服务路由共 **8 个数据/管理接口 + 1 个面板入口（8+1）**，`/` 是 `/panel` 的别名：

| 方法 | 路径 | 鉴权 / 说明 |
| --- | --- | --- |
| GET | `/health` | **免 Bearer、免 Origin**，仅回环、只返回非敏感存活状态（含上游是否已配置、缓存条目数、面板路径） |
| GET | `/panel`（或 `/`） | **免 Bearer、免 Origin、零数据静态 HTML**（`text/html; charset=utf-8`）；`panel.html` 缺失返回 503 JSON |
| GET | `/v1/models` | Bearer；当前可用模型列表 |
| POST | `/v1/chat/completions` | Bearer；转发，支持 `stream: true` 的 SSE |
| POST | `/admin/refresh` | Bearer；刷新目录并体检；body 可传 `{"catalog": {...}}` 离线注入目录 |
| POST | `/admin/probe` | Bearer；body `{"model": "...", "token": "可选"}` |
| POST | `/admin/import` | Bearer；合并写入 `models_file` |
| GET / POST | `/admin/selection` | Bearer；查看 / 设置（或 `{"clear": true}` 取消）当前选中模型 |
| GET | `/admin/status` | Bearer；面板用的全量状态：所有模型 + 四档计数 + selection |

**没有** `/admin/cleanup` HTTP 路由：清场统一由 CLI `cleanup` 或 `serve` 退出时完成（面板「清理」按钮只给出明确提示，不伪造接口）。

**安全边界 = 只绑 `127.0.0.1` + 数据接口 Bearer + 静态页零数据**：`/health` 与 `/panel`（含 `/`）免鉴权但都不含任何模型 / 状态 / 密钥数据；所有模型数据与全部管理操作由 `/v1/*`、`/admin/*` 用 `hmac.compare_digest` 定时比较强制 Bearer 鉴权。

**手机端 Origin 差异**：桌面端不给浏览器用，任何带 `Origin` 的请求一律 403；手机端控制面板必须能从本机 WebView / 浏览器打开，因此桌面端围栏被**收窄**为「**只在 `/v1/*` 与 `/admin/*` 上拒绝非同源 `Origin`**」；同源（`127.0.0.1` / `localhost` 且端口一致）放行——浏览器对同源 POST 也会自动带 `Origin`，一并拒绝会让面板自己的 `fetch` 全部 403。`/panel`、`/`、`/health` 不做 Origin 检查。请求体超 8MB → 413；并发超 4 → 503。

### 4.1 手机端控制面板

`serve` 运行时，在**设备本机**浏览器 / Zafiro WebView 打开 **`http://127.0.0.1:41980/panel`**（`GET /` 是同一页面）。
面板是单文件 `panel.html`，纯原生 HTML/CSS/JS、零依赖、无 CDN / 外链，移动优先（viewport、竖屏、≥44px 点击区、safe-area-inset、`prefers-color-scheme` 浅色/深色）。

- **页面免鉴权但不返回数据**：`GET /panel` / `GET /` 免 Bearer、免 Origin 检查，只返回同目录 `panel.html` 的静态 UI 骨架（零模型 / 状态 / 密钥数据），因此用户还没输 key 也能打开页面——这是必需的，否则浏览器地址栏无法设置 `Authorization` 头就打不开面板。`panel.html` 缺失 / 不可读时返回明确的 **503 JSON** 错误，不崩溃。所有数据由 `/health`、`/v1/*`、`/admin/*` 提供并严格鉴权。
- **api-key 位置**：`<data_dir>/api-key`（`serve` 启动时会打印，或 `config.json` 的 `api_key`）。在输入框粘贴一次即可，key 只存浏览器 `localStorage`，**不写进 URL、不发到任何第三方、不写服务端日志**。填入后数据接口带 `Authorization: Bearer <key>` 才会返回内容。
- **顶部**：服务状态（运行中 / 端口 / 上游 host / 已缓存模型数，来自免鉴权的 `/health` 存活探针）+ 本机 api-key 输入框；未输 key 时状态条仍可见。
- **四档状态卡片**：可用 / 仅对话 / 限流 / 下线，颜色与文案区分；每个模型行可展开详情（判定码、工具、负载、能力指纹、体检时间、原因、是否客户端可见），并支持「只重测这一个」。
- **操作按钮**：检测全部（`POST /admin/refresh`）、写入客户端（`POST /admin/import`）、重新体检（`POST /admin/probe`）、勾选 / 取消模型（每行 checkbox 与「取消选中」，`POST /admin/selection`）。**「清理自己写入的条目」按钮只给出明确提示**：服务刻意不提供 HTTP 清理路由，请执行 CLI `cleanup`（或停止 `serve` 时自动清场），不伪造接口。所有数据请求同源、带 `Authorization: Bearer <key>`，**不手动设置 `Origin`**；加载态与错误都有可见提示。
- **安全说明**：面板**只在设备本机可达**（只绑 `127.0.0.1`），不监听 `0.0.0.0`、不对外暴露、不经过任何公网或第三方服务；`host` 被 `validate()` 强制为 `127.0.0.1`。请勿把端口转发到公网或改 `host`。

与桌面端 UI 的能力对照：

| 能力 | 桌面端控制面板 | 手机端 `panel.html` |
| --- | --- | --- |
| 形态 | Electron 原生窗口 | 本机 WebView / 浏览器单页（纯静态、零依赖） |
| 服务状态 / 端口 / 上游 / 缓存数 | 有 | 有（`GET /health`） |
| 四档状态与模型明细 | 有 | 有（`GET /admin/status` + `GET /v1/models`） |
| 检测全部 / 单模型重测 | 有 | 有（`POST /admin/refresh`、`POST /admin/probe`） |
| 写入客户端 | 有 | 有（`POST /admin/import`） |
| 清理条目 | 有 | **按钮给出明确 CLI 提示**（无 HTTP 清场路由，用 CLI `cleanup` / `serve` 退出清场） |
| 勾选 / 取消选中模型 | 有 | 有（`POST /admin/selection`） |
| api-key 存放 | 桌面应用配置 | 浏览器 `localStorage`，不写 URL |
| 系统代理 / 目标客户端切换 | 有（`system-proxy` / `target`） | 无（Android 无对应概念，由 Zafiro 自定义供应商直连回环） |
| 浏览器访问 | 不支持（带 Origin 一律 403） | 支持：`/panel` 免鉴权零数据静态页；`/v1/*`、`/admin/*` 拒绝非同源 Origin、同源放行 |
| 页面鉴权 | 原生窗口无需鉴权 | 免 Bearer 但零数据；数据接口全部强制 Bearer |

---

## 5. 四档状态

| 状态 | 判定来源 | 客户端可见 |
| --- | --- | --- |
| `available` **可用** | `ok`：恰好一次 `Read`，`file_path` 含随机 token | 是，带工具 |
| `chat_only` **仅对话** | `no_action`（只回文本，桌面 probe 实际抛 502）、`invalid_model_output`、`invalid_tool_call` | 是，**关闭工具** |
| `rate_limited` **限流** | 上游 429 / 503；负载状态 10 分钟后过期 | 否 |
| `offline` **下线** | `probe_mismatch`（调用数≠1 / 非 Read / 路径不含 token）、`timeout`、`network`、`http_error` | 否 |

- 仅「语义不符」(`probe_mismatch`) 与「无动作」(`no_action`) 重试 1 次；超时与格式失败不重试。
- 判定保留 6 小时，按「模型 ID + 能力指纹」失效；跨重启 / 跨进程复用。
- 限流/下线模型不会出现在 `/v1/models`，也不会被转发。

---

## 6. 诚实交代（请务必读完）

- **检测会自动发送简短请求、消耗少量免费额度**：`refresh` 对每个免费模型最多发 2 次请求（1 次 + 最多 1 次重试）。
- 「通过」**只代表这一次能按协议产生动作**，不代表模型长期稳定、不代表能完成真实任务。
- **上游不提供剩余额度查询**，本 Skill 也不查询、不猜测额度。
- **免费池随时变化**：目录来自 `models.dev/api.json`，价格字段随时可能被调整；`deprecated` 模型直接排除，名字里含 `free` 不算免费，只认 `input/output/cache_read/cache_write` 四个价格全为 0。
- **不可用模型不会提供给客户端**，你有时会发现「昨天能用的模型今天没了」，这是预期行为。
- 服务只监听 `127.0.0.1`，但仍请保管好 `api_key` 与 `upstream_api_key`；`models.json` 里默认会写入本机 key（可用 `embed_api_key: false` 关闭）。

---

## 7. 手机端 vs 桌面端差异

| 维度 | 桌面端（OC Ferry） | 手机端（本 Skill） |
| --- | --- | --- |
| 形态 | Electron 托盘应用 | Zafiro Skill + 端侧 Python 3 服务 |
| 运行时 | 内置 OpenCode / Node 运行时 | 无内置运行时；Zafiro 通过 Chaquopy 独立进程跑 Python 3 标准库 |
| 免费模型来源 | OpenCode 免费模型池 | `https://models.dev/api.json` 或用户自填 `catalog_url` |
| 推理上游 | OpenCode 本地进程 | 用户配置的 OpenAI 兼容 `upstream_base_url` + 可选 `upstream_api_key` |
| 端口 | `127.0.0.1:41980` | 相同 |
| 发现规则 | 价格全 0 + 排除 deprecated + 名字不作数 | 相同 |
| 体检 | 5 工具 / 随机 token / 60s / 并发 3 | 相同规则，并发降到 **2**（手机省电，可配） |
| 缓存 | 判定 6h、负载 10min、能力指纹失效 | 相同 |
| 安全写入 | 只动自己标记的条目 / 备份 / 锁 / 原子替换 / 退出清场 | 相同 |
| 审批围栏 | 模型只能用 JSON 信封表达意图，执行权在客户端 | 相同 |
| admin 路由 | `refresh/probe/import/selection/target/system-proxy/find-config` | `refresh/probe/import/selection` + 面板所需的 `status`（共 8 个数据/管理接口 + 1 个面板入口）；`target`/`system-proxy`/`find-config` 是桌面 Electron 特有，手机端不适用；**无 `cleanup` HTTP 路由**，清场走 CLI |
| 系统代理 | 支持 | 不支持（Android 无桌面系统代理概念），由 Zafiro 自定义供应商直接指向回环端口 |
| 控制面板 | Electron 原生窗口，不给浏览器用（带 Origin 一律 403） | `panel.html` 本机 WebView / 浏览器静态单页；**免鉴权但零数据**，数据接口 Bearer |
| Origin 围栏 | 任何带 `Origin` 一律 403 | **收窄**：只在 `/v1/*` 与 `/admin/*` 拒绝非同源 `Origin`，同源放行；`/panel`、`/`、`/health` 不检查 |

---

## 8. 安全边界小结

- 只绑 `127.0.0.1`（面板也只在设备本机可达，不对外暴露）。安全边界 = **只绑 loopback + 数据接口 Bearer + 静态页零数据**：`/health` 与 `/panel`（含 `/`）免鉴权、免 Origin 检查，但都不含任何敏感数据；`Origin` 围栏**只保护数据接口**（`/v1/*` 与 `/admin/*` 非同源 → 403、同源放行）；**所有模型数据与全部管理操作**由 `/v1/*`、`/admin/*` 强制 Bearer（`hmac.compare_digest` 定时比较）；8MB / 并发 4 限制。
- 只增删带 `_oc_ferry.managed` 标记的条目；写前备份（`.oc-ferry.bak`）、`FileLock`、`os.replace`；内容没变不落盘；文件损坏拒绝覆盖并备份为 `.corrupt-<ts>`。
- 模型输出必须是 JSON 信封；命中 shell / file / exec / localhost 等本机动作关键词 → 拒绝并转成 `external_tool_call` 信封；**服务端没有任何执行路径**。流式 SSE 只透传并以 `X-OC-Ferry-Fence: stream-passthrough` 标注审计边界。
- `import oc_ferry` 零副作用：不联网、不读文件、不建目录、不生成密钥。
