# Breeno（小布助手）适配指引

## 现有代码参考

已有的配置与实现即为最权威的参考（Single Source of Truth）：
- 胶水与驱动实现：`app/src/main/java/com/niki914/zafiro/mod/feat/oppo/`
- 配置定义读取：`BreenoConfigProvider.kt`
- 既有版本配置模板：`app/src/main/res/raw/com_heytap_speechassist_*_config.json`
- 版本列表与入口：`com_heytap_speechassist_versions.json`、`Entrance.kt`

---

## 宿主核心类清单

新版本适配时，只需参考最近一个已支持版本的 `config.json` 结构，在新 APK 中定位下列核心类的混淆与方法名变化：

| 宿主核心类 / 角色 | 职责 | 适配关注点 |
|---|---|---|
| `com.heytap.speechassist.aichat.AIChatDataCenter` | 对话数据中心 | 单参插入消息方法（输入与回答卡片）、两参更新消息方法（流式刷新） |
| `com.heytap.speechassist.aichat.AIChatRoomIdManager` | 房间与会话管理 | 清理/重置会话缓存的方法 |
| `com.heytap.speech.engine.connect.core.manager.i` | 入站消息处理器 (MessageProcessor) | 接收两参并反序列化 directives 的方法 |
| 指令转 Operation 工厂（混淆类） | 指令分发工厂 | 通过搜索 `CleanOperation` / `DoNothingOperation` 定位混淆工厂类名及返回类型 |
| `NewCuiFloatWindowRootView` / `SpeechAssistMainActivity` | 悬浮窗与主界面 | 悬浮窗 `onDetachedFromWindow` 与主界面 `onResume` |
| `com.heytap.speechassist.aichat.bean.AIChatViewBean` | 对话卡片数据载体 | 确认思考流（reasoning）、打字机、反馈点赞等方法与 LocalData Key 是否保持一致 |

---

## 适配流程

1. 拷贝最近一个已知稳定版本的 `com_heytap_speechassist_<prevVersion>_config.json` 作为模板；
2. 在新版本中核对上述核心类对应的方法名与类名，更新 JSON 中的对应字段；
3. 将新版本号追加至 `com_heytap_speechassist_versions.json`，并在 `Entrance.kt` 注册对应 raw 资源映射；
4. 运行编译命令验证无误。
