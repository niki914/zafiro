# githooks

提交前的代码质量、规范与多语言对齐检查体系。
由 `pre-commit` 统一调用 `cli.py` 进行调度执行，只依赖 Python 3 标准库（零外部依赖）。

## 启用

```sh
git config core.hooksPath .githooks
```

**这条不做，其余全是白干**：git 只在 `core.hooksPath` 指向的目录里找钩子，而它默认指向 `.git/hooks`（那里没有任何我们的脚本），脚本不会自动生效。

几个性质：
- 这是**本地配置，不进版本库**。换机器、重新 clone 都要再设一次。
- 值是**相对路径**，按每个 worktree 的根目录解析，所以设置一次即覆盖全部 worktree。
- 副作用是**每个 worktree 用自己那份 `.githooks/`**：在一个 worktree 里改动会立刻生效，不影响别的 worktree。
- 新 worktree 能拿到钩子的前提是 `.githooks/` 已经被提交（未跟踪的文件不会跟着 `git worktree add` 过去）。

## 它什么时候跑

`pre-commit`，即 `git commit` 之前。**只看本次新增或修改的变动**，不看存量——所以上线检查不需要先清理历史代码，写了新代码的模块也不会因为邻居脏而被拦。这一点本身就是"棘轮"：清干净的模块会一直保持干净。

**成功时没有违规、也没有豁免标记时完全静默。** 有输出就是有问题。
合并提交不跑这个钩子；`git commit --no-verify` 也能绕过——它是纪律工具，不是安全边界。

---

## 规则分类全集 (Taxonomy)

全体系规则按领域关注点划分为五大类，共 12 条规则：

```
                       ┌── Concurrency (monitor-lock)
                       ├── Coroutine & Exception (uncancellable-run-catching, bare-catch)
    1. safety ─────────┼── Logging API (log-api)
                       └── Log Tag Prefix (log-tag)

    2. ui ─────────────┬── Design System Shapes (rounded-corner-shape -> G2)
                       └── Copywriting & Punctuations (verbose-ui)

    3. hygiene ────────┬── Narrative Legacy Comments (legacy-comment)
                       └── Inline Fully Qualified Names (inline-fqn)

    4. i18n ───────────┬── Strings Alignment (strings-alignment 键集合一致性)
                       └── ContentDescription I18n (content-description-i18n 禁多语言资源)

    5. architecture ─── File Token & Size Budget (file-token-limit 5k tokens 限制)
```

### 规则分类明细表

| 领域分类 (`--category`) | 规则 ID | 级别 | 核心要求与修复建议 |
| :--- | :--- | :--- | :--- |
| **`safety`** (安全规范) | `monitor-lock` | **阻断** | 禁止使用 `synchronized(...)` / `@Synchronized`，改用 `Mutex` 或协程 |
| | `uncancellable-run-catching` | **阻断** | 禁止调用标准库 `runCatching` / `mapCatching`（会生吞取消），一律改用 `runCatchingCancellable` |
| | `bare-catch` | 警告 | 不写裸 `catch (e: Throwable)` / `catch (e: Exception)`，改用 `runCatchingCancellable` / `tryOrNull` 或捕获具体受检异常 |
| | `log-api` | **阻断** | 禁止使用 `android.util.Log`、`println` / `print` / `System.out`，一律走 `com.niki914.logging.Logger` |
| | `log-tag` | 警告 | 日志 TAG 必须以 `niki914_zafiro_<ClassName>` 开头 |
| **`ui`** (界面与文案) | `rounded-corner-shape` | **阻断** | 禁止使用原生 `RoundedCornerShape`，项目要求统一使用 G2 连续圆角（`G2FieldShape` / `G2CardShape` / `G2BubbleShape`） |
| | `verbose-ui` | 警告 | 检测中文 UI 文案中的分号与解释性括号。文案必须简洁专业、拒绝话痨：去掉多余括号解释，分号精简为短句或改用换行 |
| **`hygiene`** (代码整洁) | `legacy-comment` | 警告 | 注释只描述代码**现在**是什么样，不描述“曾经/不再/修复了什么”（如“不再走旧路径”是不合格的） |
| | `inline-fqn` | 警告 | 用到的类要 `import`，不内联写全限定名（同名类刻意消歧义除外） |
| **`i18n`** (多语言资源) | `strings-alignment` | **阻断** | 以 `values/strings.xml` 为唯一锚点，所有支持语种严格对齐键集合，缺失或多出未定义 Key 均阻断 |
| | `content-description-i18n` | **阻断** | 禁止 `contentDescription` 适配多语言资源（如 `stringResource`）或非英文文案，统一使用纯英文硬编码或 null |
| **`architecture`** (工程架构) | `file-token-limit` | **阻断** | 限制单文件上限为 5,000 Tokens（20,000 字节），防止大文件导致 AI Agent 注意力衰减与合并冲突 |

---

## 放行机制 (Ignore Directives)

确有必要放行时，无需关停检查，在源码中添加放行标记即可：

### 1. Kotlin 代码放行 (`.kt`)

```kotlin
// 行级放行：写在违规行的同一行，或紧贴其上的上一行
synchronized(lock) { swap() } // githooks:ignore monitor-lock 需要与回调原子交换

// 文件级放行：写在文件任意位置（推荐首部）
// githooks:ignore-file monitor-lock
// githooks:ignore-file file-token-limit
```

### 2. XML 资源放行 (`.xml`)

```xml
<!-- 条目级放行：写在对应条目前 -->
<!-- githooks:ignore verbose-ui 必要业务解释 -->
<string name="special_desc">保留解释（括号）</string>

<!-- 文件级放行：写在文件任意位置 -->
<!-- githooks:ignore-file verbose-ui -->
```

- 规则名用逗号分隔，**不留空格**（如 `monitor-lock,log-api`）。
- 写 `all` 表示放行该作用域内的全部规则。
- 强烈建议在规则名后写明放行理由，便于后续追溯与清理。

---

## 常用命令

### 1. 统一命令行 (`cli.py`) - 推荐

```sh
python3 .githooks/cli.py                     # 增量检查：只查暂存区新增变动（pre-commit 调用的就是这个）
python3 .githooks/cli.py --all               # 全量扫描：检查整个工作区所有已跟踪文件
python3 .githooks/cli.py --all business app  # 限定模块范围全量扫描
python3 .githooks/cli.py --worktree          # 检查工作区变动（含未暂存与已暂存）
python3 .githooks/cli.py --category ui       # 只检查 UI 类别（圆角规范与文案话痨）
python3 .githooks/cli.py --category safety   # 只检查安全类别（并发、协程、异常、日志）
python3 .githooks/cli.py --rule monitor-lock # 只检查单条指定规则
python3 .githooks/cli.py --all --stat        # 输出全仓各模块动态自适应的大盘统计表
python3 .githooks/cli.py --list-rules        # 查看所有规则的分类目录字典
```

### 2. 兼容入口

为兼容历史脚本调用习惯，保留了原有入口脚本，均已接入底层统一调度中心：

```sh
python3 .githooks/check.py --all             # 扫描代码坏味道 (safety, ui, hygiene)
python3 .githooks/check_strings.py --all     # 扫描多语言与文案 (i18n, ui)
python3 .githooks/check_tokens.py --all      # 扫描超标大文件 (architecture)
```

### 3. 运行测试

所有测试均为纯函数内存断言，不依赖任何外部工具，毫秒级执行：

```sh
python3 -m unittest discover -s .githooks/tests -p "test_*.py"  # 运行全套单元测试 (49 tests)
python3 .githooks/test_check.py                                 # 历史单测兼容运行 (56 tests)
python3 .githooks/test_check_strings.py                         # 历史单测兼容运行 (18 tests)
python3 .githooks/test_check_tokens.py                          # 历史单测兼容运行 (10 tests)
```

---

## 内部架构结构

```
.githooks/
├── pre-commit                  # Git 提交前钩子脚本
├── cli.py                      # 统一 CLI 入口与调度分发中心
├── check.py                    # 代码坏味道兼容转发入口
├── check_strings.py            # 多语言对齐兼容转发入口
├── check_tokens.py             # 文件大小限制兼容转发入口
├── README.md                   # 规范文档
├── core/                       # 零外部依赖底层核心工具
│   ├── git.py                  # Git 仓储交互、Diff 解析与文件读取
│   ├── ignore.py               # Kotlin 与 XML 统一豁免标记解析器
│   ├── models.py               # Rule, Finding, Severity, Category 基础模型
│   └── reporter.py             # 终端格式化、自适应列宽动态统计表
├── checks/                     # 各分类规则执行器
│   ├── code_rules.py           # 8 条 Kotlin 代码规范与正则检查
│   ├── strings_rules.py        # 多语言 XML 严格对齐与 UI 文案话痨检查
│   └── tokens_rules.py         # 5,000 Tokens 单文件上限与拆分建议
└── tests/                      # 纯函数单元测试集
    ├── test_cli.py
    ├── test_code_rules.py
    ├── test_strings_rules.py
    └── test_tokens_rules.py
```
