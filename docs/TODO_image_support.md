# TODO_image_support（图片支持）

## 已完成（2026-09，feat/image-support-ii 分支）

- **协议层图片链路**：view_image 工具 → 工具结果带图 → 各协议编码（OpenAI
  Responses `input_image` / Chat Completions 拆独立 user 消息 / Anthropic
  tool_result 内嵌 image 块）。Gemini 支持已整体移除。
- **MCP 多图**：`ToolCallOutcome.Success.images: List`（原单图字段已删）。
  McpExecutor 收集全部图片；save 失败转文本注记不毁结果；saver 为 null
  （图片功能未启用）维持静默丢弃。三协议逐张降级：单张 load 失败 →
  `[image omitted]` 注记进文本，成功照发（共享 helper `loadToolImages()`）。
  端到端无多图 MCP 服务器实测，逻辑层由单测覆盖（8 个用例）。
- **统一 ingest 管线**：`ImageCodec`（agent-runtime）+ `ImageFormat`（纯逻辑层）。
  三入口（file / content URI / base64）汇聚：魔数校验 → 尺寸收缩（长边 ≤1600px
  且 ≤1.5MP）→ JPEG q80 重编码（alpha 拍白底）→ sha256 命名落盘
  `filesDir/Zafiro/images/`（私有目录，零权限）。SVG 经 coil-resvg 光栅化。
- **错误模型**：`IngestError` sealed（FileNotFound / EmptyContent / TooLarge /
  UnsupportedFormat / DecodeFailed / IoFailed / SvgRenderFailed），view_image
  映射为结构化错误码回喂 Agent。
- **护栏**：读侧 ≤12MB；`ImageLoader.load` / `ChatProtocol.buildRequest` 已
  suspend 化（IO dispatcher）；`ImageSaver` 接受 data URL 并剥前缀。
- **支持 supportsImages 已接入开关**：provider 设置页「视觉模型」toggle
  （默认关）→ SavedLlmConfig（codec `supports_images` key，旧文档缺省 false）
  → RuntimeLlmConfig → LLMController `imageLoader != null && config.supportsImages`。
  五语言齐全。
- **用户图片附件 UI（本轮）**：composer 加号（photo picker 无权限）→
  `UserImageSaver.ingestFromUri`（ingest 落盘 + 预览 dataURL）→
  `pendingImages` → 图片条（composer 上方 8dp，同宽）→ send 时移入
  `turn.images` 渲染 120dp 图片卡（G2 圆角，外层同圆角 clip）。发送链路
  未接（见下）。VM 状态机 6 单测。纯图片 send 只进 UI 历史不发流。
- **composer 双态（需求外）**：紧凑态单行 ↔ 展开态（2..7 行 + 按钮行）；
  LiquidTextField 新增 leadingContent / expandedLayout /
  contentVerticalAlignment / onTextLayout。光标色随主题 primary（原固定
  黑色深色模式不可见）；hint 隐藏条件 isEmpty（任意输入即隐藏）；
  空文本 user bubble（纯图片 turn）不渲染。
- OpenAI Responses `function_call_output.output` 变体名已调研确认：`input_text`
  / `input_image` 与官方 spec 一致，无需修改（详见
  docs/research_fn_call_output_variants.md）。

## 待办（未排期）

### Okia.send() 图片参数（UI 链路下一步）

用户侧 UI 已就位（选图 → ingest → pendingImages → turn.images 展示），但
`Okia.send()` 只收 `text: String`，图片未发到模型。需给 send() 增加图片参数
或新 API，构造带 `ContentBlock.Image` 的 `Message.User`；三协议 `userContent()`
的图片分支已实现但是死代码，send() 接通后需补多图支持（当前只取 first）。
纯图片发送（无文本）当前只进 UI 历史不发流，send() 接图后去掉这个短路。

### UI 图片卡交互

图片卡点击事件（点开大图 / 默认图片查看器）未做，代码留 TODO。用户消息
图片卡（120dp）与 composer 待发条（60dp）共用 `HomeChatImageCard`/`HomeChatImageRow`。

### 会话持久化补图

`ConversationFormatter.toHomeTurns` 未读 `Message.User` 的 Image 块，恢复会话
后图片丢失；草稿（draftText）只存文本，pendingImages 跨进程不恢复。

### 权限请求 / 管理

当前权限只在 manifest 声明（READ_MEDIA_IMAGES 等），未做运行时请求 / 管理 UI。
仅服务一个未来场景：view_image 直读共享存储任意路径（/sdcard）。ingest 即转存
私有目录的策略下，图片链路与权限解耦，本项可无限期推迟。

## 备注

- 历史中含图片路径的工具结果，每次后续请求都会重读文件并重发 base64，
  token 随图片数累积。Zafiro 走 pi 路线（每轮重发）；Eta 的选择是从持久
  会话剔除图片只留文本占位，两种都成立，成本特性不同。
- 测试现状：`ImageFormat`（纯逻辑）22 个单测；`ImageCodec`（Bitmap/IO 胶水）
  无单测，已经真机验证——胶水层 Robolectric 测试拦截力存疑，出现真实回归时
  再为修 bug 补测。MCP 多图 / 协议多图编码 8 个单测（okia 模块）。
- 已知既有失败：`LLMControllerRefreshSkillTest.stream_reusesPreviousSnapshotWhen
  SkillListFails` 在有网环境挂死（测试对 example.com 发真实请求），main 上
  即失败，与图片工作无关，已拍板 ignore。
