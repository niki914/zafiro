# githooks

提交前的坏味道与多语言对齐检查。机制是 `pre-commit` 依次调用：
- `check.py`：拦掉几类"写代码时容易顺手写进去、但没人愿意读到"的 Kotlin 坏味道；
- `check_strings.py`：检查修改过的 `strings.xml` 各语言版本键值是否严格对齐，防止漏写翻译。

## 启用

```sh
git config core.hooksPath .githooks
```

**这条不做，其余全是白干**：git 只在 `core.hooksPath` 指向的目录里找钩子，而它默认指向 `.git/hooks`（那里没有任何我们的脚本），脚本不会自动生效。

几个性质：

- 这是**本地配置，不进版本库**。换机器、重新 clone 都要再设一次。
- 值是**相对路径**，按每个 worktree 的根目录解析，所以设置一次即覆盖全部 worktree。
- 副作用是**每个 worktree 用自己那份 `.githooks/`**：在一个 worktree 里改 `check.py` 会立刻生效，不影响别的 worktree。改钩子本体的开发过程因此很方便。
- 新 worktree 能拿到钩子的前提是 `.githooks/` 已经被提交（未跟踪的文件不会跟着 `git worktree add` 过去）。

## 它什么时候跑

`pre-commit`，即 `git commit` 之前。**只看本次新增的行**，不看存量——所以上线这套检查不需要先清理历史代码，写了新代码的模块也不会因为邻居脏而被拦。这一点本身就是"棘轮"：清干净的模块会一直保持干净。

**成功时没有违规、也没有豁免标记时完全静默。** 有输出就是有问题。

合并提交不跑这个钩子；`git commit --no-verify` 也能绕过——它是纪律工具，不是安全边界。

## 七条规则

| 规则 | 级别 | 要求 |
| --- | --- | --- |
| `monitor-lock` | **阻断** | 不用 `synchronized(...)` / `@Synchronized`，改用 `Mutex` / 协程 |
| `log-api` | **阻断** | 不用 `android.util.Log`、`println` / `print` / `System.out`，一律走 `com.niki914.logging.Logger` |
| `uncancellable-run-catching` | **阻断** | 不用标准库 `runCatching` / `mapCatching`（会生吞协程取消与中断），改用 `runCatchingCancellable` |
| `bare-catch` | 警告 | 不写裸 `catch (e: Throwable)` / `catch (e: Exception)`，改用 `runCatchingCancellable` / `tryOrNull` 或捕获具体异常类型 |
| `legacy-comment` | 警告 | 注释只描述代码**现在**是什么样，不描述“曾经/不再”是什么样 |
| `inline-fqn` | 警告 | 代码里用到的类要 `import`，不内联写全限定名 |
| `log-tag` | 警告 | 日志 TAG 必须以 `niki914_zafiro_<ClassName>` 开头 |

`legacy-comment` 的例子，左边不合格，右边合格：

- `// 不再走旧路径，改为走新路径` → 只写现在在走哪条路径。
- `// Foreground SSH wrapper. No longer reachable from the main path` → 直接删掉这句，或者只写它现在是什么。

注意 `之前` / `以前` **不在**关键词表里：它们大多是"位置/顺序"含义（`截在条目之前`、`排在通知之前`），是有价值的注释。

`inline-fqn` 有一条豁免：如果同名类已经 `import` 了，内联全限定名是在**消歧义**，不报。

## 协程取消安全与异常治理

Kotlin 标准库的 `runCatching` 和 `mapCatching` 会无差别捕获所有 `Throwable`，在协程环境下会生吞 `CancellationException` 与线程中断 `InterruptedException`，破坏协程生命周期的正常取消与结构化并发。

- **`uncancellable-run-catching`（阻断）**：禁止直接调用标准库 `runCatching` / `mapCatching`。一律改用 `com.niki914.zafiro.api.util.runCatchingCancellable`（或 `mapCatchingCancellable`）。
- **`bare-catch`（警告）**：捕获宽泛的 `Throwable` 或 `Exception` 会产生警告。应优先使用 `runCatchingCancellable`、`tryOrNull`、`tryOrDefault`、`tryIgnore`，或者捕获具体的受检异常（如 `IOException`、`JSONException`）。特殊场景（如底层 Binder 兜底或基础库实现）请加 `// githooks:ignore bare-catch` 或 `// githooks:ignore-file bare-catch`。

## 日志

一律用 `com.niki914.logging.Logger`，不用 `android.util.Log`，也不用 `println` / `print` / `System.out`。

TAG 统一是 `niki914_zafiro_<ClassName>`（现存 59 个 tag 常量全部符合）；写成常量供整文件复用：

```kotlin
private const val LOG_TAG = "niki914_zafiro_Foo"
```

`log-tag` 判定的是**名字长得像日志 TAG 的常量**（`TAG` / `LOG_TAG` / `logTag` / `loggingTag`）和**直接传给 `Logger.*` 的字面量 TAG**。像 `TAG_PREFIX`、`language_tag_en` 这种同名不同义的不在检查范围。
仓库里有两个历史遗留的 `TAG` 常量不是日志 TAG（`FilesBlock` 等文本块 tag），作为警告存在，需要时加放行标记。

## 放行

有的地方确实需要监视器锁，有的注释确实在讲一件必须交代的事。放行只需要一行注释标记：

```kotlin
// 行级：写在违规行的同一行，或紧贴其上的上一行
synchronized(lock) { swap() } // githooks:ignore monitor-lock 需要与回调原子交换

// 文件级：写在文件任意位置，放行整个文件里的这条规则
// githooks:ignore-file monitor-lock
```

- 规则名用逗号分隔，**不留空格**（第一个空格之后的内容当作理由，不解析）。
- 写 `all` 表示放行该作用域内的全部规则。
- 理由不强制，但强烈建议写——标记会跟着代码长期留在文件里，后来人只能靠这句话判断它是不是还成立。
- 标记**写在源码里**，所以文件头的文件级标记即使不在本次 diff 里也照样生效，属于一次性决策。
- 豁免数量会在汇总里报出来，不会被藏起来。

## 多语言对齐检查 (check_strings.py)

防止新增功能或文案时只写了基准语言（`values/strings.xml`）或英语，而遗漏了其它已支持语种（`values-ja`、`values-ru`、`values-es`、`values-b+zh+Hant` 等）。

- **增量联动**：当暂存区中有任意 `strings.xml` 变动时，自动定位对应模块（如 `app/src/main/res`），并对其下**所有语种版本**进行键集合（Key Set）全量对比。
- **严格对齐**：以基准语言 `values/strings.xml` 为唯一锚点，缺失 Key 或多出未定义 Key 均会直接阻断提交，并打印出具体差异项。
- **免翻译豁免**：在基准文件中声明了 `translatable="false"` 的条目（如无需翻译的应用名或技术标识）自动豁免，不要求在其它语种中出现。
- **标签支持**：覆盖 `<string>`、`<plurals>` 和 `<string-array>`。
- **新增语种提示**：当暂存区新增全新的语言文件（如 `values-de/strings.xml`）时，输出提示提醒确认全项目语种支持清单，检查其它模块是否需同步支持。

## 命令

### 代码坏味道检查 (check.py)

```sh
python3 .githooks/check.py                    # 增量：只查暂存区新增行（钩子用的就是这个）
python3 .githooks/check.py --all              # 全量：查整个工作区的已跟踪 .kt
python3 .githooks/check.py --all business/agent app   # 全量，限定模块，分批清理
python3 .githooks/check.py --all --rule monitor-lock  # 只查一条规则
python3 .githooks/check.py --all --stat       # 按模块出统计，可直接当清理工单
python3 .githooks/check.py --list-rules       # 列出规则与级别
python3 .githooks/test_check.py               # 跑测试
```

### 多语言对齐检查 (check_strings.py)

```sh
python3 .githooks/check_strings.py            # 增量：检查暂存区受影响的模块（钩子用的就是这个）
python3 .githooks/check_strings.py --worktree # 检查工作区有改动的模块（未暂存 + 暂存）
python3 .githooks/check_strings.py --all      # 全量：扫描全仓所有模块的 strings.xml
python3 .githooks/check_strings.py --all --stat # 输出全仓各模块多语言键值统计大盘
python3 .githooks/test_check_strings.py       # 跑多语言检查器的单元测试
```

### 单文件 Token 上限检查 (check_tokens.py)

限制单文件上限为 5,000 Tokens（按 `Tokens = bytes / 4` 换算，上限为 20,000 字节），防止大文件导致 AI Agent 上下文注意力衰减、工具调用截断与代码合并冲突。

```sh
python3 .githooks/check_tokens.py            # 增量：检查暂存区新增/修改的 .kt（钩子用的就是这个）
python3 .githooks/check_tokens.py --all      # 全量：扫描全仓所有已跟踪的 .kt
python3 .githooks/check_tokens.py --all app  # 全量，限定模块
python3 .githooks/check_tokens.py --all --stat # 输出全仓各模块超标大盘
python3 .githooks/test_check_tokens.py       # 跑 Token 检查器的单元测试
```

- **自动忽略**：排除 `libs/`、`build/` 等目录，排除 ViewBinding、Room `_Impl`、Navigation Directions 以及头部含 `@generated` / `DO NOT EDIT` 等非 Agent 产出的生成代码。
- **放行方式**：在文件任意位置（推荐第一行）添加 `// githooks:ignore-file file-token-limit`。
- **拆分指引**：阻断时会输出针对 Compose UI（拆子组件/Preview）、ViewModel（契约/领域服务下沉）、Repository（实体拆解/模型外置）、单元测试（按场景拆分）的具体操作步骤。

清理存量建议先 `--all --stat` 看分布，再按模块 + 按规则分批推进：一批只清一种坏味道，diff 才好读。

排除的路径在 `check.py` 的 `EXCLUDE_PREFIXES`，增量与全量共用同一份；目前默认排掉整个 `libs/`（内联的第三方库，不重构别人的代码风格）。

## 改策略

策略全在 `check.py` 顶部，改策略不用碰逻辑：

- `SEVERITY`：哪条规则阻断、哪条只警告。
- `EXCLUDE_PREFIXES`：不检查的路径前缀。
- `NARRATIVE`：`legacy-comment` 的关键词表，中英混排的一条正则，加词就加一个 `|新词`。

改完跑一遍 `python3 .githooks/test_check.py`。

## 已知边界

- 用正则做启发式判断，不是语法分析。判不准的时候宁可放过（`inline-fqn` 的 import 豁免就是这么来的）。
- 行尾注释（代码后面跟 `//`）不会被 `legacy-comment` 检查，只看整行都是注释的行。
- 三引号字符串（内联的 Python / Markdown 示例）内部的所有行都被跳过，因此 `CustomPyToolHarness` 里那些 Python `print(...)` 不会被误报。
- KDoc 的 `*` 续行算注释，会被检查。
- `--all` 只看**已跟踪**的文件（`git ls-files`），新文件先 `git add` 才会进全量扫描。
- 合并提交、`--no-verify` 会绕过检查。
