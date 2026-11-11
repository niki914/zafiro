package com.tricreta.zafiro.autonomy.reliability

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Manufacturer-specific steps that keep a background app alive. No API can set these: Tecno/Infinix/itel (Transsion HiOS/XOS),
 * Samsung, Xiaomi, Oppo/Vivo/Realme/OnePlus and Huawei each run their own battery manager on top of Android's.
 * The step texts come from general knowledge of those skins and are UNVERIFIED on specific models; the screen says so and the
 * self-test ("Self-test in 1 min") is the real proof for a given phone.
 */
data class OemAdvice(
    val vendorLabel: String,
    val steps: List<String>,
    /** Package of the vendor's own manager app to open, if it exists on this phone. */
    val managerPackage: String?,
)

object OemGuide {

    private val generic = listOf(
        "Settings → Apps → Zafiro → Battery → choose \"Unrestricted\" (or turn off battery optimisation).",
        "Keep the phone's battery saver / ultra power saving OFF while you rely on scheduled tasks.",
    )

    fun adviceFor(manufacturer: String, brand: String): OemAdvice {
        val key = "${manufacturer.lowercase()} ${brand.lowercase()}"
        return when {
            listOf("tecno", "infinix", "itel", "transsion").any { key.contains(it) } -> OemAdvice(
                vendorLabel = "Tecno / Infinix / itel (HiOS / XOS)",
                steps = listOf(
                    "Open Phone Master → App management (or Settings → Apps) → Auto-start / Startup manager → allow Zafiro.",
                    "Settings → Battery → App battery management → Zafiro → \"No restrictions\".",
                    "Open the Recent apps screen and LOCK Zafiro's card (pull the card down or tap its lock icon) so the cleaner skips it.",
                ) + generic,
                managerPackage = "com.transsion.phonemaster",
            )
            key.contains("samsung") -> OemAdvice(
                vendorLabel = "Samsung (One UI)",
                steps = listOf(
                    "Settings → Battery and device care → Battery → Background usage limits.",
                    "Add Zafiro to \"Never sleeping apps\" and make sure it is NOT in \"Sleeping apps\" or \"Deep sleeping apps\".",
                    "Turn off \"Put unused apps to sleep\" (or exclude Zafiro) under the same menu.",
                ) + generic,
                managerPackage = "com.samsung.android.lool",
            )
            listOf("xiaomi", "redmi", "poco").any { key.contains(it) } -> OemAdvice(
                vendorLabel = "Xiaomi / Redmi / Poco (MIUI / HyperOS)",
                steps = listOf(
                    "Security app → Permissions → Autostart → enable Zafiro.",
                    "Settings → Apps → Zafiro → Battery saver → \"No restrictions\".",
                    "Recent apps → long-press Zafiro's card → Lock.",
                ) + generic,
                managerPackage = "com.miui.securitycenter",
            )
            listOf("oppo", "realme", "oneplus", "vivo", "iqoo").any { key.contains(it) } -> OemAdvice(
                vendorLabel = "Oppo / Realme / OnePlus / Vivo",
                steps = listOf(
                    "Settings → Battery → App battery management (or Background power consumption) → Zafiro → Allow background activity.",
                    "Settings → Apps → Auto-launch (or Startup manager) → allow Zafiro.",
                    "Recent apps → lock Zafiro's card.",
                ) + generic,
                managerPackage = null,
            )
            key.contains("huawei") || key.contains("honor") -> OemAdvice(
                vendorLabel = "Huawei / Honor",
                steps = listOf(
                    "Settings → Battery → App launch → Zafiro → turn off \"Manage automatically\" and enable Auto-launch, Secondary launch and Run in background.",
                ) + generic,
                managerPackage = null,
            )
            else -> OemAdvice(vendorLabel = "Your phone", steps = generic, managerPackage = null)
        }
    }

    fun adviceForThisPhone(): OemAdvice = adviceFor(android.os.Build.MANUFACTURER.orEmpty(), android.os.Build.BRAND.orEmpty())

    /** Opens the vendor's manager app if installed, otherwise Zafiro's own app-info page. Never throws. */
    fun openSettings(context: Context, advice: OemAdvice): Boolean {
        val pm = context.packageManager
        val vendor = advice.managerPackage?.let { pm.getLaunchIntentForPackage(it) }
        val intent = vendor ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
