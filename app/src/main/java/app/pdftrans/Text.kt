package app.pdftrans

// Pure Kotlin (no Android imports) so everything in this file is unit-tested on the JVM.

enum class Digits { LATIN, ARABIC }

// ------------------------------------------------------------------ cleaning

private val LIGATURES = mapOf("\uFB00" to "ff", "\uFB01" to "fi", "\uFB02" to "fl", "\uFB03" to "ffi", "\uFB04" to "ffl")
private val DOT_LEADER = Regex("""(?:\s?\.){4,}|…{2,}|_{4,}|-{5,}""")
private val WS = Regex("\\s+")

fun cleanText(s: String): String {
    var t = s
    for ((k, v) in LIGATURES) t = t.replace(k, v)
    t = t.replace("\u00AD", "").replace("\u200B", "").replace('\u00A0', ' ')
    t = DOT_LEADER.replace(t, " ")          // table-of-contents leaders
    return WS.replace(t, " ").trim()
}

// ------------------------------------------------------ what is worth translating

private val LATIN_WORD = Regex("[A-Za-z]{2,}")
private val URL_OR_MAIL = Regex("""^(?:https?://\S+|www\.\S+|[\w.+-]+@[\w-]+\.[\w.-]+)$""")

/** False for page numbers, formulas, numeric tables, URLs-only and text that is already Arabic. */
fun needsTranslation(text: String): Boolean {
    if (!LATIN_WORD.containsMatchIn(text)) return false
    var latin = 0
    var arabic = 0
    var letters = 0
    var nonSpace = 0
    for (c in text) {
        if (!c.isWhitespace()) nonSpace++
        if (c in 'A'..'Z' || c in 'a'..'z') { latin++; letters++ }
        else if (c in '\u0600'..'\u06FF') { arabic++; letters++ }
        else if (c.isLetter()) letters++
    }
    if (latin <= arabic) return false
    if (letters < 0.45f * nonSpace) return false      // mostly digits and symbols: formula, data row
    if (URL_OR_MAIL.matches(text.trim())) return false
    return true
}

// ------------------------------------------------------------- sentence handling

private val SENT_BREAK = Regex("""[.!?]["”’')\]]*\s+(?=["“‘'(\[]?[A-Z0-9])""")
private val ABBREV = Regex(
    """(?:\b(?:Dr|Mr|Mrs|Ms|Prof|Fig|Figs|Eq|Eqs|No|Nos|vs|etc|al|cf|Inc|Ltd|Co|St|Jr|Sr|Vol|Sec|Ch|pp|approx|resp|ca|Dept|Univ|Ref|Refs)\.|\b[A-Z]\.|\b(?:e\.g|i\.e)\.)$"""
)

/** Splits English text into sentences without cutting after abbreviations such as "Fig." or "Dr.". */
fun splitSentences(text: String): List<String> {
    val out = ArrayList<String>()
    var start = 0
    for (m in SENT_BREAK.findAll(text)) {
        val end = m.range.first + m.value.trimEnd().length
        if (end <= start) continue
        val piece = text.substring(start, end)
        if (ABBREV.containsMatchIn(piece)) continue
        out.add(piece.trim())
        start = m.range.last + 1
    }
    if (start < text.length) out.add(text.substring(start).trim())
    return out.filter { it.isNotEmpty() }
}

private fun hardSplit(s: String, max: Int): List<String> {
    if (s.length <= max) return listOf(s)
    val out = ArrayList<String>()
    var rest = s
    while (rest.length > max) {
        var take: Int
        val comma = rest.lastIndexOfAny(charArrayOf(',', ';', ':'), max - 1)
        if (comma >= max / 2) {
            take = comma + 1
        } else {
            val space = rest.lastIndexOf(' ', max)
            take = if (space >= max / 4) space else max
        }
        if (take < 1) take = 1
        out.add(rest.substring(0, take).trim())
        rest = rest.substring(take).trim()
    }
    if (rest.isNotEmpty()) out.add(rest)
    return out
}

/** Packs consecutive sentences into chunks of at most [maxChars]: context for the engine, bounded size. */
fun packChunks(sentences: List<String>, maxChars: Int): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    for (s0 in sentences) {
        for (s in hardSplit(s0, maxChars)) {
            if (cur.isNotEmpty() && cur.length + 1 + s.length > maxChars) { out.add(cur.toString()); cur.setLength(0) }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
    }
    if (cur.isNotEmpty()) out.add(cur.toString())
    return out
}

// -------------------------------------------------- protected tokens and glossary

/** [tokens] hold what each placeholder turns back into (a URL, or the glossary's Arabic term). */
class Masked(val text: String, val tokens: List<String>)

private val PROTECT = Regex("""https?://\S+|www\.\S+|[\w.+-]+@[\w-]+\.[\w.-]+|\b[dD][oO][iI]:\s?10\.\S+""")
private val PLACEHOLDER = Regex("Z\\s?Q\\s?(\\d{1,3})\\s?Z")
private const val TRAIL = ".,;:)]}\"'"

class GlossaryEntry(val pattern: Regex, val arabic: String)

fun parseGlossary(raw: String): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    for (line in raw.lines()) {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) continue
        val i = l.indexOfFirst { it == '=' || it == '\t' || it == '→' || it == '|' }
        if (i <= 0) continue
        val k = l.substring(0, i).trim()
        val v = l.substring(i + 1).trim()
        if (k.isNotEmpty() && v.isNotEmpty()) m[k] = v
    }
    return m
}

fun compileGlossary(g: Map<String, String>): List<GlossaryEntry> =
    g.entries.sortedByDescending { it.key.length }.map {
        GlossaryEntry(
            Regex("(?<![A-Za-z0-9])" + Regex.escape(it.key) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE), it.value
        )
    }

fun mask(src: String, glossary: List<GlossaryEntry>): Masked {
    val tokens = ArrayList<String>()
    var t = PROTECT.replace(src) { m ->
        val raw = m.value
        val core = raw.trimEnd { it in TRAIL }
        val tail = raw.substring(core.length)
        tokens.add(core)
        "ZQ${tokens.size - 1}Z$tail"
    }
    for (e in glossary) {
        t = e.pattern.replace(t) { tokens.add(e.arabic); "ZQ${tokens.size - 1}Z" }
    }
    return Masked(t, tokens)
}

/** Puts the tokens back. Returns null if the engine lost or mangled any placeholder. */
fun unmask(out: String, tokens: List<String>): String? {
    if (tokens.isEmpty()) return out
    val seen = HashSet<Int>()
    val r = PLACEHOLDER.replace(out) { m ->
        val i = m.groupValues[1].toInt()
        if (i in tokens.indices) { seen.add(i); tokens[i] } else m.value
    }
    return if (seen.size == tokens.size) r else null
}

// ------------------------------------------------------------- Arabic polish

private val AR_COMMA = Regex("(?<=[\u0600-\u06FF]),")
private val AR_SEMI = Regex("(?<=[\u0600-\u06FF]);")
private val AR_QMARK = Regex("(?<=[\u0600-\u06FF])\\s*\\?")
private val SPACE_BEFORE = Regex("(?<=[\u0600-\u06FF])\\s+([،؛؟.!:])")
private val MULTI_SPACE = Regex("[ \\t]{2,}")
private val DIGIT_OR_PH = Regex("Z\\s?Q\\s?\\d{1,3}\\s?Z|[0-9]")

/** Arabic punctuation, no stray spaces before it. */
fun fixArabic(s: String): String {
    var t = s
    t = AR_COMMA.replace(t, "،")
    t = AR_SEMI.replace(t, "؛")
    t = AR_QMARK.replace(t, "؟")
    t = SPACE_BEFORE.replace(t, "\$1")
    t = MULTI_SPACE.replace(t, " ")
    return t.trim()
}

/** Optional Arabic-Indic digits. Placeholders are skipped so protected URLs stay intact. */
fun convertDigits(s: String, d: Digits): String {
    if (d == Digits.LATIN) return s
    return DIGIT_OR_PH.replace(s) { m ->
        val v = m.value
        if (v.length == 1 && v[0] in '0'..'9') (0x0660 + (v[0] - '0')).toChar().toString() else v
    }
}

// ------------------------------------------------------------ quality judge

/** Why a translation looks unusable. Ordered from best to worst. */
enum class Verdict { OK, SHORT, FOREIGN, LOOP, UNCHANGED, BLANK }

private fun norm(s: String) = s.lowercase().filter { it.isLetter() }

/** Cheap sanity checks on one translated paragraph ([src] is the masked English). */
fun judge(src: String, out: String): Verdict {
    val srcWords = LATIN_WORD.findAll(src).count()
    if (srcWords == 0) return Verdict.OK
    if (out.isBlank()) return Verdict.BLANK
    var ar = 0
    var la = 0
    for (c in out) {
        if (c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F') ar++
        else if (c in 'A'..'Z' || c in 'a'..'z') la++
    }
    if (srcWords >= 2 && norm(out) == norm(src)) return Verdict.UNCHANGED
    if (srcWords >= 4 && ar + la > 0 && ar < 0.3f * (ar + la)) return Verdict.FOREIGN
    if (src.length >= 40) {
        val r = out.length.toFloat() / src.length
        if (r < 0.40f) return Verdict.SHORT
        if (r > 3.2f) return Verdict.LOOP
    }
    val words = out.split(' ').filter { it.isNotEmpty() }
    if (words.size >= 8) {
        val top = words.groupingBy { it }.eachCount().values.max()
        if (top > 0.45f * words.size) return Verdict.LOOP
        val grams = HashMap<String, Int>()
        for (i in 0..words.size - 3) {
            val g = words[i] + " " + words[i + 1] + " " + words[i + 2]
            val n = (grams[g] ?: 0) + 1
            if (n >= 4) return Verdict.LOOP
            grams[g] = n
        }
    }
    return Verdict.OK
}
