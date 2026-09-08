# PRD：Zafiro 权限管理器（PermissionManager）

## 背景

权限获取逻辑散落多处：`App.grantOverlayPermissionViaRoot`（裸 `su -c`）、`AccessibilityController.ensureService`（手写 root→shizuku→跳设置三连）、`NotificationPermissionGate`（独立 object）。新增权限会继续复制这套模式。

## 目标

1. **统一入口**：全应用权限的查询与申请走 PermissionManager，业务方不直接碰 `Settings.canDrawOverlays` / `su -c` / `ActivityResultLauncher`。
2. **降级链显式化**：`scope(Channel...)` 声明通道优先级，按序尝试，首个 GRANTED 即成功，任何失败继续下一环，链尽则失败。引擎对失败原因不做特判——"用户 deny 后不再继续"由调用方省略后续通道表达。
3. **版本差异一等公民**：通道声明 `minSdk`，引擎统一把门；不支持的通道记 `UNAVAILABLE` 并降级，永不崩溃。
4. **无 UI 进程支持**：宿主侧只注册 shell 类通道，`SYSTEM_DIALOG` / `JUMP_SETTINGS` 未 bind Activity 时报 `UNAVAILABLE`。
5. **回调转同步**：通道内部用 `suspendCancellableCoroutine`（参考 libterm `ShizukuPrivilegeAuthorizer`），对外提供挂起式与阻塞式 API。

## 非目标

- 并发请求单飞去重（各试各的链，通道实现保证幂等）
- 权限撤销监听/推送（按需 `status()` 查询）
- 授权重试机制（调用方重新调 `withPermission` 即重试）
- 合流 libterm（战略迁移完成前允许两套 provider 共存：permission-manager 自带
  shizuku/root 实现，libterm 保持不动；迁移完成后再抽公共模块或改 libterm）
- 保留 `NotificationPermissionGate`（实现时删除收编）

## 契约

```kotlin
enum class Permission { ROOT, SHIZUKU, NOTIFICATION, OVERLAY, ACCESSIBILITY }

enum class Channel { ROOT_SHELL, SHIZUKU, SYSTEM_DIALOG, JUMP_SETTINGS }

/** UNKNOWN = 无法静默得知（如 root 嗅探会拉起授权），非成功也非失败 */
enum class PermissionState { GRANTED, DENIED_BY_USER, UNAVAILABLE, FAILED, UNKNOWN }

@JvmInline value class MinSdk(val api: Int)
// 版本判定不入 MinSdk（保持引擎纯 Kotlin、设备 API 可注入测试）；
// 引擎构造时传入 currentApi，统一比对 minSdk

data class Attempt(val permission: Permission, val channel: Channel,
                   val state: PermissionState, val detail: String? = null)

data class PermissionResult(val permission: Permission,
                            val finalState: PermissionState, val attempts: List<Attempt>)

interface ChannelHandler {
    val channel: Channel
    val minSdk: MinSdk                    // 实现类标 @RequiresApi，lint NewApi(error) 编译期兜底

    /**
     * 永远静默：不弹窗、不跳页、不写。
     * 真实状态优先；无法静默得知（如 root 嗅探会拉授权）返回 UNKNOWN。
     * 对本 handler 管不了的 permission 必须返回 UNAVAILABLE 而非 FAILED。
     */
    fun status(permission: Permission): PermissionState

    /**
     * 契约：必须在状态确定后返回，不允许“发射后不管”。
     * - SYSTEM_DIALOG：弹窗回调返回时确定结果
     * - JUMP_SETTINGS：launch intent → 挂起等 Activity resume → 复查一次 status() 后确定结果
     * - shell 通道：命令 exit code 校验后确定结果
     * Activity 销毁导致挂起被取消 = 本次请求无结果（引擎将 CancellationException
     * 原样上抛，不记为 FAILED），由下次 status() 兜底。
     */
    suspend fun request(permission: Permission): PermissionState
}

interface PermissionManager {
    fun bind(activity: Activity)          // onDestroy 必须 unbind，防泄漏
    fun unbind()
    suspend fun status(permission: Permission): PermissionState
    fun scope(vararg channels: Channel): ScopeBuilder
}

interface ScopeBuilder {
    fun withPermission(permission: Permission, onResult: (PermissionResult) -> Unit)
    fun withPermissionBlocking(permission: Permission): PermissionResult
}
```

## 引擎语义

- 尝试顺序 = `scope()` 传入顺序。
- 每环：`minSdk.supported == false` → `UNAVAILABLE`（detail 注明 API 要求）→ 下一环；否则 `request()`，结果原样入 `attempts`。
- 首个 `GRANTED` 终止；`UNKNOWN` 视为未成功，继续下一环；链尽返回，`finalState` 取最后一环（可能为 UNKNOWN）。
- `status()` 聚合：任一 handler 报 GRANTED → GRANTED；否则取任一真实状态（DENIED_BY_USER/UNAVAILABLE）；全为 UNKNOWN → UNKNOWN。

### status() 真实状态来源（Context 注入给 handler）

| Permission | 静默查询方式 | 结果映射 |
|---|---|---|
| ROOT | `Shell.isAppGrantedRoot()`：已建 shell 给真实值；未建 shell 返回 null（建 shell 即拉授权，不可静默） | true→GRANTED / false→DENIED_BY_USER / null→UNKNOWN |
| SHIZUKU | `Shizuku.pingBinder()` + `checkSelfPermission()`，异常一律 UNAVAILABLE | binder 死/抛异常→UNAVAILABLE / 已授权→GRANTED / 未授权→DENIED_BY_USER |
| OVERLAY | `Settings.canDrawOverlays(context)` | GRANTED / DENIED_BY_USER |
| ACCESSIBILITY | 查 enabled_accessibility_services 是否含本应用服务（ComponentName 归一化比较，短名与全限定名都认） | GRANTED / DENIED_BY_USER |
| NOTIFICATION | `checkSelfPermission(POST_NOTIFICATIONS)`；<33 恒 GRANTED | GRANTED / DENIED_BY_USER |

### Shizuku binder 到达机制（真机验证结论）

binder 由 Shizuku server 在应用启动后异步推送（`sendBinder`），无法通过
`contentResolver.call(getBinder)` 主动要（extras 传 null 时 provider 直接返回空）。
因此 `status()` 只上报当前快照（binder 不在即 UNAVAILABLE，不阻塞）；
`request()` 用 sticky 监听等 binder 最长 8 秒，到不了才报 UNAVAILABLE。
授权 requestCode 必须自增生成并与监听配对，超时后复查一次 `checkSelfPermission()`
避免回调迟到误判拒绝。

## 通道与默认链

| Permission | 默认链 | 说明 |
|---|---|---|
| OVERLAY | ROOT_SHELL → SHIZUKU → JUMP_SETTINGS | shell 执行 `appops set ... SYSTEM_ALERT_WINDOW allow` |
| ACCESSIBILITY | ROOT_SHELL → SHIZUKU → JUMP_SETTINGS | shell 写 `settings put secure enabled_accessibility_services`（收编 AccessibilityController 逻辑） |
| NOTIFICATION | SYSTEM_DIALOG → JUMP_SETTINGS | minSdk 33；<33 恒 GRANTED |
| ROOT / SHIZUKU | 自身对应通道 | 能力型目标，复用 libterm 授权检查 |

## 模块归属

```
libs/permission-manager/   # 新模块，与 libterm 平级
  依赖: libsu-core、shizuku-api/provider（独立实现，与 libterm 共存）
被依赖: app、agent-runtime（宿主侧后续接入）
```

## 版本策略

- minSdk 26，与 app/libs 现状一致。
- 版本分叉只存在于 handler 内部与 `minSdk` 声明；引擎侧零 `SDK_INT` 判断。
- handler 实现标注 `@RequiresApi`，lint NewApi（error 级）作为编译期兜底。

## 验收

1. `NotificationPermissionGate` 删除，通知申请走 PermissionManager，行为不变。
2. `App.grantOverlayPermissionViaRoot` 删除，悬浮窗授权走 PermissionManager，`handleBackgroundConfirmation` 改调 `withPermissionBlocking`。
3. `AccessibilityController.ensureService` 降级逻辑改调 PermissionManager，`attempts` 用于拼装给 LLM 的报错文案。
4. JUMP_SETTINGS 通道：跳设置 → 返回后复查一次 status()，返回真实结果，符合 request() 契约。
5. 单测：FakeChannelHandler 覆盖链语义（成功短路、UNAVAILABLE 降级、DENIED 继续、链尽失败）与 minSdk 门槛。
