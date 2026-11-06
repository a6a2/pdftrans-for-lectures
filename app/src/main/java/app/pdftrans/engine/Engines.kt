package app.pdftrans.engine

import android.text.Html
import app.pdftrans.AppSettings
import app.pdftrans.EngineKind
import app.pdftrans.PdfTransException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ----------------------------------------------------------------------- HTTP

object Http {
    // a browser-like agent: some free endpoints rate-limit unknown agents much sooner
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

    fun get(url: String, headers: Map<String, String> = emptyMap()): String = call(url, "GET", null, null, headers)
    fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): String =
        call(url, "POST", body, contentType, headers)

    private fun call(url: String, method: String, body: String?, contentType: String?, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(160)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

// --------------------------------------------------------------- helper engine

abstract class PerTextEngine : Engine() {
    override val maxItems = 8
    private val gate = Semaphore(4)
    protected abstract suspend fun one(text: String): String

    override suspend fun translateBatch(texts: List<String>): List<String> = coroutineScope {
        texts.map { t -> async { gate.withPermit { one(t) } } }.awaitAll()
    }
}

// ------------------------------------------------------------ on-device engine

object LocalModel {
    private fun model() = TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()

    suspend fun isDownloaded(): Boolean = RemoteModelManager.getInstance().isModelDownloaded(model()).await()

    suspend fun download(wifiOnly: Boolean = false) {
        val cond = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        RemoteModelManager.getInstance().download(model(), cond).await()
    }

    suspend fun delete() {
        RemoteModelManager.getInstance().deleteDownloadedModel(model()).await()
    }
}

/** ML Kit on-device translation: no text leaves the phone once the model is downloaded. */
class MlKitEngine : Engine() {
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
    )
    override val label = "On device"
    override val chunkChars = 500          // short, sentence-aligned pieces: the small model drops text from long inputs
    override val maxItems = 10

    override suspend fun prepare() {
        if (!LocalModel.isDownloaded()) throw PdfTransException("model_missing")
    }

    override suspend fun translateBatch(texts: List<String>): List<String> =
        texts.map { translator.translate(it).await() }

    override fun close() = translator.close()
}

// ------------------------------------------------------------- online engines

/**
 * Microsoft Translator through the token the Edge browser uses: free, no key, good Arabic.
 * Unofficial: it may change or stop, and it is not covered by Microsoft's service terms.
 */
class MicrosoftEngine : Engine() {
    override val label = "Microsoft"
    override val chunkChars = 1800
    override val maxItems = 25
    override val maxChars = 6000
    override val retryDelayMs = 1200L

    private var token: String? = null
    private var tokenAt = 0L

    private suspend fun auth(): String {
        val t = token
        if (t != null && System.currentTimeMillis() - tokenAt < 8 * 60_000L) return t
        val fresh = withContext(Dispatchers.IO) { Http.get("https://edge.microsoft.com/translate/auth") }.trim()
        if (fresh.length < 20) throw IOException("empty token")
        token = fresh
        tokenAt = System.currentTimeMillis()
        return fresh
    }

    override suspend fun translateBatch(texts: List<String>): List<String> {
        val body = JSONArray().also { a -> texts.forEach { a.put(JSONObject().put("Text", it)) } }.toString()
        var last: Exception? = null
        for (host in listOf("https://api-edge.cognitive.microsofttranslator.com", "https://api.cognitive.microsofttranslator.com")) {
            try {
                val bearer = auth()
                val url = "$host/translate?from=en&to=ar&api-version=3.0&textType=plain"
                val resp = withContext(Dispatchers.IO) {
                    Http.post(url, body, "application/json; charset=UTF-8", mapOf("Authorization" to "Bearer $bearer"))
                }
                val arr = JSONArray(resp)
                return List(arr.length()) { i ->
                    arr.getJSONObject(i).getJSONArray("translations").getJSONObject(0).getString("text")
                }
            } catch (e: IOException) {
                val m = e.message ?: ""
                if (m.contains("HTTP 401") || m.contains("HTTP 403")) token = null    // expired: fetch a new one
                last = e
            } catch (e: JSONException) {
                last = e
            }
        }
        throw last ?: IOException("Microsoft translator unreachable")
    }
}

/** Unofficial Google web endpoint. Opt-in only: may break or violate Google's ToS. */
class GoogleEngine : PerTextEngine() {
    override val label = "Google"
    override val chunkChars = 1500

    override suspend fun one(text: String): String {
        val out = try {
            viaGtx(text)
        } catch (e: IOException) {
            val m = e.message ?: ""
            if (m.contains("HTTP 429") || m.contains("HTTP 403")) viaDictChrome(text) else throw e
        }
        delay(80)
        return out
    }

    private suspend fun viaGtx(text: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t"
        val body = withContext(Dispatchers.IO) {
            Http.post(url, "q=" + enc(text), "application/x-www-form-urlencoded; charset=UTF-8")
        }
        val arr = JSONArray(body).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val seg = arr.optJSONArray(i) ?: continue
            if (!seg.isNull(0)) sb.append(seg.getString(0))
        }
        return sb.toString()
    }

    /** Second keyless endpoint, tried when the first one answers 429/403. Best effort: the format is tolerated loosely. */
    private suspend fun viaDictChrome(text: String): String {
        val url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=ar&q=" + enc(text)
        val body = withContext(Dispatchers.IO) { Http.get(url) }
        try {
            val v = JSONTokener(body).nextValue()
            if (v is JSONObject) {
                val s = v.optJSONArray("sentences")
                if (s != null) {
                    val sb = StringBuilder()
                    for (i in 0 until s.length()) sb.append(s.getJSONObject(i).optString("trans"))
                    if (sb.isNotEmpty()) return sb.toString()
                }
            } else if (v is JSONArray) {
                val first = v.opt(0)
                if (first is JSONArray) return first.getString(0)
                if (first is String) return first
            }
        } catch (e: JSONException) { /* fall through */ }
        throw IOException("unexpected Google response")
    }
}

/** MyMemory: official free API. ~5k chars/day anonymous, ~50k/day when an email is supplied. */
class MyMemoryEngine(private val email: String) : PerTextEngine() {
    override val label = "MyMemory"
    override val chunkChars = 450

    private fun chunk(text: String, limit: Int = 450): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?;:])\\s+"))) {
            if (sb.isNotEmpty() && sb.length + sentence.length + 1 > limit) { out.add(sb.toString()); sb.clear() }
            if (sentence.length > limit) { sentence.chunked(limit).forEach { out.add(it) }; continue }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(sentence)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    override suspend fun one(text: String): String {
        val parts = chunk(text).map { part ->
            var url = "https://api.mymemory.translated.net/get?q=${enc(part)}&langpair=en%7Car"
            if (email.isNotBlank()) url += "&de=${enc(email.trim())}"
            val json = JSONObject(withContext(Dispatchers.IO) { Http.get(url) })
            val status = json.opt("responseStatus")?.toString()
            var tt = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
            if (status == "429" || tt.contains("MYMEMORY WARNING", ignoreCase = true)) throw PdfTransException("quota")
            if (status != "200") throw IOException("MyMemory status $status")
            if (tt.contains('&')) {
                tt = Html.fromHtml(tt, Html.FROM_HTML_MODE_LEGACY).toString()
            }
            tt
        }
        return parts.joinToString(" ")
    }
}

/**
 * LibreTranslate. With your own address it uses that server; with the address left empty it rotates
 * through free public servers that need no key. Open source, but quality is below Microsoft / Google.
 */
class LibreEngine(userUrl: String, private val key: String) : Engine() {
    private val own = userUrl.isNotBlank()
    private val servers: List<String> =
        if (own) listOf(userUrl.trim().trimEnd('/')) else PUBLIC
    private var cur = 0

    override val label = if (own) "LibreTranslate" else "LibreTranslate (free)"
    override val chunkChars = 800
    override val maxItems = if (own) 30 else 8
    override val maxChars = 4000

    override suspend fun translateBatch(texts: List<String>): List<String> {
        var last: Exception? = null
        for (n in servers.indices) {
            val idx = (cur + n) % servers.size
            try {
                val payload = JSONObject().put("source", "en").put("target", "ar").put("format", "text")
                if (texts.size == 1) payload.put("q", texts[0]) else payload.put("q", JSONArray(texts))
                if (key.isNotBlank()) payload.put("api_key", key.trim())
                val body = withContext(Dispatchers.IO) { Http.post("${servers[idx]}/translate", payload.toString(), "application/json") }
                val t = JSONObject(body).get("translatedText")
                val res = if (t is JSONArray) List(t.length()) { t.getString(it) } else listOf(t.toString())
                cur = idx
                if (!own) delay(250)           // be gentle with volunteers' servers
                return res
            } catch (e: IOException) {
                last = e
            } catch (e: JSONException) {
                last = e
            }
        }
        throw last ?: IOException("no LibreTranslate server reachable")
    }

    companion object {
        /** Keyless public servers reported working in autumn 2026; the list may change over time. */
        val PUBLIC = listOf("https://translate.adminforge.de", "https://lt.pyrine.net")
    }
}

// ---------------------------------------------------------------- engine set

/** The engine the user picked, plus the engines allowed to take over for failures and weak results. */
class EngineSet(val primary: Engine, val fallbacks: List<Engine>) {
    fun close() {
        primary.close()
        fallbacks.forEach { it.close() }
    }
}

fun makeEngines(s: AppSettings): EngineSet {
    fun build(k: EngineKind): Engine? = when (k) {
        EngineKind.ONDEVICE -> MlKitEngine()
        EngineKind.MICROSOFT -> if (s.allowGoogle) MicrosoftEngine() else null
        EngineKind.GOOGLE -> if (s.allowGoogle) GoogleEngine() else null
        EngineKind.LIBRE -> LibreEngine(s.libreUrl, s.libreKey)
        EngineKind.MYMEMORY -> MyMemoryEngine(s.memoryEmail)
    }
    if ((s.engine == EngineKind.MICROSOFT || s.engine == EngineKind.GOOGLE) && !s.allowGoogle)
        throw PdfTransException("google_optin")
    val primary = build(s.engine) ?: throw PdfTransException("google_optin")
    val order = listOf(EngineKind.MICROSOFT, EngineKind.GOOGLE, EngineKind.LIBRE, EngineKind.MYMEMORY, EngineKind.ONDEVICE)
    val fallbackKinds = when {
        s.engine != EngineKind.ONDEVICE -> order.filter { it != s.engine }            // already online: any other may help
        s.cloudRetry -> order.filter { it != EngineKind.ONDEVICE }                    // on device: only if the user allowed it
        else -> emptyList()
    }
    return EngineSet(primary, fallbackKinds.mapNotNull { build(it) })
}
