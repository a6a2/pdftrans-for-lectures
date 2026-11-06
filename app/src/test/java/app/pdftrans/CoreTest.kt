package app.pdftrans

import app.pdftrans.engine.Engine
import app.pdftrans.engine.TranslationService
import app.pdftrans.pdf.Block
import app.pdftrans.pdf.Line
import app.pdftrans.pdf.buildBlocks
import app.pdftrans.pdf.classifyBlocks
import app.pdftrans.pdf.hasSideBySide
import app.pdftrans.pdf.markRunningHeads
import app.pdftrans.pdf.readingOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ln(text: String, x0: Float, x1: Float, top: Float, size: Float = 10f, bold: Boolean = false) =
    Line(text, x0, x1, top, top + size * 1.02f, size, bold)

/** Fake engine: every English word becomes an Arabic word; placeholders survive. */
private class FakeEngine(
    private val name: String,
    private val behave: (String) -> String? = { null }      // null = translate normally
) : Engine() {
    var calls = 0
    override val label = name
    override val retryDelayMs = 0L
    private val words = listOf("هذا", "نص", "مترجم", "من", "اللغة", "الإنجليزية", "إلى", "العربية", "بنجاح", "تام", "جدا", "الآن")
    private val ph = Regex("ZQ\\d+Z")
    override suspend fun translateBatch(texts: List<String>): List<String> {
        calls++
        return texts.map { t ->
            behave(t) ?: t.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w ->
                if (ph.matches(w.trimEnd('.', ',', ';'))) w else words[(i * 7 + w.length) % words.size]
            }.joinToString(" ")
        }
    }
}

class TextRulesTest {
    @Test fun cleanText_fixesLigaturesAndLeaders() {
        assertEquals("fine office", cleanText("\uFB01ne o\uFB03ce"))
        assertEquals("Introduction 5", cleanText("Introduction ........ 5"))
    }

    @Test fun needsTranslation_filters() {
        assertTrue(needsTranslation("Hello world"))
        assertFalse(needsTranslation("12345 67"))
        assertFalse(needsTranslation("x = y + 2"))
        assertFalse(needsTranslation("https://example.com/path"))
        assertFalse(needsTranslation("مرحبا بالعالم"))
        assertFalse(needsTranslation("0.12  0.34  0.56 ab"))
    }

    @Test fun splitSentences_keepsAbbreviations() {
        val s = splitSentences("Dr. Smith went to Fig. 3. He saw it. It was fine!")
        assertEquals(listOf("Dr. Smith went to Fig. 3.", "He saw it.", "It was fine!"), s)
    }

    @Test fun packChunks_respectsLimit() {
        val chunks = packChunks(listOf("Aaaa aaaa.", "Bbbb bbbb.", "Cccc cccc."), 22)
        assertEquals(listOf("Aaaa aaaa. Bbbb bbbb.", "Cccc cccc."), chunks)
        val long = packChunks(listOf("word ".repeat(40).trim()), 50)
        assertTrue(long.all { it.length <= 50 })
        assertEquals(40, long.sumOf { it.split(' ').size })
    }

    @Test fun mask_roundTrip() {
        val m = mask("See https://a.org/x. Visit www.b.com, now", emptyList())
        assertEquals("See ZQ0Z. Visit ZQ1Z, now", m.text)
        assertEquals("See https://a.org/x. Visit www.b.com, now", unmask(m.text, m.tokens))
        assertNull(unmask("لا شيء", m.tokens))
        assertEquals("ok", unmask("ok", emptyList()))
    }

    @Test fun glossary_isApplied() {
        val g = compileGlossary(parseGlossary("# comment\nmachine learning = تعلم الآلة\nbad line"))
        val m = mask("Machine Learning is fun, and machine learning rocks", g)
        assertEquals("ZQ0Z is fun, and ZQ1Z rocks", m.text)
        assertEquals("تعلم الآلة هو ممتع", unmask("ZQ0Z هو ممتع", listOf("تعلم الآلة")))
        assertEquals(listOf("تعلم الآلة", "تعلم الآلة"), m.tokens)
    }

    @Test fun fixArabic_punctuation() {
        assertEquals("مرحبا، كيف حالك؟", fixArabic("مرحبا, كيف حالك ?"))
        assertEquals("نعم؛ لا", fixArabic("نعم; لا"))
    }

    @Test fun convertDigits_skipsPlaceholders() {
        assertEquals("صفحة ١٢ ZQ3Z", convertDigits("صفحة 12 ZQ3Z", Digits.ARABIC))
        assertEquals("صفحة 12", convertDigits("صفحة 12", Digits.LATIN))
    }

    @Test fun judge_detectsProblems() {
        val src = "The quick brown fox jumps over the lazy dog today"
        assertEquals(Verdict.OK, judge(src, "الثعلب البني السريع يقفز فوق الكلب الكسول اليوم"))
        assertEquals(Verdict.UNCHANGED, judge(src, src))
        assertEquals(Verdict.BLANK, judge(src, "  "))
        assertEquals(Verdict.LOOP, judge(src, "كلمة ".repeat(12).trim()))
        assertEquals(Verdict.SHORT, judge(src + " and then some more words to be long enough", "نعم"))
        assertEquals(Verdict.OK, judge("Introduction", "مقدمة"))
        assertEquals(Verdict.OK, judge("12 34", "12 34"))
    }

    @Test fun parsePages_ranges() {
        assertEquals(listOf(0, 1, 2, 6, 9, 10, 11), parsePages("1-3,7,10-", 12))
        assertEquals((0 until 5).toList(), parsePages(null, 5))
    }
}

class LayoutTest {
    private fun paragraph(top: Float, n: Int, x0: Float = 50f, x1: Float = 500f, size: Float = 10f): List<Line> =
        (0 until n).map { i ->
            val last = i == n - 1
            ln(if (last) "last line of the text." else "a full line of running text", x0, if (last) x0 + 250f else x1, top + 12f * i, size)
        }

    @Test fun buildBlocks_splitsParagraphs() {
        val lines = paragraph(100f, 3) + paragraph(150f, 2)
        val blocks = buildBlocks(lines, 1)
        assertEquals(2, blocks.size)
        assertEquals(3, blocks[0].lines.size)
        assertEquals(2, blocks[1].lines.size)
    }

    @Test fun buildBlocks_columnsAreNotInterleaved() {
        val left = (0 until 4).map { ln("left column line $it", 50f, 250f, 100f + 12f * it) }
        val right = (0 until 4).map { ln("right column line $it", 300f, 500f, 100f + 12f * it) }
        val mixed = left.zip(right).flatMap { listOf(it.first, it.second) }      // PDF stream order interleaves them
        val blocks = buildBlocks(mixed, 1)
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.lines.size == 4 })
        assertTrue(hasSideBySide(blocks))
        val order = readingOrder(blocks)
        assertTrue(order[0].x0 < order[1].x0)
    }

    @Test fun buildBlocks_bulletsAndHeadings() {
        val lines = listOf(
            ln("Chapter One", 50f, 200f, 60f, 20f, true),
            ln("• first item that is quite long and wraps", 50f, 480f, 100f),
            ln("onto a second line", 62f, 200f, 112f),
            ln("• second item", 50f, 150f, 124f),
            ln("Plain body text goes here and continues on.", 50f, 480f, 150f)
        )
        val blocks = readingOrder(buildBlocks(lines, 1))
        classifyBlocks(blocks)
        assertEquals(4, blocks.size)
        assertEquals("h1", blocks[0].kind)
        assertEquals("li", blocks[1].kind)
        assertEquals(2, blocks[1].lines.size)
        assertEquals("li", blocks[2].kind)
    }

    @Test fun readingOrder_fullWidthSplitsColumns() {
        val lines = listOf(ln("A wide title across the page that spans everything here", 50f, 500f, 60f, 16f)) +
            (0 until 3).map { ln("left $it words words", 50f, 250f, 100f + 12f * it) } +
            (0 until 3).map { ln("right $it words words", 300f, 500f, 100f + 12f * it) } +
            listOf(ln("A wide closing line across the page that spans everything", 50f, 500f, 300f))
        val order = readingOrder(buildBlocks(lines, 1))
        assertEquals(4, order.size)
        assertTrue(order[0].text.startsWith("A wide title"))
        assertTrue(order[1].text.startsWith("left"))
        assertTrue(order[2].text.startsWith("right"))
        assertTrue(order[3].text.startsWith("A wide closing"))
    }

    @Test fun readingOrder_marginsGoFirstAndLast() {
        val lines = (0 until 3).map { ln("left $it words words", 50f, 250f, 100f + 12f * it) } +
            (0 until 3).map { ln("right $it words words", 300f, 500f, 100f + 12f * it) } +
            listOf(ln("Page 7", 50f, 100f, 760f), ln("Running title of the book", 50f, 250f, 20f))
        val order = readingOrder(buildBlocks(lines, 1), 792f)
        assertEquals("Running title of the book", order.first().text)
        assertEquals("Page 7", order.last().text)
        assertTrue(order[1].text.startsWith("left"))
        assertTrue(order[2].text.startsWith("right"))
    }

    @Test fun markRunningHeads_flagsRepeatedFooters() {
        val ph = 800f
        val pages = (1..5).associateWith { p ->
            buildBlocks(
                listOf(ln("Real content of page number $p is here", 50f, 480f, 300f), ln("Confidential draft $p", 50f, 200f, 780f)),
                p
            )
        }
        markRunningHeads(pages) { ph }
        assertTrue(pages.values.all { bs -> bs.first { it.text.startsWith("Confidential") }.skip })
        assertTrue(pages.values.all { bs -> bs.first { it.text.startsWith("Real") }.skip.not() })
    }
}

class ServiceTest {
    private fun svc(primary: Engine, vararg fb: Engine, digits: Digits = Digits.LATIN, gloss: String = "") =
        TranslationService(primary, fb.toList(), compileGlossary(parseGlossary(gloss)), digits)

    private val a = "The committee approved the new budget after a long discussion about costs."
    private val b = "Results improved significantly when the model was trained with more data."

    @Test fun translates_inParagraphs() = runBlocking {
        val e = FakeEngine("primary")
        val s = svc(e)
        val out = s.translate(listOf(a, b)) { _, _ -> }
        assertEquals(2, out.size)
        assertTrue(out.all { x -> x.any { it in '\u0600'..'\u06FF' } })
        assertEquals(0, s.stats.weak)
        assertEquals("primary", s.stats.engine)
    }

    @Test fun weakResult_isRetriedOnFallback() = runBlocking {
        val bad = FakeEngine("bad") { it }                       // returns the English back
        val good = FakeEngine("good")
        val s = svc(bad, good)
        val out = s.translate(listOf(a, b)) { _, _ -> }
        assertTrue(out.all { x -> x.any { it in '\u0600'..'\u06FF' } })
        assertEquals(2, s.stats.retried)
        assertEquals(0, s.stats.weak)
        assertTrue(good.calls >= 1)
    }

    @Test fun weakResult_withoutFallback_isReturnedAsSource() = runBlocking {
        val s = svc(FakeEngine("bad") { it })
        val out = s.translate(listOf(a)) { _, _ -> }
        assertEquals(listOf(a), out)
        assertEquals(1, s.stats.weak)
    }

    @Test fun lostPlaceholder_isRetriedWithoutProtection() = runBlocking {
        val text = "Please read the full report at https://example.org/report before the meeting tomorrow."
        // an engine that destroys placeholders on the first (masked) pass
        val e = FakeEngine("lossy") { t -> if (t.contains("ZQ0Z")) "ينبغي قراءة التقرير الكامل قبل الاجتماع غدا بالتأكيد" else null }
        val s = svc(e)
        val out = s.translate(listOf(text)) { _, _ -> }
        assertEquals(1, out.size)
        assertTrue(out[0].any { it in '\u0600'..'\u06FF' })
    }

    @Test fun placeholder_isRestored() = runBlocking {
        val text = "Please read the full report at https://example.org/report before the meeting."
        val s = svc(FakeEngine("ok"))
        val out = s.translate(listOf(text)) { _, _ -> }
        assertTrue(out[0].contains("https://example.org/report"))
        assertFalse(out[0].contains("ZQ"))
    }

    @Test fun glossary_termsComeBackInArabic() = runBlocking {
        val s = svc(FakeEngine("ok"), gloss = "committee = اللجنة")
        val out = s.translate(listOf(a)) { _, _ -> }
        assertTrue(out[0].contains("اللجنة"))
    }

    @Test fun quota_switchesToFallback() = runBlocking {
        val dead = object : Engine() {
            override val label = "dead"
            override val retryDelayMs = 0L
            override suspend fun translateBatch(texts: List<String>): List<String> = throw PdfTransException("quota")
        }
        val s = svc(dead, FakeEngine("backup"))
        val out = s.translate(listOf(a)) { _, _ -> }
        assertTrue(out[0].any { it in '\u0600'..'\u06FF' })
        assertTrue(s.stats.switched)
        assertEquals("backup", s.stats.engine)
    }

    @Test fun missingModel_onMainEngine_isReported() {
        val e = object : Engine() {
            override val retryDelayMs = 0L
            override suspend fun translateBatch(texts: List<String>): List<String> = throw PdfTransException("model_missing")
        }
        var code: String? = null
        try { runBlocking { svc(e, FakeEngine("x")).translate(listOf(a)) { _, _ -> } } } catch (x: PdfTransException) { code = x.code }
        assertEquals("model_missing", code)
    }

    @Test fun arabicDigits_option() = runBlocking {
        val s = svc(FakeEngine("ok") { "المجموع 25 نقطة في هذا الاختبار الطويل جدا اليوم" }, digits = Digits.ARABIC)
        val out = s.translate(listOf("The total is 25 points in this fairly long test today.")) { _, _ -> }
        assertTrue(out[0].contains("٢٥"))
        assertNotNull(out[0])
    }
}
