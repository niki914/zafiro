# OC Ferry 手机 Termux 使用指南

在 **Android 手机 Termux** 里直接跑 OC Ferry：不需要 root、不需要 Zafiro、不需要编译、
不需要任何第三方 Python 包。核心 `oc_ferry.py` 是纯 Python 3 标准库（3.8+ 即可）。

两件事可以分开用：

| 目标 | 需要上游 API Key？ | 消耗额度？ | 命令 |
| --- | --- | --- | --- |
| **导出免费模型文档** | **不需要** | **不会** | `bash termux/export.sh` |
| 体检模型 + 转发给客户端 + 控制面板 | 需要（你自己的 BYOK 上游） | 会，少量 | `bash termux/start.sh` |

> 免费模型目录来自公开的 `https://models.dev/api.json`。导出文档只读取这份目录，
> **不会向任何模型发送推理请求**，因此既不需要密钥也不消耗额度。

---

## 1. 把文件放到手机

在 Termux 里执行（任选一种方式把整个仓库目录放到 `~/oc-ferry`）：

```bash
pkg install -y python git
git clone <你的仓库地址> ~/oc-ferry     # 或者用文件管理器把目录复制到 ~/oc-ferry
cd ~/oc-ferry
bash termux/setup.sh
```

`setup.sh` 会：确认 Python → 申请 Android 存储权限 → 用 `$HOME` 生成
`termux/config.json` → 建立数据目录 → 跑一次离线 `doctor` 自检。

手机上的目录结构：

```
~/oc-ferry/
├── .agents/skills/oc-ferry/     # 核心：oc_ferry.py / panel.html / SKILL.md ...
├── termux/
│   ├── config.termux.json       # 配置模板（setup.sh 读取它）
│   ├── config.json              # setup.sh 生成的实际配置（含密钥，勿提交）
│   ├── setup.sh  start.sh  stop.sh  export.sh
└── TERMUX.md                    # 本文
```

---

## 2. 最短路径：导出免费模型文档（推荐先试这个）

```bash
cd ~/oc-ferry
bash termux/export.sh
```

输出默认写到手机共享存储：

```
~/storage/shared/Documents/oc-ferry/FREE_MODELS.md
```

用手机文件管理器打开「文档 → oc-ferry」即可看到。也可以指定路径和格式：

```bash
bash termux/export.sh ~/storage/shared/Download/free.md          # Markdown
bash termux/export.sh ~/storage/shared/Download/free.json json   # JSON（脚本消费）
bash termux/export.sh ~/storage/shared/Download/free.csv  csv    # CSV（表格软件）
```

等价的底层命令（脚本只是包了一层）：

```bash
python .agents/skills/oc-ferry/oc_ferry.py --config termux/config.json \
  export --format md --online --output ~/storage/shared/Documents/oc-ferry/FREE_MODELS.md
```

`export` 的其它用法：

| 命令 | 联网 | 说明 |
| --- | --- | --- |
| `export --online` | 是（只读公开目录） | 拉最新免费模型清单，不需要密钥 |
| `export --catalog <本地.json>` | 否 | 用你自己的目录文件离线导出 |
| `export` | **否** | 用上次 `refresh` 留下的本地快照导出，完全离线 |
| `export --format json\|csv` | 同上 | 换格式 |
| `export --no-cache` | 同上 | 不合并体检缓存，只出纯清单 |

导出文档包含：生成时间、数据来源、判定规则、**免费模型总数与供应商数**、
四档状态统计、按供应商分组的完整明细表（模型 ID / 上游模型 / 名称 / 状态 /
工具 / 判定码 / 体检时间）、使用说明与诚实交代。

**免费判定规则**：`input` / `output` / `cache_read` / `cache_write` 四个价格字段
全为 0 才算免费；`deprecated` 直接排除；名字里含 `free` **不作数**。

> 本次实测（2026-11-08，公开目录 235 个供应商）：筛出 **421 个免费模型 / 75 个供应商**，
> 已生成 `FREE_MODELS.md` / `.json` / `.csv` 三份示例文档放在仓库根目录。
> 数量会随目录价格变动而变化，请以自己重新导出的结果为准。

---

## 3. 完整模式：体检 + 转发 + 手机控制面板

这一步才需要你自己的 OpenAI 兼容上游（BYOK）。编辑 `termux/config.json`：

```json
{
  "data_dir": "/data/data/com.termux/files/home/.oc-ferry",
  "port": 41980,
  "host": "127.0.0.1",
  "upstream_base_url": "https://your-provider.example/v1",
  "upstream_api_key": "sk-...",
  "catalog_url": "https://models.dev/api.json"
}
```

`upstream_base_url` 是**必填**（`serve` / `refresh` / `probe` 都要它）；
`upstream_api_key` 可选，只发给上游，绝不下发给客户端。

```bash
bash termux/start.sh     # 后台启动，PID 写入 data_dir/ferry.pid
bash termux/stop.sh      # 优雅停止（SIGINT -> 自动 cleanup 自己写入的条目）
```

启动后：

- **手机控制面板**：本机浏览器打开 `http://127.0.0.1:41980/panel`
  （免鉴权但零数据；在输入框粘贴 `<data_dir>/api-key` 的内容后才会加载数据）
- **客户端接口**：`http://127.0.0.1:41980/v1`，API Key = `<data_dir>/api-key`
- **日志**：`<data_dir>/ferry.log`

手动命令（脚本之外）：

```bash
PY=.agents/skills/oc-ferry/oc_ferry.py
CFG=termux/config.json

python $PY --config $CFG doctor     # 离线自检，不联网不写文件
python $PY --config $CFG refresh    # 拉目录 + 免费筛选 + 工具体检（消耗少量额度）
python $PY --config $CFG status     # 查看缓存判定与四档状态
python $PY --config $CFG import     # 把可用模型安全写入 models.json
python $PY --config $CFG serve      # 前台启动服务（Ctrl-C 退出自动清场）
python $PY --config $CFG cleanup    # 摘掉自己打过标记的条目
python $PY --config $CFG probe opencode/demo/free-model   # 只体检一个模型
python $PY --config $CFG export --online                  # 导出文档
```

四档状态：`available` 可用（带工具）/ `chat_only` 仅对话（不带工具）/
`rate_limited` 限流 / `offline` 下线。后两档不会提供给客户端。
判定保留 6 小时、负载状态 10 分钟，按「模型 ID + 能力指纹」失效。

---

## 4. 后台保活（Android 12+ 很重要）

Android 会在后台杀掉长时间运行的进程（phantom process killer），导致服务中断。建议：

1. 安装 **Termux:API** App 并在 Termux 里 `pkg install -y termux-api`；
   `start.sh` 会自动调用 `termux-wake-lock`，`stop.sh` 自动 `termux-wake-unlock`。
2. 系统设置里把 Termux 的电池优化关掉（「不优化 / 允许后台运行」）。
3. 长时间不用时执行 `bash termux/stop.sh`，避免无谓耗电。
4. 服务**只绑 `127.0.0.1`**，同一台设备内的浏览器 / 客户端才能访问；
   不要把 41980 转发到公网，也不要改 `host`（`validate()` 会拒绝）。

---

## 5. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| `export` 提示「没有免费模型数据」 | 本地快照为空。用 `export --online`（不需要密钥）或先 `refresh` |
| `export --online` 返回 403 | 旧版本没有 User-Agent 头；当前版本已统一补 `oc-ferry/1.0` UA |
| `serve` 提示 `serve requires upstream_base_url` | 在 `termux/config.json` 填 `upstream_base_url` |
| 面板打不开 | 服务没启动；`bash termux/start.sh`，再看 `<data_dir>/ferry.log` |
| 面板能开但数据空白 | 需要在面板输入框粘贴 `<data_dir>/api-key` 的内容 |
| 端口被占用 | 改 `config.json` 的 `port`（例如 41981），并同步改访问地址 |
| 文档找不到 | 确认执行过 `termux-setup-storage` 并授权；默认在「文档/oc-ferry」 |
| `refresh` 很慢 | 默认并发 2（手机省电）；可调 `probe_concurrency`，但会更耗电 |
| 某个模型昨天能用今天没了 | 免费池价格 / `deprecated` 随时变化，属预期行为 |

---

## 6. 安全边界与诚实交代

- 服务只绑 `127.0.0.1`；`/v1/*` 与 `/admin/*` 强制 Bearer（`hmac.compare_digest`）；
  非同源 `Origin` 在数据接口一律 403；请求体上限 8MB、并发上限 4。
- `import` / `cleanup` **只增删带 `_oc_ferry.managed` 标记的条目**，你手写的配置逐字节不动；
  写前备份、加锁、`os.replace` 原子替换；文件损坏时拒绝覆盖。
- 审批围栏：模型只能用 JSON 信封表达意图，任何本机动作一律拒绝并转交为外部工具调用，
  **服务端没有任何执行路径**。流式 SSE 下围栏只做审计透传（已知限制）。
- `refresh` / `probe` 会向上游发短请求、消耗少量免费额度；「通过」只代表这一次能按协议
  产生动作；上游不提供剩余额度查询；免费池随时变化；不可用模型不会提供给客户端。
- 导出的清单是**生成时刻的快照**，「免费」不等于「无限量」，也不代表模型已体检可用。
- `termux/config.json` 含 `upstream_api_key`，**请勿提交到公开仓库**。

---

## 7. 与 Zafiro Skill 的关系

同一份 `oc_ferry.py` 也按 `.agents/skills/` 结构组织，可作为 Zafiro Skill 加载；
但在 Termux 场景下它就是普通 CLI，**不依赖 Zafiro、Chaquopy 或任何 Android App 运行时**。
设计取舍与完整架构见 `docs/OC_FERRY_MOBILE.md`。
