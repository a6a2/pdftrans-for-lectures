package app.pdftrans.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import app.pdftrans.PdfTransException
import app.pdftrans.cleanText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

fun openPdf(file: File): PDDocument {
    val doc = try {
        PDDocument.load(file)
    } catch (e: Exception) {
        val msg = e.message ?: ""
        if (e.javaClass.simpleName.contains("Password", true) || msg.contains("password", true))
            throw PdfTransException("password")
        throw PdfTransException("cannot_open", msg)
    }
    if (doc.numberOfPages == 0) {
        doc.close(); throw PdfTransException("empty_pdf")
    }
    return doc
}

fun pageHasImage(page: PDPage): Boolean = try {
    val res = page.resources
    res != null && res.xObjectNames.any { n -> try { res.getXObject(n) is PDImageXObject } catch (e: Exception) { false } }
} catch (e: Exception) {
    true
}

// ------------------------------------------------------- text from the PDF

private class WordCollector : PDFTextStripper() {
    val words = ArrayList<List<TextPosition>>()
    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        if (!textPositions.isNullOrEmpty()) words.add(ArrayList(textPositions))
    }
}

private class Word(val text: String, val x0: Float, val x1: Float, val base: Float, val size: Float, val bold: Boolean)

private fun isBold(tp: TextPosition): Boolean {
    val f = tp.font ?: return false
    val n = (f.name ?: "").lowercase()
    if ("bold" in n || "black" in n || "heavy" in n) return true
    return try {
        val d = f.fontDescriptor
        d != null && (d.fontWeight >= 700f || d.isForceBold)
    } catch (e: Exception) {
        false
    }
}

/**
 * Returns (lines of the page, number of vertical/rotated words that were skipped).
 * Lines come in content-stream order; [buildBlocks] regroups them by geometry.
 */
fun extractPageLines(doc: PDDocument, pno: Int): Pair<List<Line>, Int> {
    val st = WordCollector()
    st.startPage = pno + 1
    st.endPage = pno + 1
    st.sortByPosition = false
    try {
        st.getText(doc)
    } catch (e: Exception) {
        return Pair(emptyList(), 0)
    }
    val pageRot = doc.getPage(pno).rotation.toFloat()
    var rotated = 0
    val words = ArrayList<Word>()
    for (tps in st.words) {
        val first = tps.first()
        val last = tps.last()
        val d = first.dir
        if (d != 0f && d != pageRot) { rotated++; continue }
        val text = cleanText(tps.joinToString("") { it.unicode ?: "" })
        if (text.isEmpty()) continue
        var size = tps.map { it.fontSizeInPt }.average().toFloat()
        if (size <= 0f) size = 10f
        words.add(
            Word(
                text, first.xDirAdj, last.xDirAdj + last.widthDirAdj, first.yDirAdj, size,
                tps.count { isBold(it) } > tps.size / 2
            )
        )
    }

    val lines = ArrayList<Line>()
    var cur = ArrayList<Word>()
    fun flush() {
        if (cur.isEmpty()) return
        val main = cur.maxByOrNull { it.text.length }!!
        val base = cur.map { it.base }.average().toFloat()
        val size = main.size
        lines.add(
            Line(
                cur.joinToString(" ") { it.text }, cur.first().x0, cur.last().x1,
                base - 0.80f * size, base + 0.22f * size, size, cur.count { it.bold } > cur.size / 2
            )
        )
        cur = ArrayList()
    }
    for (w in words) {
        if (cur.isNotEmpty()) {
            val p = cur.last()
            val ref = max(p.size, w.size)
            val sameRow = abs(w.base - p.base) <= 0.5f * ref          // tolerates superscripts
            val forward = w.x0 >= p.x1 - 0.5f * ref
            val near = w.x0 - p.x1 <= 2.2f * ref                      // a wide gap = next column / table cell
            if (!(sameRow && forward && near)) flush()
        }
        cur.add(w)
    }
    flush()
    return Pair(lines, rotated)
}

// ------------------------------------------------------------------- OCR

suspend fun ocrLines(renderer: PdfRenderer, pno: Int, recognizer: TextRecognizer, dpi: Int = 200): List<Line> {
    val page = renderer.openPage(pno)
    // never build a bitmap larger than ~3000 px on its long side (memory)
    val scale = min(dpi / 72f, 3000f / max(page.width, page.height))
    val bmp = Bitmap.createBitmap(max(1, (page.width * scale).toInt()), max(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
    bmp.eraseColor(Color.WHITE)
    try {
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    } finally {
        page.close()
    }
    val result = try {
        recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
    } finally {
        bmp.recycle()
    }
    val out = ArrayList<Line>()
    for (block in result.textBlocks) {
        for (ln in block.lines) {
            val r = ln.boundingBox ?: continue
            val text = cleanText(ln.text)
            if (text.isEmpty()) continue
            val h = r.height() / scale
            out.add(Line(text, r.left / scale, r.right / scale, r.top / scale, r.bottom / scale, h * 0.8f, false))
        }
    }
    return out
}
