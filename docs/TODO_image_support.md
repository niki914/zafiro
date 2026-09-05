# TODO_image_support（图片支持 · 未来项）

当前状态（2026）：协议层图片链路已通（view_image 工具 → 工具结果带图 →
四种协议各自编码）。`supportsImages` 全局默认 **false**——尚无任何入口把它
置 true，图片功能整体休眠。

## Code Review 发现的问题

### 必改

- **Gemini 工具结果图片编码违法**：`GeminiProtocol.functionResponsePart()` 把
  `functionResponse` 和 `inlineData` 塞进同一个 part，但 Gemini API schema 中
  `Part` 是 oneof 结构，每个 part 只能有一个字段。会触发
  `Only one field may be set on a Part` 错误。需改为返回 `List<JsonObject>`，
  拆成两个独立 part：functionResponse part + inlineData part。

- **MCP 图片 base64 未 strip data URL 前缀**：`AndroidImageSaver.save()` 直接
  `Base64.decode(base64, ...)`，但 MCP 实现可能返回带
  `data:image/png;base64,` 前缀的字符串。Android 的 `Base64.decode` 遇到非
  base64 字符会抛异常或返回错码，导致图片落地失败、工具结果丢失。需先
  `substringAfter("base64,", base64)` 再 decode。

- **ImageLoader.load() 无文件大小护栏**：`AndroidImageLoader` 原字节直读，
  无尺寸上限、无 `withContext(Dispatchers.IO)`、无 mime 校验。大图（如 5MB
  PNG → ~6.7MB base64）可能触达网关请求体上限；且 `load()` 不是 suspend
  函数，在 `buildRequest` 内阻塞当前协程线程。需加文件大小上限（如 10MB）
  并把 `ImageLoader` 改为 `suspend fun interface`。

### 需调研

- **OpenAI Responses function_call_output.output 变体名**：当前用 `input_text` /
  `input_image`，这两个是 user content part 的变体名。`function_call_output.output`
  数组内的合法变体名是否也是 `input_text` / `input_image`，还是 `output_text` /
  `output_image`，需对照 OpenAI 官方 spec 进一步调研正确性。PR 实测通过
  可能是 DeepSeek 网关比较宽容，换到其他 gateway（OpenAI 官方）可能报错。

以下为未来待办，均未排期。

## UI - 用户消息气泡附加图片

用户消息气泡支持附加图片（相册 / 文件等入口，text_file / photos）。

前提：`Okia.send()` 只收 `text: String`，内部恒构造 `ContentBlock.Text`，
协议层 `userContent()` 的图片分支目前是死代码。需给 send() 增加图片参数或
新 API，才能构造带 `ContentBlock.Image` 的 `Message.User`。

## decode / filter

发送前图片处理：尺寸收缩 / 格式转换。

- svg 等 provider 不支持的格式需要转换
- 参照 pi `processImage` / Eta `AgentImageCodec`（尺寸上限 + 压缩）
- 当前 `AndroidImageLoader` 原字节直读，无任何护栏（大图 base64 可能触达
  provider 请求体上限）

## MCP 多图

`ToolCallOutcome.Success` 目前只承载单图（McpExecutor 对多图静默丢弃、
只取第一张）。需扩展为图片列表。

## UI - provider 设置开关

provider 修改页增加 supportsImages 开关。落地后由 LLMController 写入
OkiaConfig——这是把图片功能从休眠恢复的正式入口。

## 权限请求 / 管理

当前权限只在 manifest 声明（READ_MEDIA_IMAGES 等），未做运行时请求 / 管理 UI。

## 备注

- 历史中含图片路径的工具结果，每次后续请求都会重读文件并重发 base64，
  token 随图片数累积。Zafiro 暂走 pi 路线（每轮重发）；Eta 的选择是从持久
  会话剔除图片只留文本占位，两种都成立，成本特性不同。
