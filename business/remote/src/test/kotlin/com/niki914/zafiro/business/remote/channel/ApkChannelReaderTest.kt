// 保护：签名块渠道和调试包规则读错时，发布包会被记成别的渠道，或在坏 APK 上崩溃
package com.niki914.zafiro.business.remote.channel

import com.niki914.zafiro.api.telemetry.TelemetryContract
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApkChannelReaderTest {
    private val samples: Map<String, ByteArray> by lazy { writerSamples() }

    @Test
    fun `writer output with padding reads back as github`() {
        assertEquals(TelemetryContract.CHANNEL_GITHUB_NAME, ApkChannelReader.readStampedName(samples.getValue("github")))
        val file = File.createTempFile("channel-github", ".apk")
        file.writeBytes(samples.getValue("github"))
        assertEquals(
            TelemetryContract.CHANNEL_GITHUB_NAME,
            ApkChannelReader.currentChannel(isDebug = false, apk = file),
        )
    }

    @Test
    fun `channel pair is found when the signing block sits before a long central directory`() {
        val eocd = eocdOffset(samples.getValue("github"))
        val gap = ByteArray(70_000) { 'A'.code.toByte() }
        val grown = samples.getValue("github").copyOfRange(0, eocd) + gap +
            samples.getValue("github").copyOfRange(eocd, samples.getValue("github").size)
        assertEquals(TelemetryContract.CHANNEL_GITHUB_NAME, ApkChannelReader.readStampedName(grown))
    }

    @Test
    fun `pattern legal names are returned even when they are not github or xposed`() {
        assertEquals("a".repeat(32), ApkChannelReader.readStampedName(samples.getValue("long32")))
        assertEquals("coolapk", ApkChannelReader.readStampedName(samples.getValue("coolapk")))
        assertEquals(TelemetryContract.CHANNEL_XPOSED_NAME, ApkChannelReader.readStampedName(samples.getValue("xposed")))
    }

    @Test
    fun `non canonical json illegal names and structural damage read as absent`() {
        val absent = listOf("spaced", "extra", "unicode", "bad", "over33", "two", "unsigned")
        val present = absent.filter { ApkChannelReader.readStampedName(samples.getValue(it)) != null }
        assertEquals(emptyList(), present)
    }

    @Test
    fun `zip64 locator is absent even when a channel pair is in the block`() {
        val apk = samples.getValue("xposed").copyOf()
        val at = eocdOffset(apk) - 20
        apk[at] = 0x50
        apk[at + 1] = 0x4b
        apk[at + 2] = 0x06
        apk[at + 3] = 0x07
        assertNull(ApkChannelReader.readStampedName(apk))
    }

    @Test
    fun `legacy stamp is readable but is not a release current channel`() {
        assertEquals(
            TelemetryContract.CHANNEL_LEGACY_NAME,
            ApkChannelReader.readStampedName(samples.getValue("legacy")),
        )
        val file = File.createTempFile("channel-legacy", ".apk")
        file.writeBytes(samples.getValue("legacy"))
        assertEquals(
            TelemetryContract.CHANNEL_UNKNOWN_NAME,
            ApkChannelReader.currentChannel(isDebug = false, apk = file),
        )
    }

    @Test
    fun `debug build reports dev without opening the apk`() {
        val missing = File(File.createTempFile("channel-missing", ".apk").parentFile, "no-such-channel.apk")
        assertEquals(
            TelemetryContract.CHANNEL_DEV_NAME,
            ApkChannelReader.currentChannel(isDebug = true, apk = missing),
        )
    }

    @Test
    fun `debug build reports dev even when the stamp is github`() {
        val file = File.createTempFile("channel-debug", ".apk")
        file.writeBytes(samples.getValue("github"))
        assertEquals(
            TelemetryContract.CHANNEL_DEV_NAME,
            ApkChannelReader.currentChannel(isDebug = true, apk = file),
        )
    }

    @Test
    fun `unreadable apk is unknown and does not throw`() {
        assertNull(ApkChannelReader.readStampedName(byteArrayOf()))
        val directory = kotlin.io.path.createTempDirectory("channel-dir").toFile()
        assertNull(ApkChannelReader.readStampedName(directory))
        assertEquals(
            TelemetryContract.CHANNEL_UNKNOWN_NAME,
            ApkChannelReader.currentChannel(isDebug = false, apk = directory),
        )
    }
}

private fun writerSamples(): Map<String, ByteArray> {
    val process = ProcessBuilder("python3", "-c", WRITER)
        .directory(repoRoot())
        .start()
    val errors = StringBuilder()
    val errorThread = Thread { errors.append(process.errorStream.reader().readText()) }
    errorThread.start()
    val output = process.inputStream.readBytes()
    val exit = process.waitFor()
    errorThread.join()
    if (exit != 0) error(errors.toString())
    return output.decodeToString().lineSequence().filter { it.isNotBlank() }.associate { line ->
        val split = line.indexOf(' ')
        line.substring(0, split) to Base64.getDecoder().decode(line.substring(split + 1))
    }
}

private fun repoRoot(): File {
    var dir = File(System.getProperty("user.dir") ?: error("user.dir is unset"))
    while (true) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile ?: error("repo root not found from ${dir}")
    }
}

private fun eocdOffset(apk: ByteArray): Int {
    for (index in apk.size - 22 downTo 0) {
        if (apk[index] != 0x50.toByte() ||
            apk[index + 1] != 0x4b.toByte() ||
            apk[index + 2] != 0x05.toByte() ||
            apk[index + 3] != 0x06.toByte()
        ) {
            continue
        }
        val comment = (apk[index + 20].toInt() and 0xff) or ((apk[index + 21].toInt() and 0xff) shl 8)
        if (index + 22 + comment == apk.size) return index
    }
    error("fixture has no end of central directory")
}

private val WRITER = """
import base64, io, struct, sys, zipfile
sys.path.insert(0, "tools")
import apk_channel as channel

def zip_with(pairs, pad=False):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("readme.txt", "hello channel")
    raw = buf.getvalue()
    eocd = channel.find_eocd(raw)
    cd = struct.unpack_from("<I", raw, eocd + 16)[0]
    block = channel._build_block(pairs, pad=pad, previous_total=None)
    eocd_bytes = bytearray(raw[eocd:])
    struct.pack_into("<I", eocd_bytes, 16, cd + len(block))
    return raw[:cd] + block + raw[cd:eocd] + bytes(eocd_bytes)

v2 = 0x7109871A
samples = {
    "github": channel.transform(zip_with([(v2, b"signature-bytes")], pad=True), "github"),
    "xposed": zip_with([(v2, b"sig"), (channel.CHANNEL_ID, channel.channel_value("xposed"))]),
    "long32": zip_with([(channel.CHANNEL_ID, channel.channel_value("a" * 32))]),
    "coolapk": zip_with([(channel.CHANNEL_ID, channel.channel_value("coolapk"))]),
    "legacy": zip_with([(channel.CHANNEL_ID, channel.channel_value("legacy"))]),
    "spaced": zip_with([(channel.CHANNEL_ID, b'{"channel": "github"}')]),
    "extra": zip_with([(channel.CHANNEL_ID, b'{"channel":"github","x":1}')]),
    "unicode": zip_with([(channel.CHANNEL_ID, '{"channel":"\\u0061"}'.encode("ascii"))]),
    "bad": zip_with([(channel.CHANNEL_ID, b'{"channel":"GitHub"}')]),
    "over33": zip_with([(channel.CHANNEL_ID, b'{"channel":"' + b"a" * 33 + b'"}')]),
    "two": zip_with([
        (channel.CHANNEL_ID, channel.channel_value("github")),
        (channel.CHANNEL_ID, channel.channel_value("xposed")),
    ]),
}
unsigned = io.BytesIO()
with zipfile.ZipFile(unsigned, "w") as archive:
    archive.writestr("readme.txt", "unsigned")
samples["unsigned"] = unsigned.getvalue()
for name, data in samples.items():
    sys.stdout.write(name + " " + base64.b64encode(data).decode("ascii") + "\n")
""".trimIndent()
