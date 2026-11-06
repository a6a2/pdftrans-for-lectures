package app.pdftrans.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import app.pdftrans.*
import app.pdftrans.engine.EngineSet
import app.pdftrans.engine.TranslationService
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * extract -> group into paragraphs -> translate paragraphs -> render.
 * Every mode works on paragraph blocks, never on single PDF lines.
 */
class PdfTranslator(private val ctx: Context) {
    private val fonts = FontProvider(ctx)

    suspend fun run(
        src: File, dst: File, opts: Options, engines: EngineSet, glossary: Map<String, String>,
        progress: (Stage, Int, Int) -> Unit
    ): Report = withContext(Dispatchers.Default) { doRun(src, dst, opts, engines, glossary, progress) }

    private suspend fun doRun(
        src: File, dst: File, opts: Options, engines: EngineSet, glossary: Map<String, String>,
        progress: (Stage, Int, Int) -> Unit
    ): Report {
        val t0 = SystemClock.elapsedRealtime()
        opts.validate()
        val rep = Report()
        val doc = openPdf(src)
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var recognizer: TextRecognizer? = null
        val tmp = File(ctx.cacheDir, "overlay.pdf")

        fun rendererOrOpen(): PdfRenderer {
            renderer?.let { return it }
            val fd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = fd
            return PdfRenderer(fd).also { renderer = it }
        }

        try {
            rep.pagesTotal = doc.numberOfPages
            val wanted = parsePages(opts.pages, doc.numberOfPages)
            engines.primary.prepare()

            // ---- 1. extraction (OCR for scanned pages) -> paragraph blocks ---------------
            val pageBlocks = HashMap<Int, List<Block>>()
            for ((n, pno) in wanted.withIndex()) {
                currentCoroutineContext().ensureActive()
                progress(Stage.EXTRACT, n + 1, wanted.size)
                val page = doc.getPage(pno)
                var (lines, _) = extractPageLines(doc, pno)
                val textLen = lines.sumOf { l -> l.text.count { !it.isWhitespace() } }
                if (textLen < 25 && pageHasImage(page)) {
                    if (opts.ocr) {
                        try {
                            val rec = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                .also { recognizer = it }
                            lines = ocrLines(rendererOrOpen(), pno, rec)
                            rep.ocrPages.add(pno + 1)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            rep.scannedUnprocessed.add(pno + 1)
                        }
                    } else {
                        rep.scannedUnprocessed.add(pno + 1)
                    }
                }
                pageBlocks[pno] = readingOrder(buildBlocks(lines, pno + 1), page.cropBox.height)
            }
            val ordered = wanted.flatMap { pageBlocks[it].orEmpty() }
            classifyBlocks(ordered)
            markRunningHeads(wanted.associateWith { pageBlocks[it].orEmpty() }) { pno -> doc.getPage(pno).cropBox.height }

            // ---- 2. choose what to translate ---------------------------------------------
            val cand = ordered.filter { !it.skip && needsTranslation(stripBullet(it.text)) }
            val texts = cand.map { stripBullet(it.text) }
            rep.segments = texts.size
            if (texts.isEmpty()) throw PdfTransException("no_text")

            // ---- 3. translate paragraphs ---------------------------------------------------
            val service = TranslationService(engines.primary, engines.fallbacks, compileGlossary(glossary), opts.digits)
            val translated = service.translate(texts) { d, t -> progress(Stage.TRANSLATE, d, t) }
            var got = 0
            cand.forEachIndexed { i, b ->
                if (translated[i] != texts[i]) { b.trans = translated[i]; got++ }
            }
            rep.failedSegments = service.failedChunks
            rep.weakSegments = service.stats.weak
            rep.retriedSegments = service.stats.retried
            rep.engine = service.stats.engine
            rep.switchedEngine = service.stats.switched
            if (got == 0) throw PdfTransException("engine_failing")

            // ---- 4. render -----------------------------------------------------------------
            currentCoroutineContext().ensureActive()
            if (opts.mode == Mode.PARAGRAPH) {
                val first = doc.getPage(wanted[0])
                var w = first.cropBox.width
                var h = first.cropBox.height
                if (first.rotation % 180 != 0) { val x = w; w = h; h = x }
                renderParagraphs(fonts, opts, ordered, w, h, dst) { d, t -> progress(Stage.RENDER, d, t) }
            } else {
                renderLayout(
                    fonts, opts, doc, wanted, pageBlocks, ::rendererOrOpen, rep.ocrPages, tmp, dst, rep
                ) { d, t -> progress(Stage.RENDER, d, t) }
            }
            rep.pagesDone = wanted.size
        } finally {
            try { recognizer?.close() } catch (e: Exception) { }
            try { renderer?.close() } catch (e: Exception) { }
            try { pfd?.close() } catch (e: Exception) { }
            doc.close()
            tmp.delete()
        }
        rep.seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0
        return rep
    }
}
