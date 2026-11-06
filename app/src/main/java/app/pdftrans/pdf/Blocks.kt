package app.pdftrans.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Pure Kotlin (no Android / PDFBox imports): unit-tested on the JVM.

/** One horizontal line of text. Coordinates are PDF points, origin top-left. */
class Line(
    val text: String,
    val x0: Float, val x1: Float,
    val top: Float, val bottom: Float,
    val size: Float,
    val bold: Boolean
) {
    val height get() = bottom - top
}

/** A paragraph-like run of lines: the unit that is translated and placed on the page. */
class Block(val lines: List<Line>, val page: Int, val text: String) {
    val x0 = lines.minOf { it.x0 }
    val x1 = lines.maxOf { it.x1 }
    val top = lines.minOf { it.top }
    val bottom = lines.maxOf { it.bottom }
    val size = median(lines.map { it.size })
    val bold = lines.count { it.bold } > lines.size / 2
    val width get() = x1 - x0
    val height get() = bottom - top

    var kind = "p"           // p | li | h1 | h2 | h3
    var skip = false         // running header / footer: not translated
    var trans: String? = null
}

internal fun median(v: List<Float>): Float {
    val s = v.sorted()
    return if (s.isEmpty()) 0f else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
}

private val BULLET_RE = Regex("""^\s*(?:[•●▪■◦‣·\-–—*]|\(?\d{1,3}[.)]|\(?[A-Za-z][.)])\s+""")
private val BULLET_STRIP = Regex("""^\s*[•●▪■◦‣·*\-–—]\s+""")
private val END_RE = Regex("""[.!?:;…)”"'\]]\s*$""")

fun stripBullet(s: String) = BULLET_STRIP.replace(s, "")

fun joinLines(lines: List<String>): String {
    var out = lines[0]
    for (nxt in lines.drop(1)) {
        out = if (out.endsWith("-") && out.length > 1 && out[out.length - 2].isLetter() && nxt.firstOrNull()?.isLowerCase() == true)
            out.dropLast(1) + nxt
        else "$out $nxt"
    }
    return out
}

// ------------------------------------------------------------ line -> paragraph

/** Typical (line pitch / font size) of the page: 1.2 normal text, 1.5-2.0 for loose spacing. */
private fun pitchRatio(sorted: List<Line>): Float {
    val r = ArrayList<Float>()
    for (i in 1 until sorted.size) {
        val l = sorted[i]
        var j = i - 1
        while (j >= 0 && i - j <= 12) {
            val p = sorted[j]
            val dy = l.top - p.top
            val overlap = min(l.x1, p.x1) - max(l.x0, p.x0)
            if (dy > 0.4f * l.size && dy < 3.2f * l.size && abs(l.size - p.size) <= 1f &&
                overlap > 0.3f * min(l.x1 - l.x0, p.x1 - p.x0)
            ) { r.add(dy / l.size); break }
            j--
        }
    }
    return if (r.isEmpty()) 1.25f else median(r)
}

/**
 * Groups the lines of one page into paragraphs by geometry (not by the order inside the PDF, which
 * interleaves columns): a line joins the paragraph just above it when the spacing, font size, overlap
 * and indentation fit; bullets, indents, short sentence-ending lines and extra spacing start a new one.
 */
fun buildBlocks(lines: List<Line>, pageNo: Int): List<Block> {
    if (lines.isEmpty()) return emptyList()
    val sorted = lines.sortedWith(compareBy<Line>({ it.top }, { it.x0 }))
    val attachMax = (pitchRatio(sorted) * 1.35f).coerceIn(1.55f, 3.4f)
    val groups = ArrayList<ArrayList<Line>>()
    for (ln in sorted) {
        var best: ArrayList<Line>? = null
        var bestDy = Float.MAX_VALUE
        for (g in groups) {
            val p = g.last()
            val dy = ln.top - p.top
            if (dy < 0.4f * ln.size) continue                                   // same row: other column
            if (dy > attachMax * max(ln.size, p.size)) continue                 // paragraph spacing
            if (abs(ln.size - p.size) > max(1f, 0.12f * p.size)) continue       // different style
            if (BULLET_RE.containsMatchIn(ln.text)) continue                    // a new list item
            val gx0 = g.minOf { it.x0 }
            val gx1 = g.maxOf { it.x1 }
            val overlap = min(ln.x1, gx1) - max(ln.x0, gx0)
            if (overlap < 0.25f * min(ln.x1 - ln.x0, gx1 - gx0)) continue       // not in the same column
            if (END_RE.containsMatchIn(p.text) && p.x1 < gx1 - 4.5f * p.size) continue   // short last line
            val bulletGroup = BULLET_RE.containsMatchIn(g.first().text)
            val centered = abs((ln.x0 + ln.x1) / 2f - (p.x0 + p.x1) / 2f) < 0.6f * ln.size
            if (!bulletGroup && !centered && ln.x0 - gx0 > 1.2f * ln.size && p.x1 > gx1 - 3f * p.size) continue   // first-line indent
            if (dy < bestDy) { best = g; bestDy = dy }
        }
        if (best != null) best.add(ln) else groups.add(arrayListOf(ln))
    }
    return groups.map { g -> Block(g, pageNo, joinLines(g.map { it.text })) }
}

// ----------------------------------------------------------------- reading order

/**
 * Blocks in reading order: full-width blocks split the page, columns inside a band read left to right.
 * With [pageH] known, margin text (running heads at the top, footers and page numbers at the bottom)
 * goes first / last instead of landing between the columns.
 */
fun readingOrder(blocks: List<Block>, pageH: Float = 0f): List<Block> {
    if (blocks.size < 2) return blocks
    if (pageH > 0f) {
        val head = blocks.filter { it.bottom < 0.07f * pageH }.sortedBy { it.x0 }
        val foot = blocks.filter { it.top > 0.93f * pageH && it !in head }.sortedBy { it.x0 }
        val mid = blocks.filter { it !in head && it !in foot }
        if (head.isNotEmpty() || foot.isNotEmpty()) return head + readingOrder(mid) + foot
    }
    val left = blocks.minOf { it.x0 }
    val right = blocks.maxOf { it.x1 }
    val full = (right - left) * 0.62f
    val byTop = blocks.sortedWith(compareBy<Block>({ it.top }, { it.x0 }))
    val out = ArrayList<Block>()
    var seg = ArrayList<Block>()
    for (b in byTop) {
        if (b.width >= full) {
            out.addAll(columnsOrder(seg)); seg = ArrayList(); out.add(b)
        } else seg.add(b)
    }
    out.addAll(columnsOrder(seg))
    return out
}

private fun columnsOrder(seg: List<Block>): List<Block> {
    if (seg.size < 2) return seg
    val cols = ArrayList<MutableList<Block>>()
    var colMax = -1e9f
    for (b in seg.sortedBy { it.x0 }) {
        if (cols.isEmpty() || b.x0 >= colMax - 2f) { cols.add(mutableListOf(b)); colMax = b.x1 }
        else { cols.last().add(b); colMax = max(colMax, b.x1) }
    }
    return cols.flatMap { c -> c.sortedBy { it.top } }
}

/** True when blocks sit side by side (columns, table cells): such a page can't be re-flowed row by row. */
fun hasSideBySide(blocks: List<Block>): Boolean {
    for (i in blocks.indices) for (j in i + 1 until blocks.size) {
        val a = blocks[i]
        val b = blocks[j]
        val shared = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (shared > 0.5f * min(a.height, b.height) && (a.x1 < b.x0 || b.x1 < a.x0)) return true
    }
    return false
}

// --------------------------------------------------------------- classification

/** Headings and list items, judged against the document's dominant (body) font size. */
fun classifyBlocks(blocks: List<Block>) {
    if (blocks.isEmpty()) return
    val weighted = blocks.map { it.size to it.text.length }.sortedBy { it.first }
    val total = weighted.sumOf { it.second }
    var acc = 0
    var body = weighted[0].first
    for ((s, w) in weighted) {
        acc += w
        if (acc >= total / 2.0) { body = s; break }
    }
    for (b in blocks) {
        b.kind = when {
            b.size >= body * 1.5f -> "h1"
            b.size >= body * 1.2f -> "h2"
            b.bold && b.text.length < 100 && !END_RE.containsMatchIn(b.text) && b.size >= body * 0.98f -> "h3"
            BULLET_RE.containsMatchIn(b.text) -> "li"
            else -> "p"
        }
    }
}

private val DIGITS = Regex("\\d+")

/**
 * Running headers and footers: the same text (page numbers aside) in the top or bottom band of
 * several pages. They are not translated, which also keeps them out of the Arabic pages.
 */
fun markRunningHeads(pages: Map<Int, List<Block>>, heightOf: (Int) -> Float) {
    if (pages.size < 3) return
    val seen = HashMap<String, MutableList<Block>>()
    for ((pno, blocks) in pages) {
        val ph = heightOf(pno)
        for (b in blocks) {
            val zone = when {
                b.bottom < 0.12f * ph -> "T"
                b.top > 0.88f * ph -> "B"
                else -> continue
            }
            val key = zone + DIGITS.replace(b.text.lowercase(), "#").replace(Regex("\\s+"), " ").trim()
            seen.getOrPut(key) { ArrayList() }.add(b)
        }
    }
    val need = max(3, (0.4f * pages.size).toInt())
    for (group in seen.values) {
        if (group.map { it.page }.toSet().size >= need) group.forEach { it.skip = true }
    }
}
