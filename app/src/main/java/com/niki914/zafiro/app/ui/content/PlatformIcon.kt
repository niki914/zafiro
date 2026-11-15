package com.niki914.zafiro.app.ui.content

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/** Brand colour and glyph per platform. These are plain badges, not the official logos. */
internal data class Badge(val color: Color, val glyph: String)

internal fun badgeFor(platform: String): Badge = when (platform) {
    "substack" -> Badge(Color(0xFFFF6719), "S")
    "x" -> Badge(Color(0xFF000000), "X")
    "linkedin" -> Badge(Color(0xFF0A66C2), "in")
    "instagram" -> Badge(Color(0xFFD62976), "IG")
    "facebook" -> Badge(Color(0xFF1877F2), "f")
    "tiktok" -> Badge(Color(0xFF111111), "♪")
    "youtube" -> Badge(Color(0xFFFF0000), "▶")
    "reddit" -> Badge(Color(0xFFFF4500), "r/")
    "threads" -> Badge(Color(0xFF101010), "@")
    "pinterest" -> Badge(Color(0xFFE60023), "P")
    "email" -> Badge(Color(0xFFD93025), "M")
    "whatsapp" -> Badge(Color(0xFF25D366), "W")
    "jobhunt" -> Badge(Color(0xFF6D4AFF), "💼")
    "health" -> Badge(Color(0xFF2E9E6B), "♥")
    "toolsmith" -> Badge(Color(0xFF3B7DDD), "🛠")
    else -> Badge(Color(0xFF607D8B), "?")
}

/** The real app icon when that app is installed on this phone, otherwise a coloured badge. */
@Composable
fun PlatformIcon(platform: String, packageName: String, size: Dp = 48.dp) {
    val context = LocalContext.current
    val drawable: Drawable? = remember(packageName) { if (packageName.isBlank()) null else runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull() }
    val shape = RoundedCornerShape(size / 4)
    if (drawable != null) {
        val bmp = remember(drawable) { drawable.toBitmap(128, 128).asImageBitmap() }
        Image(bmp, contentDescription = platform, modifier = Modifier.size(size).clip(shape))
    } else {
        val b = badgeFor(platform)
        Box(Modifier.size(size).clip(shape).background(b.color), contentAlignment = Alignment.Center) {
            Text(b.glyph, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp)
        }
    }
}
