package app.pdftrans.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import app.pdftrans.*
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val INK_BLUE = Color.parseColor("#0b3d91")
private val INK_LIGHT = Color.parseColor("#ffd54f")
private val INK_DARK = Color.parseColor("#141414")

// ------------------------------------------------------------------ text layout

private fun paintFor(fonts: FontProvider, o: Options, size: Float, color: Int, bold: Boolean) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.typeface(o.font, bold, size)
        textSize = size
        this.color = color
    }

/** Shaped, bidi-aware Arabic layout (Android's text engine handles joining and RTL). */
private fun arabicLayout(text: String, width: Float, paint: TextPaint, align: Align): StaticLayout {
    val a = when (align) {
        Align.RIGHT -> Layout.Alignment.ALIGN_NORMAL       // start edge of an RTL paragraph = right
        Align.LEFT -> Layout.Alignment.ALIGN_OPPOSITE
        Align.CENTER -> Layout.Alignment.ALIGN_CENTER
    }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(8, width.toInt()))
        .setAlignment(a)
        .setTextDirection(TextDirectionHeuristics.RTL)
        .setLineSpacing(0f, 1.3f)
        .setIncludePad(false)
        .build()
}

private class Ctx(val fonts: FontProvider, val o: Options) {
    val userColor: Int? = o.color?.let { Color.parseColor(parseColor(it)) }

    fun layout(text: String, width: Float, size: Float, color: Int, bold: Boolean, align: Align) =
        arabicLayout(text, width, paintFor(fonts, o, size, color, bold), align)
}

private fun isCentered(b: Block, pw: Float) =
    abs((b.x0 + b.x1) / 2f - pw / 2f) < 0.03f * pw && b.width < 0.8f * pw

private fun alignFor(b: Block, pw: Float, o: Options): Align =
    if (o.align == Align.RIGHT && b.kind != "p" && b.kind != "li" && isCentered(b, pw)) Align.CENTER else o.align

// ------------------------------------------------------------- page analysis

private fun gray(p: Int): Int =
    (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000

/**
 * Pixel scan of a rendered page. A scan-line is "blank" when every pixel in the text columns
 * has (almost) the same colour. Pages are only cut on blank scan-lines, so no glyph is sliced.
 */
private class Ink(bmp: Bitmap, private val k: Float, x0: Float, x1: Float, tol: Int) {
    private val blank = BooleanArray(bmp.height)
    private val heightPt = bmp.height / k

    init {
        val a = max(0, (x0 * k).toInt())
        val b = min(bmp.width, (x1 * k).toInt() + 1)
        val n = max(0, b - a)
        val row = IntArray(max(n, 1))
        for (y in 0 until bmp.height) {
            if (n == 0) { blank[y] = true; continue }
            bmp.getPixels(row, 0, n, a, y, n, 1)
            var mn = 255
            var mx = 0
            for (i in 0 until n) {
                val g = gray(row[i])
                if (g < mn) mn = g
                if (g > mx) mx = g
            }
            blank[y] = mx - mn <= tol
        }
    }

    private fun px(yPt: Float) = max(0, min(blank.size - 1, (yPt * k).toInt()))

    /** Middle (pt) of the first run of blank scan-lines that starts inside [yPt, limitPt]. */
    fun firstBlank(yPt: Float, limitPt: Float): Float? {
        for (y in px(yPt)..px(limitPt)) {
            if (blank[y]) {
                var end = y
                while (end + 1 < blank.size && blank[end + 1]) end++
                val run = min(end + 1 - y, (6 * k).toInt())
                return (y + run / 2f) / k
            }
        }
        return null
    }

    fun nextInk(yPt: Float): Float {
        for (y in px(yPt) until blank.size) if (!blank[y]) return y / k
        return heightPt
    }
}

private fun regionLum(bmp: Bitmap, k: Float, b: Block): Int {
    val x0 = max(0, (b.x0 * k).toInt()); val x1 = min(bmp.width - 1, (b.x1 * k).toInt())
    val y0 = max(0, (b.top * k).toInt()); val y1 = min(bmp.height - 1, (b.bottom * k).toInt())
    if (x1 <= x0 || y1 <= y0) return 255
    var sum = 0L
    var n = 0
    var y = y0
    while (y <= y1) {
        var x = x0
        while (x <= x1) { sum += gray(bmp.getPixel(x, y)); n++; x += 3 }
        y += 3
    }
    return if (n == 0) 255 else (sum / n).toInt()
}

private class Bg(val color: Int, val spread: Float)

/** Typical background colour around a box (ring of pixels just outside it) and how uneven it is. */
private fun sampleBg(bmp: Bitmap, k: Float, x0: Float, top: Float, x1: Float, bottom: Float): Bg {
    val a = max(0, (x0 * k).toInt() - 2)
    val b = min(bmp.width - 1, (x1 * k).toInt() + 2)
    val c = max(0, (top * k).toInt() - 2)
    val d = min(bmp.height - 1, (bottom * k).toInt() + 2)
    if (b <= a || d <= c) return Bg(Color.WHITE, 0f)
    val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>(); val gr = ArrayList<Int>()
    fun take(x: Int, y: Int) {
        val p = bmp.getPixel(x, y)
        rs.add(Color.red(p)); gs.add(Color.green(p)); bs.add(Color.blue(p)); gr.add(gray(p))
    }
    var x = a
    while (x <= b) { take(x, c); take(x, d); x += 2 }
    var y = c
    while (y <= d) { take(a, y); take(b, y); y += 2 }
    fun med(v: MutableList<Int>): Int { v.sort(); return v[v.size / 2] }
    val mean = gr.average()
    val spread = sqrt(gr.sumOf { (it - mean) * (it - mean) } / gr.size).toFloat()
    return Bg(Color.rgb(med(rs), med(gs), med(bs)), spread)
}

private fun renderBitmap(renderer: PdfRenderer, pno: Int, pw: Float, ph: Float, k: Float): Bitmap {
    val bmp = Bitmap.createBitmap(max(1, (pw * k).toInt()), max(1, (ph * k).toInt()), Bitmap.Config.ARGB_8888)
    bmp.eraseColor(Color.WHITE)
    val p = renderer.openPage(pno)
    try { p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } finally { p.close() }
    return bmp
}

private fun freeBelow(b: Block, all: List<Block>, ph: Float): Float {
    var nxt = ph - 6f
    for (other in all) {
        if (other === b || other.top < b.bottom - 1f) continue
        if (min(other.x1, b.x1) - max(other.x0, b.x0) > 4f) nxt = min(nxt, other.top)
    }
    return max(0f, nxt - b.bottom)
}

// ------------------------------------------------------------ overlay plans

private class Cut(val y: Float, val extra: Float, val band: Int)

/** One piece of Arabic on an overlay page. [cover] (REPLACE) first paints over the original text. */
private class Entry(
    val oldY: Float, val x0: Float, val x1: Float, val h: Float, val layout: StaticLayout,
    val cover: Int? = null, val dy: Float = 0.4f
)

private class Plan(val entries: List<Entry>, val cuts: List<Cut>)

/**
 * SMART: Arabic under each paragraph. Pages are cut on blank scan-lines and made taller so nothing
 * overlaps. Returns null (the page is then shown as facing pages) when that can't be done cleanly:
 * columns or tables, or no blank line near a paragraph.
 */
private fun planSmart(
    c: Ctx, tb: List<Block>, pw: Float, ph: Float, bmp: Bitmap, k: Float, ocr: Boolean
): Plan? {
    if (hasSideBySide(tb)) return null
    val o = c.o
    val xs0 = tb.minOf { it.x0 } - 3f
    val xs1 = tb.maxOf { it.x1 } + 3f
    val ink = Ink(bmp, k, xs0, xs1, if (ocr) 40 else 1)
    val entries = ArrayList<Entry>()
    val cuts = ArrayList<Cut>()
    val ordered = tb.sortedBy { it.top }
    for ((i, b) in ordered.withIndex()) {
        val text = b.trans ?: continue
        var size = b.size * 1.02f
        o.size?.let { size = min(size, it) }
        size = size.coerceIn(o.minSize, 28f)
        val x0 = max(b.x0, 8f)
        val x1 = min(max(b.x1, x0 + 60f), pw - 8f)
        val col = c.userColor ?: if (regionLum(bmp, k, b) >= 140) INK_BLUE else INK_LIGHT
        val bold = o.bold || b.kind.startsWith("h")
        val layout = c.layout(text, x1 - x0, size, col, bold, alignFor(b, pw, o))

        val cut = ink.firstBlank(b.bottom - 0.15f * b.size, b.bottom + 1.4f * b.size) ?: return null
        val nextTop = if (i + 1 < ordered.size) ordered[i + 1].top else ph
        if (cut > nextTop - 0.5f) return null
        val avail = max(0f, min(ink.nextInk(cut), ph - 4f) - cut - 0.5f)
        val need = layout.height + 0.3f * size + 2f
        if (need > avail) {
            val bx = min(bmp.width - 1, max(0, ((xs0 + 2f) * k).toInt()))
            val by = min(bmp.height - 1, max(0, (cut * k).toInt()))
            cuts.add(Cut(cut, need - avail + 1f, bmp.getPixel(bx, by)))
        }
        entries.add(Entry(cut, x0, x1, need, layout))
    }
    if (cuts.sumOf { it.extra.toDouble() } > 1.2 * ph) return null      // the page would more than double
    return Plan(entries, cuts.sortedBy { it.y })
}

/**
 * REPLACE: the Arabic is drawn over each text block on a patch of the sampled background colour.
 * Returns null when a block's background is busy (photo, gradient) or the Arabic can't fit at a
 * readable size, so that page is shown as facing pages instead.
 */
private fun planReplace(c: Ctx, tb: List<Block>, all: List<Block>, pw: Float, ph: Float, bmp: Bitmap, k: Float): Plan? {
    val o = c.o
    val entries = ArrayList<Entry>()
    val minS = o.minSize - 1f
    for (b in tb) {
        val text = b.trans ?: continue
        val bx0 = max(0f, b.x0 - 1.5f)
        val bx1 = min(pw, b.x1 + 1.5f)
        val by0 = max(0f, b.top - 1f)
        val by1 = min(ph, b.bottom + 1f)
        val bg = sampleBg(bmp, k, bx0, by0, bx1, by1)
        if (bg.spread > 28f) return null
        val lum = gray(bg.color)
        val col = c.userColor ?: if (lum >= 140) INK_DARK else Color.WHITE
        val bold = o.bold || b.kind.startsWith("h") || b.bold
        val align = alignFor(b, pw, o)
        val boxH = by1 - by0
        val allowed = boxH + min(freeBelow(b, all, ph) * 0.8f, boxH * 0.8f)
        var size = min(b.size, o.size ?: 99f)
        if (size < minS) return null
        size = max(size, minS)
        var layout = c.layout(text, bx1 - bx0, size, col, bold, align)
        while (layout.height > allowed && size > minS) {
            size = max(minS, size - 0.5f)
            layout = c.layout(text, bx1 - bx0, size, col, bold, align)
        }
        if (layout.height > allowed + 0.5f) return null
        entries.add(Entry(by0, bx0, bx1, max(boxH, layout.height + 1f), layout, cover = bg.color, dy = 0.5f))
    }
    return Plan(entries, emptyList())
}

private fun drawPlan(canvas: Canvas, plan: Plan) {
    val fill = Paint().apply { style = Paint.Style.FILL }
    for (e in plan.entries) {
        val shift = plan.cuts.filter { it.y < e.oldY }.sumOf { it.extra.toDouble() }.toFloat()
        val y = e.oldY + shift
        if (e.cover != null) {
            fill.color = e.cover
            canvas.drawRect(e.x0, y, e.x1, y + e.h, fill)
        }
        canvas.save()
        canvas.translate(e.x0, y + e.dy)
        e.layout.draw(canvas)
        canvas.restore()
    }
}

// ----------------------------------------------------- flowing pages (facing + reflow)

private class Flow(private val doc: PdfDocument, private val pw: Int, private val ph: Int, private val margin: Float) {
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = margin
    var pages = 0
        private set
    private val bottom get() = ph - margin

    private fun newPage() {
        page?.let { doc.finishPage(it) }
        pages++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pages).create())
        page = p; canvas = p.canvas; y = margin
    }

    fun place(layout: StaticLayout, spaceBefore: Float, spaceAfter: Float) {
        if (page == null) newPage()
        if (y > margin) y += spaceBefore
        var line = 0
        while (line < layout.lineCount) {
            val avail = bottom - y
            var last = line
            while (last < layout.lineCount && layout.getLineBottom(last) - layout.getLineTop(line) <= avail) last++
            if (last == line) {
                if (y > margin + 1f) { newPage(); continue }
                last = line + 1                                  // a single line taller than the page
            }
            val top = layout.getLineTop(line).toFloat()
            val bot = layout.getLineBottom(last - 1).toFloat()
            val cv = canvas!!
            cv.save()
            cv.translate(margin, y - top)
            cv.clipRect(0f, top, layout.width.toFloat(), bot)
            layout.draw(cv)
            cv.restore()
            y += bot - top
            line = last
            if (line < layout.lineCount) newPage()
        }
        y += spaceAfter
    }

    fun finish() { page?.let { doc.finishPage(it) }; page = null }
}

private fun plainLayout(text: String, width: Int, size: Float, bold: Boolean, col: Int, center: Boolean = false): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        textSize = size
        color = col
    }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
        .setTextDirection(if (center) TextDirectionHeuristics.FIRSTSTRONG_LTR else TextDirectionHeuristics.LTR)
        .setLineSpacing(0f, 1.2f).setIncludePad(false).build()
}

private fun kindScale(kind: String) = when (kind) { "h1" -> 1.35f; "h2" -> 1.2f; "h3" -> 1.08f; else -> 1f }
private fun spaceBefore(kind: String) = when (kind) { "h1", "h2" -> 12f; "h3" -> 9f; "li" -> 3f; else -> 7f }

private class FlowItem(val layout: StaticLayout, val before: Float, val after: Float)

/**
 * FACING: the Arabic of one source page on a page of the same size, in reading order. The body size
 * shrinks (never below 9pt) so that it fits one page when it can; otherwise it flows onto more pages.
 * Returns how many overlay pages were written.
 */
private fun drawFacing(c: Ctx, ov: PdfDocument, blocks: List<Block>, pageNo: Int, pw: Float, ph: Float): Int {
    val o = c.o
    val margin = (pw * 0.075f).coerceIn(28f, 50f)
    val width = pw - 2 * margin
    val col = c.userColor ?: INK_DARK
    val bodies = blocks.filter { it.kind == "p" || it.kind == "li" }.ifEmpty { blocks }
    val body = o.size ?: (median(bodies.map { it.size }) * 1.1f).coerceIn(10f, 13f)

    fun build(scale: Float): List<FlowItem> = blocks.map { b ->
        val t = b.trans ?: ""
        val text = if (b.kind == "li") "\u200F•\u00A0$t" else t
        val heading = b.kind.startsWith("h")
        FlowItem(
            c.layout(text, width, body * scale * kindScale(b.kind), col, o.bold || heading, alignFor(b, pw, o)),
            spaceBefore(b.kind), if (heading) 2f else 0f
        )
    }

    val header = plainLayout("― $pageNo ―", width.toInt(), 8f, false, Color.parseColor("#888888"), center = true)
    val room = ph - 2 * margin - header.height - 6f
    var chosen = build(1f)
    for (scale in listOf(1f, 0.94f, 0.88f, 0.82f)) {
        if (body * scale < 9f && scale != 1f) break
        val items = build(scale)
        chosen = items
        if (items.sumOf { (it.layout.height + it.before + it.after).toDouble() } <= room) break
    }
    val flow = Flow(ov, ceil(pw).toInt(), ceil(ph).toInt(), margin)
    flow.place(header, 0f, 4f)
    for (item in chosen) flow.place(item.layout, item.before, item.after)
    flow.finish()
    return flow.pages
}

// ----------------------------------------------------------------- the three page layouts

private sealed class Out
private class CopyOut(val pno: Int) : Out()
private class FacingOut(val pno: Int, val first: Int, val count: Int) : Out()
private class OverlayOut(val pno: Int, val index: Int, val cuts: List<Cut>) : Out()

/**
 * FACING, SMART and REPLACE. The original pages stay vector: the Arabic is drawn on an overlay or on
 * extra pages with Android's text engine (correct shaping and RTL) and merged with PDFBox.
 * SMART and REPLACE fall back to facing pages page by page when the clean layout is impossible.
 */
suspend fun renderLayout(
    fonts: FontProvider, o: Options, srcDoc: PDDocument, wanted: List<Int>,
    pageBlocks: Map<Int, List<Block>>, renderer: () -> PdfRenderer, ocrPages: List<Int>,
    tmpOverlay: File, dst: File, rep: Report, progress: (Int, Int) -> Unit
) {
    val c = Ctx(fonts, o)
    val ov = PdfDocument()
    val outs = ArrayList<Out>()
    var ovPages = 0
    try {
        for ((n, pno) in wanted.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress(n + 1, wanted.size)
            val src = srcDoc.getPage(pno)
            val cb = src.cropBox
            var pw = cb.width
            var ph = cb.height
            val rotation = src.rotation
            if (rotation % 180 != 0) { val t = pw; pw = ph; ph = t }
            val all = pageBlocks[pno].orEmpty()
            val tb = all.filter { it.trans != null }
            if (tb.isEmpty()) { outs.add(CopyOut(pno)); continue }

            var plan: Plan? = null
            if (o.mode != Mode.FACING) {
                if (rotation == 0) {
                    val k = min(2f, 3200f / max(pw, ph))
                    val bmp = renderBitmap(renderer(), pno, pw, ph, k)
                    try {
                        plan = if (o.mode == Mode.SMART) planSmart(c, tb, pw, ph, bmp, k, (pno + 1) in ocrPages)
                        else planReplace(c, tb, all, pw, ph, bmp, k)
                    } finally {
                        bmp.recycle()
                    }
                }
                if (plan == null) rep.fellBack.add(pno + 1)
            }

            if (plan != null) {
                val total = plan.cuts.sumOf { it.extra.toDouble() }.toFloat()
                val page = ov.startPage(PdfDocument.PageInfo.Builder(ceil(pw).toInt(), ceil(ph + total).toInt(), ovPages + 1).create())
                drawPlan(page.canvas, plan)
                ov.finishPage(page)
                outs.add(OverlayOut(pno, ovPages, plan.cuts))
                ovPages++
                if (plan.cuts.isNotEmpty()) rep.expandedPages++
            } else {
                val count = drawFacing(c, ov, tb, pno + 1, pw, ph)
                outs.add(FacingOut(pno, ovPages, count))
                ovPages += count
            }
        }
        if (ovPages > 0) FileOutputStream(tmpOverlay).use { ov.writeTo(it) }
    } finally {
        ov.close()
    }
    merge(srcDoc, if (ovPages > 0) tmpOverlay else null, outs, dst)
}

private fun merge(srcDoc: PDDocument, overlayFile: File?, outs: List<Out>, dst: File) {
    val ovDoc = overlayFile?.let { PDDocument.load(it) }
    val out = PDDocument()
    try {
        val lu = LayerUtility(out)
        for (item in outs) {
            when (item) {
                is CopyOut -> out.importPage(srcDoc.getPage(item.pno))
                is FacingOut -> {
                    out.importPage(srcDoc.getPage(item.pno))
                    for (j in 0 until item.count) out.importPage(ovDoc!!.getPage(item.first + j))
                }
                is OverlayOut -> compose(out, lu, srcDoc, ovDoc!!, item)
            }
        }
        out.save(dst)
    } finally {
        out.close()
        ovDoc?.close()
    }
}

/** One source page with room inserted at the cuts, then the overlay drawn on top. */
private fun compose(out: PDDocument, lu: LayerUtility, srcDoc: PDDocument, ovDoc: PDDocument, item: OverlayOut) {
    val srcPage = srcDoc.getPage(item.pno)
    val cb = srcPage.cropBox
    val w = cb.width
    val h = cb.height
    val total = item.cuts.sumOf { it.extra.toDouble() }.toFloat()
    val fh = h + total
    val form = lu.importPageAsForm(srcDoc, item.pno)
    val ovForm = lu.importPageAsForm(ovDoc, item.index)
    val page = PDPage(PDRectangle(w, fh))
    out.addPage(page)
    PDPageContentStream(out, page).use { cs ->
        val bounds = listOf(0f) + item.cuts.map { it.y } + listOf(h)
        var off = 0f
        for (j in 0 until bounds.size - 1) {
            val a = bounds[j]
            val b = bounds[j + 1]
            if (b - a > 0.01f) {
                cs.saveGraphicsState()
                cs.addRect(0f, fh - (b + off), w, b - a)
                cs.clip()
                cs.transform(Matrix.getTranslateInstance(-cb.lowerLeftX, fh - off - cb.lowerLeftY - h))
                cs.drawForm(form)
                cs.restoreGraphicsState()
            }
            if (j < item.cuts.size) {
                val cut = item.cuts[j]
                cs.setNonStrokingColor(Color.red(cut.band), Color.green(cut.band), Color.blue(cut.band))
                cs.addRect(0f, fh - (cut.y + off + cut.extra), w, cut.extra)
                cs.fill()
                off += cut.extra
            }
        }
        // the overlay page was rounded up to whole points: align its top edge with the page's top edge
        cs.saveGraphicsState()
        cs.transform(Matrix.getTranslateInstance(0f, fh - ceil(fh)))
        cs.drawForm(ovForm)
        cs.restoreGraphicsState()
    }
}

// ------------------------------------------------------------ paragraph mode

/** "paragraph" mode: the document is rebuilt, each English paragraph followed by its Arabic. */
suspend fun renderParagraphs(
    fonts: FontProvider, o: Options, blocks: List<Block>,
    pw: Float, ph: Float, dst: File, progress: (Int, Int) -> Unit
) {
    val c = Ctx(fonts, o)
    val body = o.size ?: 12f
    val color = c.userColor ?: Color.parseColor(DEFAULT_COLOR)
    val margin = 40f
    val contentW = (pw - 2 * margin).toInt()
    val doc = PdfDocument()
    val flow = Flow(doc, ceil(pw).toInt(), ceil(ph).toInt(), margin)
    val grey = Color.parseColor("#4a4a4a")

    var lastPage = -1
    try {
        val shown = blocks.filter { !it.skip }
        for ((i, b) in shown.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress(i + 1, shown.size)
            if (b.page != lastPage) {
                flow.place(plainLayout("― ${b.page} ―", contentW, 8f, false, Color.parseColor("#999999"), center = true), 12f, 0f)
                lastPage = b.page
            }
            val heading = b.kind.startsWith("h")
            val srcSize = when (b.kind) { "h1" -> body * 1.2f; "h2" -> body * 1.08f; else -> body * 0.85f }
            flow.place(plainLayout(b.text, contentW, srcSize, heading, grey), 7f, 0f)
            val t = b.trans ?: continue
            val text = if (b.kind == "li") "\u200F•\u00A0$t" else t
            val layout = c.layout(text, contentW.toFloat(), body * kindScale(b.kind), color, o.bold || heading, o.align)
            flow.place(layout, 2f, 4f)
        }
        flow.finish()
        FileOutputStream(dst).use { doc.writeTo(it) }
    } finally {
        doc.close()
    }
}
