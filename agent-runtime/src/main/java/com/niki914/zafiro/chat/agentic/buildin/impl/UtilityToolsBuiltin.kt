package com.niki914.zafiro.chat.agentic.buildin.impl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Date arithmetic that models get wrong when they guess. Pure and tested. */
object DateMath {
    fun describe(z: ZonedDateTime): Map<String, String> = mapOf(
        "iso" to z.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "date" to z.toLocalDate().toString(), "time" to z.toLocalTime().withNano(0).toString(),
        "weekday" to z.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH), "zone" to z.zone.id, "week_of_year" to z.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString(),
        "epoch_ms" to z.toInstant().toEpochMilli().toString(),
    )

    fun parse(text: String, zone: ZoneId): ZonedDateTime? = runCatching {
        when {
            text.length <= 10 -> LocalDate.parse(text).atStartOfDay(zone)
            text.contains('+') || text.endsWith("Z") -> ZonedDateTime.parse(text).withZoneSameInstant(zone)
            else -> LocalDateTime.parse(text).atZone(zone)
        }
    }.getOrNull()

    fun nextWeekday(from: ZonedDateTime, day: DayOfWeek): ZonedDateTime {
        var d = from.plusDays(1)
        while (d.dayOfWeek != day) d = d.plusDays(1)
        return d
    }
}

class DatetimeBuiltin : BuiltinTool() {
    override val name = "datetime"
    override val defaultEnabled = true
    override val description = "Exact date and time facts: op=now (zone optional, e.g. Africa/Nairobi), op=add (from, days, hours), op=diff (from, to), op=next_weekday (weekday=MONDAY..). Use it instead of guessing dates."
    override val inputSchemaJson = """{"type":"object","properties":{"op":{"type":"string","enum":["now","add","diff","next_weekday"]},"zone":{"type":"string"},"from":{"type":"string"},"to":{"type":"string"},"days":{"type":"number"},"hours":{"type":"number"},"weekday":{"type":"string"}},"required":["op"]}"""
    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val a = parseScreenObject(request.argumentsJson) ?: return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "Arguments must be a JSON object.")
        val zone = runCatching { a.text("zone")?.let { ZoneId.of(it) } }.getOrNull() ?: ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val from = a.text("from")?.let { DateMath.parse(it, zone) } ?: now
        fun out(z: ZonedDateTime) = BuiltinToolResult.success(message = z.toString(), data = buildJsonObject { DateMath.describe(z).forEach { (k, v) -> put(k, v) } })
        return when (a.text("op")) {
            "now" -> out(now)
            "add" -> out(from.plusDays(a.long("days") ?: 0).plusHours(a.long("hours") ?: 0))
            "diff" -> {
                val to = a.text("to")?.let { DateMath.parse(it, zone) } ?: return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "to is required (ISO date or date-time).")
                BuiltinToolResult.success(message = "diff", data = buildJsonObject { put("days", ChronoUnit.DAYS.between(from, to)); put("hours", ChronoUnit.HOURS.between(from, to)); put("minutes", ChronoUnit.MINUTES.between(from, to)) })
            }
            "next_weekday" -> {
                val d = runCatching { DayOfWeek.valueOf(a.text("weekday").orEmpty().uppercase()) }.getOrNull() ?: return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "weekday must be MONDAY..SUNDAY.")
                out(DateMath.nextWeekday(from, d))
            }
            else -> BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "op must be now, add, diff or next_weekday.")
        }
    }
}

/** Pure helpers for card colours and sizes. */
object CardSpec {
    fun color(text: String?, fallback: Int): Int = runCatching { Color.parseColor(text!!.trim()) }.getOrDefault(fallback)
    fun clampSize(v: Int?, default: Int) = (v ?: default).coerceIn(300, 2400)
    /** Largest font size (descending from [start]) at which [fits] holds, never below [min]. */
    fun fitSize(start: Int, min: Int, fits: (Int) -> Boolean): Int { var s = start; while (s > min && !fits(s)) s -= 4; return s }
}

/**
 * Makes a text card image (quote card, announcement, pin background) as a PNG in the Files folder: gradient or flat background,
 * auto-fitted wrapped text, optional author line. Free, offline, and needs no image-generation service.
 */
class MakeCardBuiltin(private val rootProvider: suspend () -> File = { File(ContextProvider.await().applicationContext.filesDir, OutputPaths.ROOT_NAME) }) : BuiltinTool() {
    override val name = "make_card"
    override val defaultEnabled = true
    override val description = "Create a text card image (PNG) for social posts: text, optional author, width/height (default 1080x1350, use 1080x1080 for square, 1000x1500 for Pinterest), bg and bg2 colours (#RRGGBB, two = gradient), fg colour, path (default cards/card-<time>.png). Saved to the Files page; returns the link."
    override val inputSchemaJson = """{"type":"object","properties":{"text":{"type":"string"},"author":{"type":"string"},"width":{"type":"number"},"height":{"type":"number"},"bg":{"type":"string"},"bg2":{"type":"string"},"fg":{"type":"string"},"path":{"type":"string"}},"required":["text"]}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val a = parseScreenObject(request.argumentsJson) ?: return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "Arguments must be a JSON object.")
        val text = a.text("text") ?: return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "text is required.")
        val w = CardSpec.clampSize(a.int("width"), 1080); val h = CardSpec.clampSize(a.int("height"), 1350)
        val root = rootProvider().also { it.mkdirs() }
        val rel = a.text("path") ?: "cards/card-${System.currentTimeMillis()}.png"
        val file = OutputPaths.resolve(root, rel)?.takeIf { it.extension.equals("png", true) } ?: return BuiltinToolResult.failure(code = "INVALID_PATH", message = "path must be a safe relative .png path.")
        val bg = CardSpec.color(a.text("bg"), Color.parseColor("#1F2A44")); val bg2 = CardSpec.color(a.text("bg2"), bg); val fg = CardSpec.color(a.text("fg"), Color.WHITE)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), bg, bg2, Shader.TileMode.CLAMP) })
            val margin = (w * 0.1f).toInt(); val boxW = w - 2 * margin; val boxH = (h * 0.7f).toInt()
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
            fun layout(size: Int): StaticLayout { paint.textSize = size.toFloat(); return StaticLayout.Builder.obtain(text, 0, text.length, paint, boxW).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build() }
            val size = CardSpec.fitSize(w / 9, 28) { layout(it).height <= boxH }
            val l = layout(size)
            c.save(); c.translate(margin.toFloat(), (h - l.height) / 2f - (if (a.text("author") != null) 30f else 0f)); l.draw(c); c.restore()
            a.text("author")?.let { au ->
                paint.textSize = (size * 0.5f).coerceAtLeast(26f); paint.typeface = Typeface.SANS_SERIF; paint.alpha = 200
                c.drawText("— $au", margin.toFloat(), (h + l.height) / 2f + paint.textSize * 1.4f, paint)
            }
            file.parentFile?.mkdirs()
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bmp.recycle() }
        val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
        return BuiltinToolResult.success(message = "Saved $relative. Show the owner this link: ${OutputPaths.link(file.name, relative)}", data = buildJsonObject { put("path", relative); put("link", OutputPaths.link(file.name, relative)); put("width", w); put("height", h) })
    }
}
