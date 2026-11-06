package app.pdftrans

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * FACING    original page, then its Arabic on a page of the same size (never overlaps)
 * SMART     Arabic under each paragraph, room inserted between paragraphs (falls back to FACING per page)
 * REPLACE   Arabic drawn over the original text blocks (falls back to FACING per page)
 * PARAGRAPH the document rebuilt as text: each paragraph, then its Arabic
 */
enum class Mode { FACING, SMART, REPLACE, PARAGRAPH }
enum class EngineKind { ONDEVICE, MICROSOFT, GOOGLE, LIBRE, MYMEMORY }
enum class Align { RIGHT, CENTER, LEFT }
enum class Stage { EXTRACT, TRANSLATE, RENDER }
enum class Screen { HOME, SETUP, RUN, SETTINGS }
enum class ModelStatus { UNKNOWN, MISSING, READY, DOWNLOADING }

/** Fonts are SIL-OFL, fetched into assets/fonts by scripts/fetch_fonts.sh. AUTO = Plex when small, Amiri when large. */
enum class FontKey(val label: String, val regular: String, val bold: String) {
    AUTO("Auto", "IBMPlexSansArabic-Regular.ttf", "IBMPlexSansArabic-Bold.ttf"),
    AMIRI("Amiri", "Amiri-Regular.ttf", "Amiri-Bold.ttf"),
    PLEX("IBM Plex Sans Arabic", "IBMPlexSansArabic-Regular.ttf", "IBMPlexSansArabic-Bold.ttf"),
    SCHEHERAZADE("Scheherazade New", "ScheherazadeNew-Regular.ttf", "ScheherazadeNew-Bold.ttf")
}

/** Error shown to the user. [code] is mapped to a localized sentence in ui/Strings.kt. */
class PdfTransException(val code: String, val detail: String = "") :
    Exception(if (detail.isEmpty()) code else "$code: $detail")

const val DEFAULT_COLOR = "#0b3d91"
val NAMED_COLORS = linkedMapOf(
    "black" to "#000000", "blue" to DEFAULT_COLOR, "red" to "#b00020", "green" to "#1b5e20",
    "purple" to "#4a148c", "orange" to "#e65100", "gray" to "#555555", "grey" to "#555555"
)

fun parseColor(value: String): String {
    var v = value.trim().lowercase()
    v = NAMED_COLORS[v] ?: v
    if (Regex("#?[0-9a-f]{6}").matches(v)) return if (v.startsWith("#")) v else "#$v"
    if (Regex("#?[0-9a-f]{3}").matches(v)) {
        v = v.trimStart('#')
        return "#" + v.map { "$it$it" }.joinToString("")
    }
    throw PdfTransException("bad_color", value)
}

data class Options(
    val mode: Mode = Mode.FACING,
    val font: FontKey = FontKey.AUTO,
    val size: Float? = null,          // upper bound for SMART/REPLACE, body size for FACING/PARAGRAPH
    val color: String? = null,        // null = automatic (picked per paragraph from the background)
    val bold: Boolean = false,
    val align: Align = Align.RIGHT,
    val pages: String? = null,        // "1-10,15,20-"  (1-based)
    val ocr: Boolean = true,
    val digits: Digits = Digits.LATIN,
    val minSize: Float = 8f           // Arabic is never drawn smaller than this (REPLACE allows 1pt less)
) {
    fun validate() {
        size?.let { if (it < 3f || it > 72f) throw PdfTransException("bad_size") }
        color?.let { parseColor(it) }
    }
}

class Report {
    var pagesTotal = 0
    var pagesDone = 0
    var segments = 0
    val ocrPages = mutableListOf<Int>()
    val scannedUnprocessed = mutableListOf<Int>()
    val fellBack = mutableListOf<Int>()        // SMART/REPLACE pages that were shown as facing pages instead
    var expandedPages = 0
    var failedSegments = 0                     // engine could not translate (stayed English)
    var weakSegments = 0                       // still doubtful after retries
    var retriedSegments = 0                    // fixed by another engine
    var engine = ""
    var switchedEngine = false
    var seconds = 0.0
}

/** "1-3,7,10-" -> zero-based page indices */
fun parsePages(spec: String?, count: Int): List<Int> {
    if (spec.isNullOrBlank()) return (0 until count).toList()
    val out = sortedSetOf<Int>()
    val range = Regex("""(\d*)\s*-\s*(\d*)""")
    for (raw in spec.split(",")) {
        val part = raw.trim()
        if (part.isEmpty()) continue
        val a: Int
        val b: Int
        val m = range.matchEntire(part)
        if (m != null) {
            a = (m.groupValues[1].ifEmpty { "1" }).toIntOrNull() ?: throw PdfTransException("bad_range", part)
            b = (m.groupValues[2].ifEmpty { count.toString() }).toIntOrNull() ?: throw PdfTransException("bad_range", part)
        } else if (part.all { it.isDigit() }) {
            a = part.toIntOrNull() ?: throw PdfTransException("bad_range", part)
            b = a
        } else throw PdfTransException("bad_range", part)
        if (a < 1 || b < a) throw PdfTransException("bad_range", part)
        for (i in a..minOf(b, count)) out.add(i - 1)
    }
    if (out.isEmpty()) throw PdfTransException("empty_pages", spec)
    return out.toList()
}

// ------------------------------------------------------------------ settings

data class AppSettings(
    val lang: String = if (Locale.getDefault().language == "ar") "ar" else "en",
    val theme: String = "system",              // system | light | dark
    val engine: EngineKind = EngineKind.ONDEVICE,
    val lastOnline: EngineKind = EngineKind.MICROSOFT,
    val mode: Mode = Mode.FACING,
    val font: FontKey = FontKey.AUTO,
    val size: Float? = null,
    val color: String? = null,
    val bold: Boolean = false,
    val align: Align = Align.RIGHT,
    val ocr: Boolean = true,
    val allowGoogle: Boolean = false,          // "unofficial free engines" (Microsoft Edge, Google) opt-in
    val cloudRetry: Boolean = false,           // on-device runs may re-do doubtful paragraphs online
    val digits: Digits = Digits.LATIN,
    val glossary: String = "",                 // lines of  term = ترجمة
    val libreUrl: String = "",
    val libreKey: String = "",
    val memoryEmail: String = ""
)

data class PdfInfo(val file: File, val name: String, val pages: Int, val sizeMb: Double, val scanned: Int)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("pdftrans", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumOf(key: String, def: E): E =
        runCatching { enumValueOf<E>(sp.getString(key, "") ?: "") }.getOrDefault(def)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            lang = sp.getString("lang", null) ?: d.lang,
            theme = sp.getString("theme", d.theme) ?: d.theme,
            engine = enumOf("engine", d.engine),
            lastOnline = enumOf("lastOnline", d.lastOnline),
            mode = enumOf("mode", d.mode),
            font = enumOf("font", d.font),
            size = sp.getFloat("size", -1f).takeIf { it > 0 },
            color = sp.getString("color", null),
            bold = sp.getBoolean("bold", d.bold),
            align = enumOf("align", d.align),
            ocr = sp.getBoolean("ocr", d.ocr),
            allowGoogle = sp.getBoolean("allowGoogle", false),
            cloudRetry = sp.getBoolean("cloudRetry", false),
            digits = enumOf("digits", d.digits),
            glossary = sp.getString("glossary", "") ?: "",
            libreUrl = sp.getString("libreUrl", "") ?: "",
            libreKey = sp.getString("libreKey", "") ?: "",
            memoryEmail = sp.getString("memoryEmail", "") ?: ""
        )
    }

    fun save(s: AppSettings) {
        sp.edit()
            .putString("lang", s.lang).putString("theme", s.theme)
            .putString("engine", s.engine.name).putString("lastOnline", s.lastOnline.name)
            .putString("mode", s.mode.name).putString("font", s.font.name)
            .putFloat("size", s.size ?: -1f).putString("color", s.color)
            .putBoolean("bold", s.bold).putString("align", s.align.name)
            .putBoolean("ocr", s.ocr).putBoolean("allowGoogle", s.allowGoogle)
            .putBoolean("cloudRetry", s.cloudRetry).putString("digits", s.digits.name)
            .putString("glossary", s.glossary)
            .putString("libreUrl", s.libreUrl).putString("libreKey", s.libreKey)
            .putString("memoryEmail", s.memoryEmail)
            .apply()
    }
}
