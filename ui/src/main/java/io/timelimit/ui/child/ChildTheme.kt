package io.timelimit.ui.child

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.timelimit.ui.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Palette from docs/specification/ui-contract.md, "Визуальный язык".
@Immutable
data class ChildColors(
    val ground: Color,
    val surface: Color,
    val text: Color,
    val secondary: Color,
    val action: Color,
    val allowed: Color,
    val endingSoon: Color,
    val closed: Color,
    val refused: Color,
)

private val lightChildColors = ChildColors(
    ground = Color(0xFFF3F4F7), surface = Color(0xFFFFFFFF), text = Color(0xFF151924),
    secondary = Color(0xFF5E6475), action = Color(0xFF2C5BD8), allowed = Color(0xFF1E8A5A),
    endingSoon = Color(0xFFC9820A), closed = Color(0xFF6B5BD2), refused = Color(0xFFC2412D),
)

private val darkChildColors = ChildColors(
    ground = Color(0xFF0F1218), surface = Color(0xFF181C25), text = Color(0xFFE8EAF0),
    secondary = Color(0xFF9AA1B2), action = Color(0xFF7C98FF), allowed = Color(0xFF47C28A),
    endingSoon = Color(0xFFE7A93A), closed = Color(0xFF9C8FF0), refused = Color(0xFFEE7A64),
)

val LocalChildColors = staticCompositionLocalOf { lightChildColors }

@Composable
fun ChildTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkChildColors else lightChildColors
    val material = if (dark) darkColors(primary = colors.action, background = colors.ground, surface = colors.surface)
    else lightColors(primary = colors.action, background = colors.ground, surface = colors.surface)

    MaterialTheme(colors = material) {
        CompositionLocalProvider(LocalChildColors provides colors, content = content)
    }
}

@Composable
fun formatDuration(millis: Long): String {
    val minutes = (millis.coerceAtLeast(0) + 59_999) / 60_000
    val hours = (minutes / 60).toInt()
    val rest = (minutes % 60).toInt()

    return when {
        hours == 0 -> stringResource(R.string.child_minutes, rest)
        rest == 0 -> stringResource(R.string.child_hours, hours)
        else -> stringResource(R.string.child_hours_minutes, hours, rest)
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("dd.MM")

fun formatClock(time: Long): String = clockFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

@Composable
fun formatDay(time: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    return when (day) {
        today -> stringResource(R.string.child_day_today)
        today.plusDays(1) -> stringResource(R.string.child_day_tomorrow)
        else -> dateFormat.format(day)
    }
}

@Composable
fun AppIcon(packageName: String, title: String, size: Dp = 48.dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) { loadIcon(context, packageName) }
    val shape = RoundedCornerShape(size / 4)

    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(
            Modifier.size(size).clip(shape).background(LocalChildColors.current.secondary),
            contentAlignment = Alignment.Center
        ) {
            Text(title.take(1).uppercase(), color = Color.White, fontSize = (size.value / 2).sp)
        }
    }
}

private fun loadIcon(context: Context, packageName: String): Bitmap? = try {
    val drawable = context.packageManager.getApplicationIcon(packageName)
    val side = drawable.intrinsicWidth.coerceIn(1, 192)
    Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also {
        drawable.setBounds(0, 0, side, side)
        drawable.draw(Canvas(it))
    }
} catch (_: PackageManager.NameNotFoundException) {
    null
}
