@file:OptIn(ExperimentalLayoutApi::class)

package app.pdftrans.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pdftrans.*
import java.io.File
import java.text.DateFormat
import java.util.Date

// ============================================================== root

@Composable
fun App(vm: AppViewModel, onPick: () -> Unit, onOpen: (File) -> Unit, onShare: (File) -> Unit, onSave: (File) -> Unit) {
    val s = vm.settings
    val dark = when (s.theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val dir = if (s.lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLang provides s.lang, LocalLayoutDirection provides dir) {
        PdfTransTheme(dark) {
            val snack = remember { SnackbarHostState() }
            LaunchedEffect(vm.message) {
                vm.message?.let { snack.showSnackbar(it); vm.consumeMessage() }
            }
            BackHandler(enabled = vm.screen != Screen.HOME) { vm.back() }
            Scaffold(
                snackbarHost = { SnackbarHost(snack) },
                containerColor = MaterialTheme.colorScheme.background
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (vm.screen) {
                        Screen.HOME -> HomeScreen(vm, onPick, onOpen, onShare)
                        Screen.SETUP -> SetupScreen(vm)
                        Screen.RUN -> RunScreen(vm, onOpen, onShare, onSave)
                        Screen.SETTINGS -> SettingsScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("back")) }
        Text(
            title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

// ============================================================== home

@Composable
private fun HomeScreen(vm: AppViewModel, onPick: () -> Unit, onOpen: (File) -> Unit, onShare: (File) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { vm.openSettings() }) {
                Icon(Icons.Rounded.Settings, contentDescription = t("settings_title"))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                Text(t("home_title"), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(10.dp))
                Text(t("home_sub"), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            }
            PageDiagram(Mode.FACING, Modifier.size(width = 104.dp, height = 140.dp))
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onPick, enabled = !vm.importing,
            modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp)
        ) {
            if (vm.importing) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = cs.onPrimary)
            } else {
                Icon(Icons.Rounded.PictureAsPdf, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(t("pick"), style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Note(t("pick_hint"))
        SectionTitle(t("recent"), Modifier.padding(top = 36.dp))
        if (vm.results.isEmpty()) {
            Note(t("recent_empty"))
        } else {
            vm.results.forEach { f -> ResultRow(f, onOpen, onShare) { vm.deleteResult(f) } }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ResultRow(f: File, onOpen: (File) -> Unit, onShare: (File) -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(f) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = cs.primary) }
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.lastModified())),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
            )
        }
        IconButton(onClick = { onShare(f) }) { Icon(Icons.Rounded.Share, contentDescription = t("share")) }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = t("delete")) }
    }
}

// ============================================================== setup

private val SWATCHES = listOf(
    "c_blue" to "#0b3d91", "c_black" to "#000000", "c_red" to "#b00020",
    "c_green" to "#1b5e20", "c_purple" to "#4a148c", "c_orange" to "#e65100"
)
private val SIZES = listOf<Float?>(null, 8f, 10f, 12f, 14f, 16f, 20f)

private fun hex(h: String) = Color(android.graphics.Color.parseColor(h))

@Composable
private fun SetupScreen(vm: AppViewModel) {
    val info = vm.pdf ?: return
    val s = vm.settings
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    val blocker: String? = when (s.engine) {
        EngineKind.ONDEVICE -> if (vm.model != ModelStatus.READY) t("need_model") else null
        EngineKind.MICROSOFT, EngineKind.GOOGLE -> if (!s.allowGoogle) t("need_google") else null
        EngineKind.LIBRE, EngineKind.MYMEMORY -> null
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(info.name, vm::back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            // ---- file
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.primaryContainer).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = cs.primary, modifier = Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = cs.onPrimaryContainer)
                    Text(t("file_meta", info.pages, info.sizeMb), style = MaterialTheme.typography.bodyMedium,
                        color = cs.onPrimaryContainer)
                }
            }
            if (info.scanned > 0) {
                Spacer(Modifier.height(10.dp))
                Note(if (s.ocr) t("scanned_ok", info.scanned) else t("scanned_no", info.scanned))
            }

            // ---- engine
            SectionTitle(t("sec_engine"))
            Segmented(
                listOf(t("on_device"), t("online")), if (s.engine == EngineKind.ONDEVICE) 0 else 1,
                { i -> vm.update { it.copy(engine = if (i == 0) EngineKind.ONDEVICE else it.lastOnline) } }
            )
            Spacer(Modifier.height(12.dp))
            if (s.engine == EngineKind.ONDEVICE) {
                Note(t("on_device_note"))
                Spacer(Modifier.height(10.dp))
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val ready = vm.model == ModelStatus.READY
                        Text(
                            when (vm.model) {
                                ModelStatus.READY -> t("model_ready")
                                ModelStatus.MISSING -> t("model_missing")
                                ModelStatus.DOWNLOADING -> t("downloading")
                                ModelStatus.UNKNOWN -> t("model_checking")
                            },
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            color = if (ready) cs.secondary else cs.onSurface
                        )
                        if (vm.model == ModelStatus.DOWNLOADING) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else if (vm.model != ModelStatus.READY) {
                            OutlinedButton(onClick = { vm.downloadModel() }) { Text(t("download_model")) }
                        } else {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = cs.secondary)
                        }
                    }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        EngineKind.MICROSOFT to "eng_microsoft", EngineKind.GOOGLE to "eng_google",
                        EngineKind.LIBRE to "eng_libre", EngineKind.MYMEMORY to "eng_mymemory"
                    ).forEach { (k, label) ->
                        FilterChip(
                            selected = s.engine == k,
                            onClick = { vm.update { it.copy(engine = k, lastOnline = k) } },
                            label = { Text(t(label)) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Note(
                    when (s.engine) {
                        EngineKind.MICROSOFT -> t("note_microsoft")
                        EngineKind.GOOGLE -> t("note_google")
                        EngineKind.MYMEMORY -> t("note_mymemory")
                        else -> t("note_libre")
                    }
                )
                if ((s.engine == EngineKind.GOOGLE || s.engine == EngineKind.MICROSOFT) && !s.allowGoogle) {
                    Spacer(Modifier.height(10.dp))
                    Panel {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(t("google_warn"), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { vm.update { it.copy(allowGoogle = true) } }) { Text(t("google_allow")) }
                        }
                    }
                }
            }

            // ---- layout
            SectionTitle(t("sec_layout"))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard(Mode.FACING, t("mode_facing"), t("mode_facing_d"), s.mode == Mode.FACING) { vm.update { it.copy(mode = Mode.FACING) } }
                ModeCard(Mode.SMART, t("mode_smart"), t("mode_smart_d"), s.mode == Mode.SMART) { vm.update { it.copy(mode = Mode.SMART) } }
                ModeCard(Mode.REPLACE, t("mode_replace"), t("mode_replace_d"), s.mode == Mode.REPLACE) { vm.update { it.copy(mode = Mode.REPLACE) } }
                ModeCard(Mode.PARAGRAPH, t("mode_para"), t("mode_para_d"), s.mode == Mode.PARAGRAPH) { vm.update { it.copy(mode = Mode.PARAGRAPH) } }
            }

            // ---- style
            SectionTitle(t("sec_style"))
            val family = remember(s.font) { previewFamily(ctx, s.font) }
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        t("sample_en"), color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(),
                        style = TextStyle(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left)
                    )
                    Text(
                        t("sample_ar"), fontFamily = family, fontSize = ((s.size ?: 12f) * 1.2f).sp,
                        fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
                        color = hex(s.color ?: DEFAULT_COLOR), modifier = Modifier.fillMaxWidth(),
                        style = TextStyle(
                            textDirection = TextDirection.Rtl,
                            textAlign = when (s.align) { Align.RIGHT -> TextAlign.Right; Align.CENTER -> TextAlign.Center; Align.LEFT -> TextAlign.Left },
                            lineHeight = ((s.size ?: 12f) * 1.9f).sp
                        )
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(t("size"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SIZES.forEach { v ->
                    FilterChip(selected = s.size == v, onClick = { vm.update { it.copy(size = v) } },
                        label = { Text(if (v == null) t("auto") else v.toInt().toString()) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(t("color"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.color == null, onClick = { vm.update { it.copy(color = null) } }, label = { Text(t("auto")) })
                SWATCHES.forEach { (k, h) ->
                    Swatch(hex(h), s.color?.lowercase() == h, t(k)) { vm.update { it.copy(color = h) } }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(t("font"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontKey.values().forEach { f ->
                    FilterChip(selected = s.font == f, onClick = { vm.update { it.copy(font = f) } }, label = { Text(f.label) })
                }
            }
            SwitchRow(t("bold"), s.bold) { b -> vm.update { it.copy(bold = b) } }
            Spacer(Modifier.height(8.dp))
            Text(t("align"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            val aligns = listOf(Align.RIGHT, Align.CENTER, Align.LEFT)
            Segmented(listOf(t("a_right"), t("a_center"), t("a_left")), aligns.indexOf(s.align),
                { i -> vm.update { it.copy(align = aligns[i]) } })

            // ---- pages
            SectionTitle(t("pages"))
            OutlinedTextField(
                value = vm.pages, onValueChange = { vm.pages = it }, singleLine = true,
                placeholder = { Text(t("pages_hint")) }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(28.dp))
        }

        // ---- pinned action
        Column(Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 24.dp, vertical = 12.dp)) {
            if (blocker != null) {
                Text(blocker, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            }
            Button(
                onClick = { vm.start() }, enabled = blocker == null,
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)
            ) { Text(t("start"), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ============================================================== run

@Composable
private fun RunScreen(vm: AppViewModel, onOpen: (File) -> Unit, onShare: (File) -> Unit, onSave: (File) -> Unit) {
    val r = vm.run
    val view = LocalView.current
    val working = r is RunState.Working
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }
    when (r) {
        is RunState.Working -> WorkingView(vm, r)
        is RunState.Done -> DoneView(vm, r, onOpen, onShare, onSave)
        is RunState.Failed -> FailedView(vm, r)
        RunState.Idle -> Unit
    }
}

@Composable
private fun WorkingView(vm: AppViewModel, r: RunState.Working) {
    val cs = MaterialTheme.colorScheme
    val d = r.done.toFloat()
    val total = r.total.coerceAtLeast(1).toFloat()
    val frac = when (r.stage) {
        Stage.EXTRACT -> 0.10f * d / total
        Stage.TRANSLATE -> 0.10f + 0.70f * d / total
        Stage.RENDER -> 0.80f + 0.20f * d / total
    }.coerceIn(0f, 1f)
    val anim by animateFloatAsState(frac, tween(350), label = "progress")
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        PageDiagram(vm.settings.mode, Modifier.size(width = 176.dp, height = 232.dp), progress = anim)
        Spacer(Modifier.height(32.dp))
        Text(t("working", vm.pdf?.name ?: ""), style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = { anim }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            trackColor = cs.surfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(
            t(when (r.stage) { Stage.EXTRACT -> "stage_extract"; Stage.TRANSLATE -> "stage_translate"; Stage.RENDER -> "stage_render" },
                r.done, r.total),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(t("keep_open"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = { vm.cancel() }, shape = RoundedCornerShape(14.dp)) { Text(t("cancel")) }
    }
}

@Composable
private fun DoneView(vm: AppViewModel, r: RunState.Done, onOpen: (File) -> Unit, onShare: (File) -> Unit, onSave: (File) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val rep = r.report
    val notes = buildList {
        if (rep.ocrPages.isNotEmpty()) add(t("note_ocr", rep.ocrPages.size))
        if (rep.scannedUnprocessed.isNotEmpty()) add(t("note_scanned_no", rep.scannedUnprocessed.size))
        if (rep.engine.isNotEmpty()) add(t("note_engine", rep.engine))
        if (rep.switchedEngine) add(t("note_switched", rep.engine))
        if (rep.fellBack.isNotEmpty()) add(t("note_fell", rep.fellBack.size, rep.fellBack.take(12).joinToString(", ")))
        if (rep.expandedPages > 0) add(t("note_expanded", rep.expandedPages))
        if (rep.retriedSegments > 0) add(t("note_retried", rep.retriedSegments))
        if (rep.weakSegments > 0) add(t("note_weak", rep.weakSegments))
        if (rep.failedSegments > 0) add(t("note_failed", rep.failedSegments))
        if (r.mode == Mode.PARAGRAPH) add(t("note_para"))
        if (r.mode == Mode.REPLACE) add(t("note_replace"))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(56.dp).clip(CircleShape).background(cs.secondary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = cs.onSecondary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(t("done_title"), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(t("done_meta", rep.pagesDone, String.format("%.1f", rep.seconds)), style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onOpen(r.file) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
            Text(t("open"), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onShare(r.file) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text(t("share")) }
            OutlinedButton(onClick = { onSave(r.file) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text(t("save_copy")) }
        }
        if (notes.isNotEmpty()) {
            SectionTitle(t("notes"))
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { vm.newFile() }) { Text(t("another")) }
    }
}

@Composable
private fun FailedView(vm: AppViewModel, r: RunState.Failed) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(t("failed_title"), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Panel { Text(r.message, style = MaterialTheme.typography.bodyLarge, color = cs.error) }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            Text(t("back"))
        }
    }
}

// ============================================================== settings

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    val s = vm.settings
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        TopBar(t("settings_title"), vm::back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            SectionTitle(t("language"), Modifier.padding(top = 8.dp))
            Segmented(listOf("العربية", "English"), if (s.lang == "ar") 0 else 1,
                { i -> vm.update { it.copy(lang = if (i == 0) "ar" else "en") } })

            SectionTitle(t("theme"))
            val themes = listOf("system", "light", "dark")
            Segmented(listOf(t("th_system"), t("th_light"), t("th_dark")), themes.indexOf(s.theme).coerceAtLeast(0),
                { i -> vm.update { it.copy(theme = themes[i]) } })

            SectionTitle(t("sec_reading"))
            SwitchRow(t("ocr_toggle"), s.ocr) { b -> vm.update { it.copy(ocr = b) } }

            SectionTitle(t("sec_model"))
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when (vm.model) {
                            ModelStatus.READY -> t("model_ready")
                            ModelStatus.MISSING -> t("model_missing")
                            ModelStatus.DOWNLOADING -> t("downloading")
                            ModelStatus.UNKNOWN -> t("model_checking")
                        }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
                    )
                    when (vm.model) {
                        ModelStatus.READY -> OutlinedButton(onClick = { vm.deleteModel() }) { Text(t("delete_model")) }
                        ModelStatus.DOWNLOADING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else -> OutlinedButton(onClick = { vm.downloadModel() }) { Text(t("download_model")) }
                    }
                }
            }

            SectionTitle(t("sec_online"))
            SwitchRow(t("google_toggle"), s.allowGoogle) { b -> vm.update { it.copy(allowGoogle = b) } }
            Note(t("note_unofficial"))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = s.libreUrl, onValueChange = { v -> vm.update { it.copy(libreUrl = v) } }, singleLine = true,
                label = { Text(t("libre_url")) }, placeholder = { Text("https://libretranslate.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = s.libreKey, onValueChange = { v -> vm.update { it.copy(libreKey = v) } }, singleLine = true,
                label = { Text(t("libre_key")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = s.memoryEmail, onValueChange = { v -> vm.update { it.copy(memoryEmail = v) } }, singleLine = true,
                label = { Text(t("memory_email")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )

            SectionTitle(t("sec_quality"))
            SwitchRow(t("cloud_retry"), s.cloudRetry) { b -> vm.update { it.copy(cloudRetry = b) } }
            Note(t("cloud_retry_note"))
            Spacer(Modifier.height(14.dp))
            Text(t("digits"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            val digitOpts = listOf(Digits.LATIN, Digits.ARABIC)
            Segmented(listOf(t("d_latin"), t("d_arabic")), digitOpts.indexOf(s.digits),
                { i -> vm.update { it.copy(digits = digitOpts[i]) } })
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = s.glossary, onValueChange = { v -> vm.update { it.copy(glossary = v) } },
                label = { Text(t("glossary")) }, placeholder = { Text("machine learning = تعلم الآلة") },
                minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(6.dp))
            Note(t("glossary_note"))

            SectionTitle(t("sec_about"))
            Note(t("about_text"))
            Spacer(Modifier.height(32.dp))
        }
    }
}
