package app.pdftrans

import android.content.Context
import android.graphics.Typeface

/**
 * Loads the bundled Arabic fonts for PDF output; falls back to Android's system Arabic font.
 * FontKey.AUTO picks IBM Plex Sans Arabic for small text (clearer at 8-11pt) and Amiri for larger text.
 */
class FontProvider(private val ctx: Context) {
    private val cache = HashMap<String, Typeface>()

    fun typeface(key: FontKey, bold: Boolean, size: Float = 12f): Typeface {
        val k = if (key == FontKey.AUTO) (if (size < 11.5f) FontKey.PLEX else FontKey.AMIRI) else key
        return cache.getOrPut("${k.name}/$bold") {
            try {
                Typeface.createFromAsset(ctx.assets, "fonts/" + if (bold) k.bold else k.regular)
            } catch (e: Exception) {
                Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }
}
