package com.niki914.zafiro.business.remote.profile

import android.app.ActivityManager
import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import com.niki914.zafiro.api.telemetry.TelemetryContract
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** 发送时能直接读到的设备信号。分档之前的原始值。 */
internal data class DeviceSignals(
    val apiLevel: Int,
    val systemLocale: Locale?,
    val manufacturer: String,
    val model: String,
    val abi: String,
    val totalMemBytes: Long,
    val smallestScreenWidthDp: Int,
    val densityDpi: Int,
    val timeZone: TimeZone,
)

/** schema §3 的 `device` 对象。没有 gms，也没有 rom_family。 */
internal data class DeviceProfile(
    val apiLevel: Int?,
    val lang: String?,
    val brand: String,
    val model: String,
    val abi: String,
    val ramGb: Int,
    val screenClass: String,
    val localeRegion: String?,
    val tzId: String,
    val tzOffsetMin: Int,
)

/**
 * 发送时采集设备画像。档位来自 [TelemetryContract]，不在这里另写一份数字。
 */
internal object DeviceProfileReader {
    private const val GIB = 1024L * 1024L * 1024L
    private val ramBuckets = csvInts(TelemetryContract.RAM_GB_BUCKETS)
    private val widthBuckets = csvInts(TelemetryContract.SCREEN_WIDTH_BUCKETS)
    private val densityBuckets = TelemetryContract.SCREEN_DENSITY_BUCKETS.split(',').map { item ->
        val name = item.substringBefore(':')
        val dpi = item.substringAfter(':').toInt()
        check(name.isNotEmpty() && ':' in item) { "screen density bucket `$item`" }
        DensityBucket(name, dpi)
    }.sortedBy { it.dpi }
    private val regionPattern = Regex("[A-Z]{${TelemetryContract.LIMIT_LOCALE_REGION_LENGTH}}")

    fun read(signals: DeviceSignals, nowMillis: Long): DeviceProfile {
        val locale = signals.systemLocale
        val language = if (locale == null || locale.language.isEmpty()) {
            null
        } else {
            boundedLanguageTag(locale.toLanguageTag(), TelemetryContract.LIMIT_LANG_LENGTH)
        }
        return DeviceProfile(
            apiLevel = signals.apiLevel,
            lang = language,
            brand = clip(signals.manufacturer, TelemetryContract.LIMIT_BRAND_LENGTH),
            model = clip(signals.model, TelemetryContract.LIMIT_MODEL_LENGTH),
            abi = signals.abi,
            ramGb = ramGb(signals.totalMemBytes),
            screenClass = screenClass(signals.smallestScreenWidthDp, signals.densityDpi),
            localeRegion = locale?.country
                ?.uppercase(Locale.ROOT)
                ?.takeIf(regionPattern::matches),
            tzId = signals.timeZone.id,
            tzOffsetMin = signals.timeZone.getOffset(nowMillis) / 60_000,
        )
    }

    fun read(context: Context, nowMillis: Long = System.currentTimeMillis()): DeviceProfile =
        read(signalsFrom(context), nowMillis)

    /**
     * 总内存字节按 1024³ 换成 GB，再向上取到不小于它的最小档。
     * 标称 8 GB 的机器系统读数约 7.4，向上才落回 8。超过最大档记最大档。
     */
    private fun ramGb(totalBytes: Long): Int {
        for (bucket in ramBuckets) {
            if (totalBytes <= bucket.toLong() * GIB) return bucket
        }
        return ramBuckets.last()
    }

    /** 最小宽度 dp 向下取档；小于最小档时用最小档。 */
    private fun widthBucket(widthDp: Int): Int {
        var chosen = widthBuckets.first()
        for (bucket in widthBuckets) {
            if (bucket <= widthDp) chosen = bucket
        }
        return chosen
    }

    /** 离 densityDpi 最近的一档；距离相等取较高档。 */
    private fun densityName(dpi: Int): String {
        var best = densityBuckets.first()
        var bestDistance = abs(dpi - best.dpi)
        for (bucket in densityBuckets.drop(1)) {
            val distance = abs(dpi - bucket.dpi)
            if (distance <= bestDistance) {
                best = bucket
                bestDistance = distance
            }
        }
        return best.name
    }

    private fun screenClass(widthDp: Int, densityDpi: Int): String =
        "sw${widthBucket(widthDp)}_${densityName(densityDpi)}"

    private fun clip(value: String, maxChars: Int): String =
        if (value.length <= maxChars) value else value.take(maxChars)

    private fun signalsFrom(context: Context): DeviceSignals {
        val configuration = context.resources.configuration
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        return DeviceSignals(
            apiLevel = Build.VERSION.SDK_INT,
            systemLocale = systemLocale(context),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            totalMemBytes = memory.totalMem,
            smallestScreenWidthDp = configuration.smallestScreenWidthDp,
            densityDpi = configuration.densityDpi,
            timeZone = TimeZone.getDefault(),
        )
    }

    /** 系统语言，不读应用内语言覆盖后的默认 Locale。 */
    private fun systemLocale(context: Context): Locale? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.systemLocales
            if (locales != null && !locales.isEmpty) return locales[0]
        }
        val systemLocales = Resources.getSystem().configuration.locales
        if (systemLocales.isEmpty) return null
        return systemLocales[0]
    }

    private fun csvInts(csv: String): List<Int> = csv.split(',').map { it.toInt() }.sorted()

    private data class DensityBucket(val name: String, val dpi: Int)
}
