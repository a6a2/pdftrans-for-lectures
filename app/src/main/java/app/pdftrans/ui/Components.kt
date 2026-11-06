package app.pdftrans.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.pdftrans.Mode
import kotlin.math.max
import kotlin.math.min

/** Page-diagram line: vertical position, length (both 0..1) and whether it's Arabic (ink). */
private class Ln(val y: Float, val w: Float, val ink: Boolean)

private fun diagramLines(mode: Mode): List<Ln> = when (mode) {
    Mode.FACING -> listOf(
        Ln(.20f, .78f, false), Ln(.32f, .62f, false), Ln(.44f, .74f, false),
        Ln(.56f, .58f, false), Ln(.68f, .70f, false), Ln(.80f, .50f, false)
    )
    Mode.REPLACE -> listOf(
        Ln(.20f, .70f, true), Ln(.32f, .62f, true), Ln(.44f, .74f, true),
        Ln(.56f, .58f, true), Ln(.68f, .70f, true), Ln(.80f, .52f, true)
    )
    Mode.SMART -> listOf(
        Ln(.20f, .70f, false), Ln(.31f, .62f, true),
        Ln(.46f, .74f, false), Ln(.57f, .66f, true),
        Ln(.72f, .60f, false), Ln(.83f, .72f, true)
    )
    Mode.PARAGRAPH -> listOf(
        Ln(.18f, .72f, false), Ln(.25f, .74f, false), Ln(.32f, .50f, false),
        Ln(.43f, .66f, true), Ln(.50f, .70f, true), Ln(.57f, .46f, true),
        Ln(.69f, .72f, false), Ln(.76f, .60f, false), Ln(.87f, .64f, true)
    )
}

/**
 * A tiny page: English lines in grey grow from the left, Arabic lines in ink grow from the right
 * (the way each is written). [progress] reveals the Arabic lines one after another, so the same
 * drawing doubles as the progress indicator while a file is translating.
 */
@Composable
fun PageDiagram(mode: Mode, modifier: Modifier = Modifier, progress: Float = 1f) {
    val cs = MaterialTheme.colorScheme
    val paper = cs.surface
    val edge = cs.outline
    val grey = cs.onSurfaceVariant.copy(alpha = 0.45f)
    val ink = cs.primary
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(min(w, h) * 0.09f)
        drawRoundRect(paper, size = Size(w, h), cornerRadius = r)
        drawRoundRect(edge, size = Size(w, h), cornerRadius = r, style = Stroke(1.2.dp.toPx()))
        val pad = w * 0.13f
        val inner = w - 2 * pad
        val stroke = max(2f, h * 0.026f)
        val lines = diagramLines(mode)
        if (mode == Mode.FACING) {
            // two facing pages: the original on the left, its Arabic growing in from the right
            val mid = w / 2f
            drawLine(edge, Offset(mid, h * 0.08f), Offset(mid, h * 0.92f), 1.2.dp.toPx())
            val half = mid - pad - pad * 0.5f
            lines.forEachIndexed { i, l ->
                val y = h * l.y
                drawLine(grey, Offset(pad, y), Offset(pad + half * l.w, y), stroke, StrokeCap.Round)
                val reveal = (progress * lines.size - i).coerceIn(0f, 1f)
                if (reveal > 0f) drawLine(ink, Offset(w - pad, y), Offset(w - pad - half * l.w * reveal, y), stroke, StrokeCap.Round)
            }
            return@Canvas
        }
        val inkTotal = lines.count { it.ink }
        var inkIdx = 0
        for (l in lines) {
            val y = h * l.y
            if (!l.ink) {
                drawLine(grey, Offset(pad, y), Offset(pad + inner * l.w, y), stroke, StrokeCap.Round)
            } else {
                val reveal = (progress * inkTotal - inkIdx).coerceIn(0f, 1f)
                inkIdx++
                if (reveal > 0f) {
                    val len = inner * l.w * reveal
                    drawLine(ink, Offset(w - pad, y), Offset(w - pad - len, y), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
fun Segmented(
    options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (sel) cs.surface else Color.Transparent)
                    .selectable(selected = sel, onClick = { onSelect(i) }, role = Role.Tab)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge,
                    color = if (sel) cs.primary else cs.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ModeCard(mode: Mode, title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) cs.primaryContainer else cs.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outline, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageDiagram(mode, Modifier.size(width = 54.dp, height = 70.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(top = 28.dp, bottom = 10.dp)
    )
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier.fillMaxWidth())
}

@Composable
fun Swatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) cs.onSurface else cs.outline, CircleShape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.fillMaxWidth().clip(shape).background(cs.surface).border(1.dp, cs.outline, shape).padding(16.dp)
    ) { content() }
}
