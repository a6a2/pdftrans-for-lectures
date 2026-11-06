package app.pdftrans

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.pdftrans.ui.App
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var pendingSave: File? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importPdf(it) }
    }

    private val saver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = pendingSave
        pendingSave = null
        if (uri != null && f != null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                }
                vm.toast("saved")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            App(
                vm = vm,
                onPick = { picker.launch(arrayOf("application/pdf")) },
                onOpen = { open(it) },
                onShare = { share(it) },
                onSave = { f -> pendingSave = f; saver.launch(f.name) }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        uri?.let { vm.importPdf(it) }
    }

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun open(f: File) {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(f), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (e: ActivityNotFoundException) { vm.toast("no_viewer") }
    }

    private fun share(f: File) {
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uriFor(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, null))
    }
}
