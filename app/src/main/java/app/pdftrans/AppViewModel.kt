package app.pdftrans

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pdftrans.engine.LocalModel
import app.pdftrans.engine.makeEngines
import app.pdftrans.pdf.PdfTranslator
import app.pdftrans.pdf.extractPageLines
import app.pdftrans.pdf.openPdf
import app.pdftrans.pdf.pageHasImage
import app.pdftrans.ui.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface RunState {
    data object Idle : RunState
    data class Working(val stage: Stage, val done: Int, val total: Int) : RunState
    data class Done(val file: File, val report: Report, val mode: Mode) : RunState
    data class Failed(val message: String) : RunState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val translator = PdfTranslator(app)
    private var job: Job? = null
    private var settingsFrom = Screen.HOME

    var settings by mutableStateOf(prefs.load()); private set
    var screen by mutableStateOf(Screen.HOME); private set
    var pdf by mutableStateOf<PdfInfo?>(null); private set
    var pages by mutableStateOf("")
    var run by mutableStateOf<RunState>(RunState.Idle); private set
    var model by mutableStateOf(ModelStatus.UNKNOWN); private set
    var importing by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    val results = mutableStateListOf<File>()

    init { loadResults() }

    // ---------------------------------------------------------------- helpers
    private fun resultsDir() = File(getApplication<Application>().filesDir, "results").apply { mkdirs() }

    fun loadResults() {
        results.clear()
        results.addAll(resultsDir().listFiles { f -> f.extension == "pdf" }?.sortedByDescending { it.lastModified() } ?: emptyList())
    }

    fun update(f: (AppSettings) -> AppSettings) {
        settings = f(settings)
        prefs.save(settings)
    }

    fun consumeMessage() { message = null }
    private fun say(text: String) { message = text }
    private fun errorText(e: PdfTransException) = Strings.error(settings.lang, e.code, e.detail)

    // ------------------------------------------------------------- navigation
    fun openSettings() { settingsFrom = screen; screen = Screen.SETTINGS; refreshModel() }

    fun back() {
        when (screen) {
            Screen.SETTINGS -> screen = settingsFrom
            Screen.SETUP -> screen = Screen.HOME
            Screen.RUN -> if (run !is RunState.Working) screen = if (pdf != null) Screen.SETUP else Screen.HOME
            Screen.HOME -> Unit
        }
    }

    fun newFile() { run = RunState.Idle; pdf = null; screen = Screen.HOME }

    // ------------------------------------------------------------------ import
    fun importPdf(uri: Uri) {
        if (run is RunState.Working) return
        importing = true
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { copyAndInspect(uri) }
                pdf = info
                pages = ""
                run = RunState.Idle
                screen = Screen.SETUP
                refreshModel()
            } catch (e: PdfTransException) {
                say(errorText(e))
            } catch (e: Exception) {
                say(Strings.get(settings.lang, "err_generic"))
            } finally {
                importing = false
            }
        }
    }

    private fun copyAndInspect(uri: Uri): PdfInfo {
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "in").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "input.pdf")
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            ?: throw PdfTransException("cannot_open")
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "document.pdf"
        val doc = openPdf(f)
        try {
            var scanned = 0
            for (i in 0 until minOf(doc.numberOfPages, 30)) {            // quick sample of the first pages
                val (lines, _) = extractPageLines(doc, i)
                if (lines.sumOf { l -> l.text.count { !it.isWhitespace() } } < 25 && pageHasImage(doc.getPage(i))) scanned++
            }
            return PdfInfo(f, name, doc.numberOfPages, Math.round(f.length() / 1_048_576.0 * 100) / 100.0, scanned)
        } finally {
            doc.close()
        }
    }

    // ----------------------------------------------------------- local model
    fun refreshModel() {
        viewModelScope.launch {
            if (model == ModelStatus.DOWNLOADING) return@launch
            model = try {
                if (LocalModel.isDownloaded()) ModelStatus.READY else ModelStatus.MISSING
            } catch (e: Exception) {
                ModelStatus.UNKNOWN
            }
        }
    }

    fun downloadModel() {
        if (model == ModelStatus.DOWNLOADING) return
        model = ModelStatus.DOWNLOADING
        viewModelScope.launch {
            try {
                LocalModel.download()
                model = ModelStatus.READY
            } catch (e: Exception) {
                model = ModelStatus.MISSING
                say(Strings.error(settings.lang, "engine_failing", ""))
            }
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            try { LocalModel.delete() } catch (e: Exception) { }
            refreshModel()
        }
    }

    // ------------------------------------------------------------------- run
    fun start() {
        val info = pdf ?: return
        val s = settings
        val opts = Options(
            mode = s.mode, font = s.font, size = s.size, color = s.color, bold = s.bold, align = s.align,
            pages = pages.trim().ifEmpty { null }, ocr = s.ocr, digits = s.digits
        )
        val engines = try {
            opts.validate()
            parsePages(opts.pages, info.pages)
            makeEngines(s)
        } catch (e: PdfTransException) {
            say(errorText(e)); return
        }
        screen = Screen.RUN
        run = RunState.Working(Stage.EXTRACT, 0, 1)
        val stem = File(info.name).nameWithoutExtension.ifBlank { "document" }
        var out = File(resultsDir(), "${stem}_ar.pdf")
        if (out.exists()) out = File(resultsDir(), "${stem}_ar_${System.currentTimeMillis() / 1000}.pdf")
        val target = out
        job = viewModelScope.launch {
            try {
                val rep = translator.run(info.file, target, opts, engines, parseGlossary(s.glossary)) { st, d, t -> run = RunState.Working(st, d, t) }
                loadResults()
                run = RunState.Done(target, rep, opts.mode)
            } catch (e: CancellationException) {
                target.delete()
                run = RunState.Idle
                screen = Screen.SETUP
                throw e
            } catch (e: PdfTransException) {
                target.delete()
                run = RunState.Failed(errorText(e))
            } catch (e: Throwable) {
                target.delete()
                run = RunState.Failed(Strings.get(settings.lang, "err_generic"))
            } finally {
                engines.close()
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun deleteResult(f: File) { f.delete(); loadResults() }

    fun toast(key: String) = say(Strings.get(settings.lang, key))
}
