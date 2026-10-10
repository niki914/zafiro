---
name: assistant-add-support
description: 为语音助手宿主（如小布助手 Breeno 等）适配新版本 Xposed 接管支持。当用户要求支持新版本助手、适配助手应用更新、分析新版本 hook 点或解决宿主升级导致的接管失效时使用。
---

# assistant-add-support

为语音助手宿主适配新版本 Xposed 接管支持：提取/分析 APK、比对 Hook 点变化、生成新版本配置并完成编译验证。

## 宿主分支

根据目标宿主查阅对应的核心类清单与指引：

- **Breeno（小布助手，`com.heytap.speechassist`）**：阅读 [`docs/BREENO.md`](docs/BREENO.md)
- **XiaoAi（小爱同学，`com.miui.voiceassist`）**：阅读 [`docs/XIAOAI.md`](docs/XIAOAI.md)（当前留空不支持）

---

## 前置环境与依赖门禁

### 1. REA (Reverse Engineer Anything) 门禁

优先使用 REA 分析引擎（MCP / CLI）审查 APK 内部类与方法签名。

- 上游仓库：`https://github.com/morluto/rea`
- **严格授权门禁**：**当且仅当 REA 不可用时，提示用户需先部署 REA，严禁私自自动安装或配置，由用户自主决定。**

### 2. 提取与反编译工具

- 使用项目内的 `extract-apk` 技能（`.agents/skills/extract-apk/scripts/`）定位并导出目标 APK。
- 反编译按需进行：若需全局搜索则调用 `decompile_apk.py`，若只需针对性核对类结构可直接用 REA MCP 直连 APK。

---

## 适配流程

### Phase 1: 确认目标版本

1. 通过 `adb -s <serial> shell dumpsys package <package_name> | grep -E "versionCode|versionName"` 获取设备上的版本信息；
2. 与用户确认目标版本号与 `versionCode`。

### Phase 2: 提取 APK

运行 `extract_apk.py` 拉取当前版本 APK。根据分析需要决定是否执行全量反编译。

### Phase 3: 调研现有 Hook 机理与签名比对

1. 查阅宿主分支指引（如 [`docs/BREENO.md`](docs/BREENO.md)），了解宿主核心类角色；
2. 以最近一个已支持版本的 `app/src/main/res/raw/<package_name>_<prevVersion>_config.json` 作为基准模板（配置结构即 Single Source of Truth）；
3. 在新版本中核对配置中涉及的每个类名、方法名与签名；
4. 列出变动清单（未变 / 变动的具体点位）。

### Phase 4: 实现新版本配置与编译验证

1. 在 `app/src/main/res/raw/` 新建 `<package_name>_<versionCode>_config.json`，填入新签名的配置；
2. 在 `app/src/main/res/raw/<package_name>_versions.json` 追加新 `<versionCode>`；
3. 在 `app/src/main/java/a0/a0/a0/a0/a0/a0/Entrance.kt` 的 `configRawIdFor` 中注册对应 raw 资源映射；
4. 同步运行 `./gradlew :app:compileDebugKotlin` 验证编译。

### Phase 5: 交付与安装门禁（严格被动）

- **严格被动原则**：严禁主动向设备执行安装、推送或任何写操作！默认仅完成代码修改与编译验证，向用户汇报改动并等待代码审查（CR）。
- **仅当用户明确要求安装时**：
  1. 执行打包：`./gradlew :app:assembleDebug`
  2. 推送并使用 `pm` 安装：
     ```bash
     adb -s <serial> push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/app-debug.apk
     adb -s <serial> shell pm install -r -d -g /data/local/tmp/app-debug.apk
     adb -s <serial> shell rm -f /data/local/tmp/app-debug.apk
     ```
  3. 重启宿主使 Xposed 模块生效：`adb -s <serial> shell am force-stop <package_name>`
