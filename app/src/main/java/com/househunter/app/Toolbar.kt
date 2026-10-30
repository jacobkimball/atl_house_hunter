package com.househunter.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFF1F2328)
private val BarColor = Color(0xF2FFFFFF)

/** The bottom tool bar: Pen, Eraser, Mark, and a "center on me" action. One tool is active at a time. */
@Composable
fun ToolBar(
    tool: Tool,
    penColor: Int,
    showMe: Boolean,
    onPen: () -> Unit,
    onEraser: () -> Unit,
    onMark: () -> Unit,
    onMe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(30.dp), color = BarColor, shadowElevation = 8.dp) {
        Row(
            Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton("Pen", tool == Tool.Pen, Color(android.graphics.Color.parseColor(PenColors.hex[penColor])).copy(alpha = 0.28f), onPen) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(PenColors.hex[penColor]))).border(1.dp, Ink.copy(alpha = 0.35f), CircleShape))
            }
            ToolButton("Eraser", tool == Tool.Eraser, Color(0xFFE3E5E8), onEraser)
            ToolButton("Mark", tool == Tool.Mark, Color(MarkerLayer.PIN_COLOR), onMark, selectedContent = Color.White)
            if (showMe) ToolButton("Me", false, Color.Transparent, onMe)
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    selected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    selectedContent: Color = Ink,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = if (selected) selectedColor else Color.Transparent,
        contentColor = if (selected) selectedContent else Ink,
    ) {
        Row(
            Modifier.height(48.dp).widthIn(min = 64.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The six pen colors (requirement PEN-1). The selected one gets a heavier ring. */
@Composable
fun PenPalette(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(30.dp), color = BarColor, shadowElevation = 8.dp) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PenColors.hex.forEachIndexed { i, hex ->
                val isSelected = i == selected
                Box(
                    Modifier
                        .size(46.dp)
                        .semantics { contentDescription = PenColors.names[i] + if (isSelected) ", selected" else "" }
                        .border(if (isSelected) 3.dp else 0.dp, if (isSelected) Ink else Color.Transparent, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(hex)))
                        .border(1.dp, Ink.copy(alpha = 0.3f), CircleShape)
                        .clickable { onSelect(i) }
                )
            }
        }
    }
}

/** Undo / Redo (PEN-8), plus "Clear all" while the eraser is active (ERS-6). */
@Composable
fun HistoryPill(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearAll: (() -> Unit)?,
    clearEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = BarColor, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
            TextButton(onClick = onRedo, enabled = canRedo) { Text("Redo") }
            if (onClearAll != null) TextButton(onClick = onClearAll, enabled = clearEnabled) { Text("Clear all", color = if (clearEnabled) MaterialTheme.colorScheme.error else Color.Unspecified) }
        }
    }
}

/** A ring showing what the eraser will remove (ERS-4: a generous circle, not a precise point). */
@Composable
fun EraserRing(center: Offset, radiusPx: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(Color.White, radius = radiusPx, center = center, style = DrawStroke(width = 5.dp.toPx()))
        drawCircle(Color(0xFF1B1B1B), radius = radiusPx, center = center, style = DrawStroke(width = 2.dp.toPx()))
    }
}

/** Snap to street on/off (PEN-6): on, the pen follows the street you're near; off, it draws exactly where you drag. */
@Composable
fun SnapToggle(snap: Boolean, ready: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onToggle,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = if (snap) Color(0xFF1F2328) else BarColor,
        contentColor = if (snap) Color.White else Ink,
        shadowElevation = 6.dp,
    ) {
        Text(
            when {
                !snap -> "Snap to streets: off"
                ready -> "Snap to streets: on"
                else -> "Snap to streets: loading…"
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Which drawing layer new highlights go onto (Pen mode). A layer that is switched off shows dimmed. */
@Composable
fun LayerChips(
    layers: List<DrawingLayer>,
    activeId: String,
    hidden: Set<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = BarColor, shadowElevation = 6.dp) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Draw on", style = MaterialTheme.typography.labelMedium, color = Ink.copy(alpha = 0.7f))
            layers.forEach { layer ->
                FilterChip(
                    selected = layer.id == activeId,
                    onClick = { onSelect(layer.id) },
                    label = { Text(layer.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp)) },
                    modifier = Modifier.alpha(if (layer.id in hidden && layer.id != activeId) 0.5f else 1f),
                )
            }
        }
    }
}
