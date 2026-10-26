package com.niki914.zafiro.business.remote.ledger

import com.niki914.zafiro.api.telemetry.CustomValue
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** `ledger.json` 与 schema §8 的字段一一对应。解析失败时返回 null，调用方不得覆盖原文件。 */
internal object LedgerJson {
    fun encode(state: LedgerState): String = encodeState(state).toString()

    fun decode(text: String): LedgerState? {
        return try {
            decodeState(JSONObject(text))
        } catch (_: JSONException) {
            null
        } catch (_: BadLedgerJson) {
            null
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun encodeState(state: LedgerState): JSONObject {
        return JSONObject()
            .put("v", state.v)
            .put("last_active", encodeLastActive(state.lastActive))
            .put("pages", encodePages(state.pages))
            .put("seen_events", encodeStrings(state.seenEvents))
            .put("envelope_custom", encodeCustom(state.envelopeCustom))
            .put("dropped_days", state.droppedDays)
            .put("send", encodeSend(state.send))
    }

    private fun decodeState(obj: JSONObject): LedgerState {
        val version = requireInt(obj, "v")
        if (version != LEDGER_FILE_VERSION) throw BadLedgerJson()
        return LedgerState(
            v = version,
            lastActive = decodeLastActive(require(obj, "last_active")),
            pages = decodePages(requireArray(obj, "pages")),
            seenEvents = decodeStrings(requireArray(obj, "seen_events")),
            envelopeCustom = decodeCustom(requireObject(obj, "envelope_custom")),
            droppedDays = requireInt(obj, "dropped_days"),
            send = decodeSend(requireObject(obj, "send")),
        )
    }

    private fun encodeLastActive(last: LastActive?): Any {
        if (last == null) return JSONObject.NULL
        return JSONObject()
            .put("day", last.day.toString())
            .put("version_code", last.versionCode)
    }

    private fun decodeLastActive(value: Any): LastActive? {
        if (value == JSONObject.NULL) return null
        val obj = value as? JSONObject ?: throw BadLedgerJson()
        return LastActive(parseDay(requireString(obj, "day")), requireInt(obj, "version_code"))
    }

    private fun encodePages(pages: List<DayPage>): JSONArray {
        val array = JSONArray()
        for (page in pages) {
            array.put(
                JSONObject()
                    .put("day", page.day.toString())
                    .put("day_key", page.dayKey)
                    .put("rev", page.rev)
                    .put("acked_rev", page.ackedRev)
                    .put("version_code", page.versionCode)
                    .put("prev_active_day", page.prevActiveDay?.toString() ?: JSONObject.NULL)
                    .put("prev_version_code", page.prevVersionCode ?: JSONObject.NULL)
                    .put("counts", encodeCounts(page.counts))
                    .put("counts_truncated", page.countsTruncated)
                    .put("firsts", encodeStrings(page.firsts))
                    .put("custom", encodeCustom(page.custom)),
            )
        }
        return array
    }

    private fun decodePages(array: JSONArray): List<DayPage> {
        val pages = ArrayList<DayPage>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: throw BadLedgerJson()
            pages.add(
                DayPage(
                    day = parseDay(requireString(obj, "day")),
                    dayKey = requireString(obj, "day_key"),
                    rev = requireInt(obj, "rev"),
                    ackedRev = requireInt(obj, "acked_rev"),
                    versionCode = requireInt(obj, "version_code"),
                    prevActiveDay = optionalDay(obj, "prev_active_day"),
                    prevVersionCode = optionalInt(obj, "prev_version_code"),
                    counts = decodeCounts(requireArray(obj, "counts")),
                    countsTruncated = requireInt(obj, "counts_truncated"),
                    firsts = decodeStrings(requireArray(obj, "firsts")),
                    custom = decodeCustom(requireObject(obj, "custom")),
                ),
            )
        }
        return pages
    }

    private fun encodeCounts(counts: List<PageCount>): JSONArray {
        val array = JSONArray()
        for (count in counts) {
            array.put(
                JSONObject()
                    .put("event", count.event)
                    .put("label", count.label ?: JSONObject.NULL)
                    .put("n", count.n)
                    .put("custom", encodeCustom(count.custom)),
            )
        }
        return array
    }

    private fun decodeCounts(array: JSONArray): List<PageCount> {
        val counts = ArrayList<PageCount>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: throw BadLedgerJson()
            counts.add(
                PageCount(
                    event = requireString(obj, "event"),
                    label = optionalString(obj, "label"),
                    n = requireInt(obj, "n"),
                    custom = decodeCustom(requireObject(obj, "custom")),
                ),
            )
        }
        return counts
    }

    private fun encodeSend(send: LedgerSend): JSONObject {
        return JSONObject()
            .put("last_ok_ms", send.lastOkMs ?: JSONObject.NULL)
            .put("next_retry_ms", send.nextRetryMs)
            .put("failures", send.failures)
    }

    private fun decodeSend(obj: JSONObject): LedgerSend {
        return LedgerSend(
            lastOkMs = optionalLong(obj, "last_ok_ms"),
            nextRetryMs = requireLong(obj, "next_retry_ms"),
            failures = requireInt(obj, "failures"),
        )
    }

    private fun encodeCustom(custom: Map<String, CustomValue>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in custom) {
            when (value) {
                is CustomValue.Str -> obj.put(key, value.value)
                is CustomValue.Num -> obj.put(key, value.value)
                is CustomValue.Bool -> obj.put(key, value.value)
            }
        }
        return obj
    }

    private fun decodeCustom(obj: JSONObject): Map<String, CustomValue> {
        val values = LinkedHashMap<String, CustomValue>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            values[key] = when (val value = obj.get(key)) {
                is String -> CustomValue.Str(value)
                is Boolean -> CustomValue.Bool(value)
                is Int -> CustomValue.Num(value)
                is Number -> CustomValue.Num(integralInt(value))
                else -> throw BadLedgerJson()
            }
        }
        return values
    }

    private fun encodeStrings(values: List<String>): JSONArray {
        val array = JSONArray()
        for (value in values) array.put(value)
        return array
    }

    private fun decodeStrings(array: JSONArray): List<String> {
        val values = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = array.opt(index)
            if (value !is String) throw BadLedgerJson()
            values.add(value)
        }
        return values
    }

    private fun optionalDay(obj: JSONObject, key: String): LocalDate? {
        val text = optionalString(obj, key) ?: return null
        return parseDay(text)
    }

    private fun optionalString(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) {
            if (!obj.has(key)) throw BadLedgerJson()
            return null
        }
        val value = obj.get(key)
        return value as? String ?: throw BadLedgerJson()
    }

    private fun optionalInt(obj: JSONObject, key: String): Int? {
        if (!obj.has(key)) throw BadLedgerJson()
        if (obj.isNull(key)) return null
        return requireInt(obj, key)
    }

    private fun optionalLong(obj: JSONObject, key: String): Long? {
        if (!obj.has(key)) throw BadLedgerJson()
        if (obj.isNull(key)) return null
        return requireLong(obj, key)
    }

    private fun require(obj: JSONObject, key: String): Any {
        if (!obj.has(key)) throw BadLedgerJson()
        return obj.get(key)
    }

    private fun requireObject(obj: JSONObject, key: String): JSONObject {
        if (!obj.has(key) || obj.isNull(key)) throw BadLedgerJson()
        return obj.optJSONObject(key) ?: throw BadLedgerJson()
    }

    private fun requireArray(obj: JSONObject, key: String): JSONArray {
        if (!obj.has(key) || obj.isNull(key)) throw BadLedgerJson()
        return obj.optJSONArray(key) ?: throw BadLedgerJson()
    }

    private fun requireString(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) throw BadLedgerJson()
        val value = obj.get(key)
        return value as? String ?: throw BadLedgerJson()
    }

    private fun requireInt(obj: JSONObject, key: String): Int {
        if (!obj.has(key) || obj.isNull(key)) throw BadLedgerJson()
        return integralInt(obj.get(key))
    }

    private fun requireLong(obj: JSONObject, key: String): Long {
        if (!obj.has(key) || obj.isNull(key)) throw BadLedgerJson()
        val value = obj.get(key)
        if (value !is Number) throw BadLedgerJson()
        val long = value.toLong()
        if (long.toDouble() != value.toDouble()) throw BadLedgerJson()
        return long
    }

    private fun integralInt(value: Any): Int {
        if (value !is Number) throw BadLedgerJson()
        val long = value.toLong()
        if (long.toDouble() != value.toDouble()) throw BadLedgerJson()
        if (long < Int.MIN_VALUE || long > Int.MAX_VALUE) throw BadLedgerJson()
        return long.toInt()
    }

    private fun parseDay(text: String): LocalDate = LocalDate.parse(text)
}

private class BadLedgerJson : Exception()
