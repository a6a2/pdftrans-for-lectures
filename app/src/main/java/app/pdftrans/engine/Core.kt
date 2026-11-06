package app.pdftrans.engine

import app.pdftrans.Digits
import app.pdftrans.GlossaryEntry
import app.pdftrans.Masked
import app.pdftrans.PdfTransException
import app.pdftrans.Verdict
import app.pdftrans.convertDigits
import app.pdftrans.fixArabic
import app.pdftrans.judge
import app.pdftrans.mask
import app.pdftrans.packChunks
import app.pdftrans.splitSentences
import app.pdftrans.unmask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

// Pure Kotlin + coroutines (no Android imports): unit-tested on the JVM.

/** Batching, caching, retry with back-off, batch splitting and a circuit breaker. */
abstract class Engine {
    open val label: String get() = javaClass.simpleName

    /** Largest piece of text (chars) this engine should receive in one item. */
    open val chunkChars: Int = 1200

    var failed = 0
        private set
    private val cache = HashMap<String, String>()
    private var consecutiveFails = 0

    protected open val maxItems = 20
    protected open val maxChars = 3000
    protected open val retries = 3
    protected open val retryDelayMs = 1500L

    /** Throw [PdfTransException] early if the engine cannot work. */
    open suspend fun prepare() {}
    open fun close() {}
    protected abstract suspend fun translateBatch(texts: List<String>): List<String>

    suspend fun translate(texts: List<String>, progress: (Int, Int) -> Unit): List<String> {
        val out = arrayOfNulls<String>(texts.size)
        val todo = LinkedHashMap<String, MutableList<Int>>()
        texts.forEachIndexed { i, t ->
            val hit = cache[t]
            if (hit != null) out[i] = hit else todo.getOrPut(t) { mutableListOf() }.add(i)
        }
        val uniq = todo.keys.toList()
        var done = 0
        progress(0, uniq.size)
        for (batch in batches(uniq)) {
            currentCoroutineContext().ensureActive()
            val res = safeBatch(batch)
            batch.forEachIndexed { j, t ->
                cache[t] = res[j]
                todo.getValue(t).forEach { out[it] = res[j] }
            }
            done += batch.size
            progress(done, uniq.size)
        }
        return texts.indices.map { out[it] ?: texts[it] }
    }

    private fun batches(items: List<String>): List<List<String>> {
        val all = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var chars = 0
        for (t in items) {
            if (cur.isNotEmpty() && (cur.size >= maxItems || chars + t.length > maxChars)) {
                all.add(cur); cur = ArrayList(); chars = 0
            }
            cur.add(t); chars += t.length
        }
        if (cur.isNotEmpty()) all.add(cur)
        return all
    }

    private suspend fun safeBatch(batch: List<String>): List<String> {
        var last: Exception? = null
        for (attempt in 0 until retries) {
            try {
                val res = translateBatch(batch)
                check(res.size == batch.size) { "engine returned a wrong number of results" }
                consecutiveFails = 0
                return res.mapIndexed { i, r -> if (r.isNotBlank()) r else batch[i] }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfTransException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(retryDelayMs * (attempt + 1))
            }
        }
        if (batch.size > 1) {
            val mid = batch.size / 2
            return safeBatch(batch.subList(0, mid)) + safeBatch(batch.subList(mid, batch.size))
        }
        failed++
        consecutiveFails++
        if (consecutiveFails >= 4) throw PdfTransException("engine_failing", last?.message ?: "")
        return batch
    }
}

class TranslationStats {
    var retried = 0              // paragraphs re-translated by another engine
    var weak = 0                 // paragraphs still doubtful at the end
    var engine = ""              // engine that did the main pass
    var switched = false         // the main pass moved to a fallback engine
}

/**
 * Paragraph-level translation on top of an [Engine]: protects URLs and glossary terms, packs sentences
 * into engine-sized chunks, translates, fixes the Arabic, judges the result and re-does doubtful
 * paragraphs on the fallback engines.
 */
class TranslationService(
    private val primary: Engine,
    private val fallbacks: List<Engine>,
    private val glossary: List<GlossaryEntry>,
    private val digits: Digits
) {
    val stats = TranslationStats()
    private val all get() = listOf(primary) + fallbacks
    val failedChunks get() = all.sumOf { it.failed }

    private class Item(val masked: Masked, val chunks: List<String>)
    private class Res(val text: String, val verdict: Verdict, val lost: Boolean)

    private fun rank(v: Verdict) = v.ordinal

    suspend fun translate(texts: List<String>, progress: (Int, Int) -> Unit): List<String> {
        val engines = all
        var used = -1
        var res: MutableList<Res>? = null
        var lastErr: PdfTransException? = null
        for ((i, e) in engines.withIndex()) {
            try {
                if (i > 0) e.prepare()
                res = run(e, texts, true, progress)
                used = i
                break
            } catch (x: PdfTransException) {
                lastErr = x
                // an engine that is down or out of quota hands over to the next one; a missing
                // model on the main engine is the user's to fix, so that one is thrown
                val switchable = x.code == "engine_failing" || x.code == "quota" || (i > 0 && x.code == "model_missing")
                if (!switchable || i == engines.lastIndex) throw x
            }
        }
        val results = res ?: throw (lastErr ?: PdfTransException("engine_failing"))
        stats.engine = engines[used].label
        stats.switched = used > 0

        var bad = results.indices.filter { results[it].verdict != Verdict.OK }
        var j = used + 1
        while (bad.isNotEmpty() && j < engines.size) {
            val e = engines[j++]
            val again = try {
                e.prepare()
                run(e, bad.map { texts[it] }, true) { _, _ -> }
            } catch (x: PdfTransException) {
                continue
            }
            val still = ArrayList<Int>()
            bad.forEachIndexed { k, idx ->
                if (again[k].verdict == Verdict.OK) { results[idx] = again[k]; stats.retried++ }
                else {
                    if (rank(again[k].verdict) < rank(results[idx].verdict)) results[idx] = again[k]
                    still.add(idx)
                }
            }
            bad = still
        }
        stats.weak = bad.size
        // an unusable result (blank / still English) is returned as the source text: the caller leaves it alone
        return results.mapIndexed { i, r ->
            if (r.verdict == Verdict.BLANK || r.verdict == Verdict.UNCHANGED) texts[i] else r.text
        }
    }

    private suspend fun run(e: Engine, texts: List<String>, masking: Boolean, progress: (Int, Int) -> Unit): MutableList<Res> {
        val items = texts.map { prepareItem(it, e.chunkChars, masking) }
        val flat = items.flatMap { it.chunks }
        val out = e.translate(flat, progress)
        val results = ArrayList<Res>(items.size)
        var k = 0
        for (item in items) {
            val parts = out.subList(k, k + item.chunks.size)
            k += item.chunks.size
            results.add(assemble(item, parts))
        }
        if (masking) {
            val redo = results.indices.filter { results[it].lost }
            if (redo.isNotEmpty()) {        // an engine mangled a placeholder: translate those without protection
                val plain = run(e, redo.map { texts[it] }, false) { _, _ -> }
                redo.forEachIndexed { n, i -> results[i] = plain[n] }
            }
        }
        return results
    }

    private fun prepareItem(src: String, chunkChars: Int, masking: Boolean): Item {
        val m = if (masking) mask(src, glossary) else Masked(src, emptyList())
        return Item(m, packChunks(splitSentences(m.text), chunkChars).ifEmpty { listOf(m.text) })
    }

    private fun assemble(item: Item, parts: List<String>): Res {
        val raw = parts.joinToString(" ").trim()
        val verdict = judge(item.masked.text, raw)
        val fixed = convertDigits(fixArabic(raw), digits)
        val restored = unmask(fixed, item.masked.tokens)
        return if (restored == null) Res(fixed, verdict, true) else Res(restored, verdict, false)
    }
}
