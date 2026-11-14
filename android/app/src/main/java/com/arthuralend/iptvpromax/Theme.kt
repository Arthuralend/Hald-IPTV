package com.arthuralend.iptvpromax

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.SubcomposeAsyncImage

/** Colors from the app icon: graphite tile, orange cabinet, pale-blue screen. */
object C {
    val Orange = Color(0xFFF2800A)
    val OrangeDeep = Color(0xFFD96200)
    val OrangeSoft = Color(0x33F2800A)
    val Screen = Color(0xFFBDE8F8)
    val Bg = Color(0xFF17181A)
    val Rail = Color(0xFF1F2022)
    val Card = Color(0xFF2B2C2E)
    val CardFocused = Color(0xFF3A3B3E)
    val Text = Color(0xFFF2F2F2)
    val Dim = Color(0x99FFFFFF)
    val Scrim = Color(0xCC000000)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Orange, onPrimary = Color.White, secondary = C.OrangeDeep,
            background = C.Bg, surface = C.Card, onSurface = C.Text, onBackground = C.Text,
            border = C.Orange, surfaceVariant = C.Card,
        ),
        content = content,
    )
}

private val palette = listOf(C.Orange, C.OrangeDeep, Color(0xFF5C9EBF), Color(0xFF6B6E75))

fun initials(name: String): String {
    val words = name.replace(Regex("""\([^)]*\)|\[[^\]]*]"""), " ").split(Regex("\\s+"))
        .filter { it.isNotEmpty() && it[0].isLetterOrDigit() && !Regex("(?i)^(u?hd|fhd|4k|sd|orig|\\+?\\d{2,})$").matches(it) }
    return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "•" }
}

fun colorFor(name: String): Color {
    var h = 0
    name.forEach { h = (h * 31 + it.code) and 0xffff }
    return palette[h % palette.size]
}

/** Channel logo with a colored-initials fallback. */
@Composable
fun ChannelLogo(ch: Channel, size: Dp, modifier: Modifier = Modifier, radius: Dp = size / 5) {
    val shape = RoundedCornerShape(radius)
    val fallback = @Composable {
        Box(
            Modifier.fillMaxSize().clip(shape).background(Brush.linearGradient(listOf(colorFor(ch.name), colorFor(ch.name).copy(alpha = .7f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(ch.name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp)
        }
    }
    Box(modifier.then(Modifier.clip(shape)), contentAlignment = Alignment.Center) {
        if (ch.logo != null) {
            SubcomposeAsyncImage(
                model = ch.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                loading = { fallback() },
                error = { fallback() },
            )
        } else fallback()
    }
}

fun hhmm(sec: Long): String {
    val c = java.util.Calendar.getInstance(); c.timeInMillis = sec * 1000
    return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}
