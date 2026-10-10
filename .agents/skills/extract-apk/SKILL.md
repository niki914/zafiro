---
name: extract-apk
description: 定位、提取并反编译 Android APK：按关键字在设备上搜索包名、用 adb 导出 APK（含 split）、再用 jadx 反编译成可检索的源码树，并输出三个可预测的产物路径。当用户想拿到某个 App 的反编译代码、只知道 App 中文名/模糊名字、需要从已连接设备导出 APK，或需要把 APK 变成 sources/ 目录时使用。
---

# extract-apk

把「人类口中一个 App」变成「本地一份可检索的反编译源码树」。三个脚本，一条直线，不做别的事。

## 边界

**做**：搜索包名 → 提取 APK → 反编译 → 打印规范产物路径。

**不做**：不读反编译产物里的源码、不看 `AndroidManifest.xml` 内容、不生成 hook 配置、不做静态分析。读到路径就结束，源码阅读交给下游流程。

如果越界去读源码，会把本应属于静态分析阶段的上下文窗口消耗掉。

## 环境前置

脚本目录：`<本 skill 目录>/scripts/`，下文命令都用 `python3 <脚本绝对路径>` 调用，不要 cd 进去。

依赖三项：`adb`（设备侧）、`java`（jadx 运行时）、`jadx`（反编译器）。

- 缺 `jadx`：脚本会自己用 `brew list --formula jadx` 判断是不是装了没进 PATH，并给出确切安装命令。**不要自动安装**，把命令给用户确认。
- 缺 `java`：脚本会提示 `brew install openjdk`。
- 设备未就绪：脚本会打印 `adb devices` 的实际输出；多设备时用 `--serial`，或设 `ANDROID_SERIAL`。

## 三步流水线

### 1. 搜索包名

```
python3 <scripts>/search_app.py <keyword> [--limit N] [--serial SERIAL]
```

关键字做包名子串匹配，大小写不敏感。搜不到就换更短的关键字（品牌名 / 域名片段）重试，不要猜包名。

**关于应用显示名**：adb 拿不到 label（`pm list packages` 和 `dumpsys package` 都没有这个字段，设备上也没有 `aapt`）。所以英文系统下搜 `wechat`、`line`、`telegram` 这类"界面上的名字"会命中 0 条。此时脚本会自动列出设备上全部第三方包（`pm list packages -3`）供按语义挑选，例如 `com.tencent.mm`、`tv.danmaku.bili`。不要自己编造包名，从这份列表里选。

### 2. 提取 APK

```
python3 <scripts>/extract_apk.py <package_name> [--serial SERIAL] [--force]
```

- 单个 APK → 存成 `<root>/extracted_apps/<包名下划线化>.apk`。
- 检测到 split → 全部拉到 `<root>/extracted_apps/<包名下划线化>_splits/`，`base.apk` 在内。**不要只拿 base.apk**：缺 split 会导致 jadx 资源解析缺失，静态事实失真。
- 目标已存在时直接返回既有路径（`status: cached`），不重复下载；要重拉加 `--force`。

### 3. 反编译

```
python3 <scripts>/decompile_apk.py <apk_文件或_splits_目录> [--force] [--no-res]
```

- 传目录时会把目录内所有 `*.apk`（`base.apk` 优先）一次性喂给 jadx，这是 split 场景的正确用法。
- 产物已存在且 `sources/` 完整 → `status: cached` 直接返回，不重跑。
- `--no-res` 只产出 sources，跳过资源解析，用于超大 App 抢时间。

**jadx 是分钟级任务，必须用后台方式执行**（例如 `bg_run`），不要在前台阻塞等。脚本自身按百分比打印进度到 stderr。

## 产物路径约定

根目录默认是**运行脚本时的工作目录**下的 `.apk_decs/`（`Path.cwd() / ".apk_decs"`），可用环境变量 `APK_DECS_ROOT` 覆盖成别的绝对路径。

因为默认跟 cwd 走，**运行前先确认自己在对的工作目录**：脚本用绝对路径调用，产物就会落在你当时所在目录。要固定到某处，直接设 `APK_DECS_ROOT`。

```
<cwd>/.apk_decs/extracted_apps/<包名下划线化>.apk      # 单 APK
<cwd>/.apk_decs/extracted_apps/<包名下划线化>_splits/  # split 场景
<cwd>/.apk_decs/<apk_stem>_apk/sources/                # Java 源码根
<cwd>/.apk_decs/<apk_stem>_apk/resources/AndroidManifest.xml
<cwd>/.apk_decs/<apk_stem>_apk/.rgignore               # 已忽略框架/资源噪声，可直接给 rg 用
<cwd>/.apk_decs/<apk_stem>_apk/jadx.log                # jadx 完整输出
```

`<apk_stem>` 由输入推导：文件取去扩展名，`*_splits` 目录去掉 `_splits` 后缀。

脚本 stdout 是稳定的 `key: value` 行，固定包含 `sources_path`、`manifest_path`（不存在时为 `null`）、`output_dir`（绝对路径）。下游流程只需要读这几个字段。

## 失败处理

| 现象 | 处理 |
|---|---|
| `matched: 0` | 显示名搜不到属正常（见上文 label 说明），从脚本输出的 `device_package` 列表里按语义挑 |
| `未找到该包` | 应用没装：`adb shell pm list packages` 确认，或换设备 |
| 拉取失败 | 多为设备连接、权限或存储不足，向用户升级，不要重试循环 |
| `jadx 未产出 sources 目录` | 真失败：日志尾部已随错误打印，完整日志在 `<out>/jadx.log`；加 `--force` 重跑 |
| `警告：jadx 退出码 1` | **不是失败**。真实 APK 总有部分类反编译不了，只要 `sources/` 产出就算成功；错误数在 `jadx.log` 里搜 `finished with errors` |
| 反编译极慢或 OOM | 加大内存：设 `JADX_OPTS=-Xmx8g`；或先 `--no-res` |

## 自检

改过脚本后跑一次共享工具的自检（纯断言，无依赖）：

```
python3 <scripts>/_common.py
```

覆盖点：`package:` 行解析、`base.apk` 选取、包名归一化、split 目录输入排序、stem 推导、进度解析（jadx 1.5 是 `progress: N of M (P%)`，取括号内百分比）、线程/内存启发值。改动解析逻辑后这个自检必须过。
