# EXT_TOOLS —— Eta 外部工具调研

> 状态：调研记录，已完成，供后续开发参考。
> 内容：同为 Android 系统级 Agent 的 Eta 项目工具对比调研。不包含 Zafiro 内部设计决策。

---

## 1. 背景

Eta（/tmp/eta）是同为 Android 系统级 Agent 的项目，工具总量约 81 个。Zafiro 当前内置工具 12 个。逐工具对比后，按"对用户有益、可泛化、shell 不可轻易替代、无兜底回退"四条标准筛选，保留 22 个值得参考的工具。

## 2. 保留清单（22 个）

| 工具 | 类别 | 说明 |
|---|---|---|
| set_alarm / set_timer | 闹钟与计时器 | 系统 `AlarmClock.ACTION_SET_ALARM/SET_TIMER` + `EXTRA_SKIP_UI` 直达。实现时只保留直达 Intent，失败即报错；不参考 Eta 的"打开时钟页让用户确认"兜底 |
| recent_notifications | 通知读取 | NotificationListenerService 读取当前通知栏。Zafiro 只有发送端 notify，无读取侧 |
| search_notification_history | 通知读取 | 通知使用权 + 本机 7 天 1000 条历史库。无系统命令可替代 |
| search_calendar_events / search_contacts / search_call_history / search_messages | 个人数据检索 | 标准 ContentResolver Provider |
| search_media / search_audio / search_recordings / search_files / search_downloads | 个人数据检索 | 媒体库与共享存储检索 |
| read_sms_code | 个人数据检索 | 只提取 4–8 位验证码、发送方、时间，不返回完整短信正文 |
| browser_use | 浏览器 | 离屏 WebView，13 种动作合一。Zafiro 目前最大能力空缺（只有 open_uri 外抛） |
| recent_app_activity / app_usage_summary | 应用活动 | UsageStatsManager，低优先 |
| get_current_location | 位置 | 读系统最近位置（passive），需后台定位权限 |
| wait_for_text | GUI 同步 | 条件等待原语，补 Zafiro wait_mode 只有"稳定/延时"两档的缺口 |
| skills_inspect_github / skills_install_from_github | Skills 渠道 | Zafiro 本地 skill 已有，缺 GitHub 发现/安装 |
| read_image | 文件视觉 | 本地图片路径附加给模型。实现前需确认 App 内模型客户端是否支持 image content |

## 3. 剔除清单与理由

**shell 可达（terminal 已覆盖，16 个）**：`set_device_state`（svc）、`app_state_control`（pm/am）、`set_setting`/`get_setting`（settings）、`get_logcat`（logcat）、`device_status`/`network_info`/`top_memory_apps`/`top_storage_apps`（dumpsys/cat/top）、`media_control`（cmd media_session dispatch）、`set_volume`（cmd media_session volume）、`wifi_credentials`（cat WifiConfigStore.xml）、`press_key`（input keyevent，screen_operation_shell 已含）、`open_system_panel`（am start panel intent）、`run_command`/`list_directory`/`read_file`/`write_file`（terminal 等价）。

**厂商绑定或方案投机（11 个）**：ColorOS 组（`list_alarms`/`list_active_timers`/`search_coloros_notes`/`search_coloros_recordings`/`search_recording_summaries`/`search_coloros_memories`/`search_saved_places`/`search_personal_orders`）、`search_qq_chat_images`、`search_wechat_chat_images`、`search_clipboard_history`（IME 绑定）、`get_health_summary`（健康 Provider 各厂商实现不一）。

**低价值/边界（3 个）**：`get_clipboard`/`set_clipboard`（root 下 cmd clipboard 可达）、`get_device_environment`（多来源聚合读）、`observe_screen` 拆分（与 screen_operation 的 read 概念等价）。

## 4. 落地顺序建议

1. set_alarm / set_timer（无新增权限）
2. browser_use（能力空缺最大）
3. 通知读取 2 个（通知使用权）
4. 个人数据检索 10 个（各项对应权限）
5. 其余低优先补强

---

## 参考

- Eta 源码：`/tmp/eta/app/src/main/kotlin/fuck/andes/agent/model/Agent*ToolCatalog.kt`