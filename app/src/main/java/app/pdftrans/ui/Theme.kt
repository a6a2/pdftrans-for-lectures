package app.pdftrans.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.pdftrans.FontKey

// Palette: ink on chalk. The primary is the same blue the translation is drawn in.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B3D91), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6ECF8), onPrimaryContainer = Color(0xFF0A2A66),
    secondary = Color(0xFF1B7F6B), onSecondary = Color.White,
    background = Color(0xFFF8F9FC), onBackground = Color(0xFF1D222B),
    surface = Color.White, onSurface = Color(0xFF1D222B),
    surfaceVariant = Color(0xFFEDF0F6), onSurfaceVariant = Color(0xFF5B6472),
    outline = Color(0xFFD5DBE6), outlineVariant = Color(0xFFE3E7EF),
    error = Color(0xFFB00020), errorContainer = Color(0xFFFBE9EC), onErrorContainer = Color(0xFF6B0014)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB8F5), onPrimary = Color(0xFF0A2A66),
    primaryContainer = Color(0xFF1B2A4A), onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = Color(0xFF5FD0B5), onSecondary = Color(0xFF00382D),
    background = Color(0xFF0D121C), onBackground = Color(0xFFE8ECF4),
    surface = Color(0xFF151C2A), onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF1C2536), onSurfaceVariant = Color(0xFF9AA5B8),
    outline = Color(0xFF2B3547), outlineVariant = Color(0xFF222B3C),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF3B1A1E), onErrorContainer = Color(0xFFFFDAD6)
)

/** Compose FontFamily from the bundled assets; falls back to the system font if they're missing. */
fun assetFamily(ctx: Context, regular: String, bold: String): FontFamily {
    val have = ctx.assets.list("fonts")?.toSet() ?: emptySet()
    if (regular !in have) return FontFamily.Default
    val fonts = mutableListOf(Font("fonts/$regular", ctx.assets, FontWeight.Normal))
    if (bold in have) fonts.add(Font("fonts/$bold", ctx.assets, FontWeight.Bold))
    return FontFamily(fonts)
}

fun previewFamily(ctx: Context, key: FontKey): FontFamily = assetFamily(ctx, key.regular, key.bold)

@Composable
fun PdfTransTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // Amiri (calligraphic naskh) for display, IBM Plex Sans Arabic for everything else.
    val display = remember { assetFamily(ctx, FontKey.AMIRI.regular, FontKey.AMIRI.bold) }
    val body = remember { assetFamily(ctx, FontKey.PLEX.regular, FontKey.PLEX.bold) }
    val type = Typography(
        headlineLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 48.sp),
        headlineMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 38.sp),
        titleLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 32.sp),
        titleMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 24.sp),
        bodyLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp)
    )
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = type, content = content)
}
