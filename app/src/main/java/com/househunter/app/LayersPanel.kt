package com.househunter.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFF1F2328)
private val PanelColor = Color.White

/** The small "Layers" button that opens the panel. */
@Composable
fun LayersButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), color = Color(0xF2FFFFFF), contentColor = Ink, shadowElevation = 6.dp) {
        Text("Layers", modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge)
    }
}

/** Show/hide switches, with a legend swatch beside each so it doubles as the map key (MAP-7). */
@Composable
fun LayersPanel(
    hidden: Set<LayerGroup>,
    onToggle: (LayerGroup) -> Unit,
    drawingLayers: List<DrawingLayer>,
    hiddenDrawing: Set<String>,
    activeLayerId: String,
    strokeCounts: Map<String, Int>,
    onToggleDrawing: (String) -> Unit,
    onEditLayer: (String) -> Unit,
    onAddLayer: () -> Unit,
    onBackup: () -> Unit,
    onAbout: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.width(320.dp), shape = RoundedCornerShape(20.dp), color = PanelColor, contentColor = Ink, shadowElevation = 10.dp) {
        Column(
            Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Map layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Done") }
            }
            LayerGroup.values().forEach { group ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LegendSwatch(group)
                            Spacer(Modifier.width(10.dp))
                            Text(group.label, style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(
                            group.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.copy(alpha = 0.65f),
                            modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                        )
                    }
                    Switch(checked = group !in hidden, onCheckedChange = { onToggle(group) })
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Ink.copy(alpha = 0.12f))
            Text("My drawing layers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp))
            drawingLayers.forEach { layer ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onEditLayer(layer.id) }.padding(vertical = 4.dp)) {
                        Text(layer.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val count = strokeCounts[layer.id] ?: 0
                        Text(
                            (if (layer.id == activeLayerId) "Drawing here · " else "") + "$count highlight${if (count == 1) "" else "s"} · tap to rename",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.copy(alpha = 0.65f),
                        )
                    }
                    Switch(checked = layer.id !in hiddenDrawing, onCheckedChange = { onToggleDrawing(layer.id) })
                }
            }
            OutlinedButton(
                onClick = onAddLayer,
                enabled = drawingLayers.size < DrawingLayers.MAX,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text(if (drawingLayers.size < DrawingLayers.MAX) "Add a drawing layer (${drawingLayers.size} of ${DrawingLayers.MAX})" else "All ${DrawingLayers.MAX} layers in use") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBackup) { Text("Back up & restore") }
                TextButton(onClick = onAbout) { Text("About") }
            }
        }
    }
}

@Composable
private fun LegendSwatch(group: LayerGroup) {
    Canvas(Modifier.size(width = 44.dp, height = 18.dp)) {
        val y = size.height / 2
        val w = size.width
        when (group) {
            LayerGroup.Neighborhoods -> {
                drawRect(Color(0x142F8F9D), size = size)
                drawLine(Color(0xFF2F8F9D), Offset(0f, y), Offset(w, y), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx())))
            }
            LayerGroup.Traffic -> {
                drawLine(Color(0xFF8A8F98), Offset(0f, y + 5.dp.toPx()), Offset(w, y + 5.dp.toPx()), 2.dp.toPx())
                drawLine(Color(0xFF2B2F36), Offset(0f, y - 3.dp.toPx()), Offset(w, y - 3.dp.toPx()), 5.dp.toPx())
            }
            LayerGroup.Rail -> {
                // Freight (ticked), MARTA (double line) and a station dot.
                drawLine(Color(0xFF3B3833), Offset(0f, y - 4.dp.toPx()), Offset(w * 0.5f, y - 4.dp.toPx()), 1.5.dp.toPx())
                drawLine(Color(0xFF3B3833), Offset(0f, y - 4.dp.toPx()), Offset(w * 0.5f, y - 4.dp.toPx()), 8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 5.dp.toPx())))
                drawLine(Color(0xFF3B3F9E), Offset(0f, y + 5.dp.toPx()), Offset(w * 0.62f, y + 5.dp.toPx()), 4.dp.toPx())
                drawLine(Color.White, Offset(0f, y + 5.dp.toPx()), Offset(w * 0.62f, y + 5.dp.toPx()), 1.6.dp.toPx())
                drawCircle(Color.White, 5.dp.toPx(), Offset(w - 7.dp.toPx(), y))
                drawCircle(Color(0xFF3B3F9E), 5.dp.toPx(), Offset(w - 7.dp.toPx(), y), style = Stroke(2.dp.toPx()))
            }
            LayerGroup.Schools -> {
                val fills = listOf(Color(0x40D9A441), Color(0x40C9704E), Color(0x407FAE8A))
                fills.forEachIndexed { i, c -> drawRect(c, Offset(i * w / 3, 1.dp.toPx()), Size(w / 3, size.height - 2.dp.toPx())) }
                drawRect(Color(0xFF7A5310), Offset(0f, 1.dp.toPx()), Size(w, size.height - 2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            }
            LayerGroup.Markers -> drawPin(Offset(w / 2, y))
        }
    }
}

private fun DrawScope.drawPin(center: Offset) {
    val r = 6.dp.toPx()
    drawCircle(Color.White, r + 1.5.dp.toPx(), center.copy(y = center.y - 1.dp.toPx()))
    drawCircle(Color(MarkerLayer.PIN_COLOR), r, center.copy(y = center.y - 1.dp.toPx()))
    drawCircle(Color.White, r * 0.4f, center.copy(y = center.y - 1.dp.toPx()))
}

/** Where the map's data comes from (MAP-9): required attribution, kept out of the way. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About & data sources") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("House Hunter keeps everything on your phone. Nothing you draw or write is uploaded.", style = MaterialTheme.typography.bodyMedium)
                Text("Streets, railroads, MARTA stations and place names: © OpenStreetMap contributors (openstreetmap.org/copyright), used under the Open Database License. Basemap tiles from Protomaps.", style = MaterialTheme.typography.bodyMedium)
                Text("Neighborhood boundaries: City of Atlanta Department of City Planning; City of Sandy Springs and City of Brookhaven GIS open data. Names shown without a boundary line come from OpenStreetMap and are approximate.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Elementary school zones: DeKalb County School District and Fulton County Schools (their own published zone data), City Schools of Decatur (traced from the district's official 2025-26 boundary map), and Atlanta Public Schools zones from a third-party compilation (Aug 2026). " +
                        "Cobb County School District zones are traced from the district's 2023-24 zone map, the oldest data here. Marietta City Schools zones come from the City of Marietta's parcel records (which the city says users must verify). Clayton and Gwinnett are not covered. Zones change, so always confirm a school with the district before relying on it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Map rendering: MapLibre.", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Rename a drawing layer, choose it as the one you draw on, or delete it. */
@Composable
fun LayerEditDialog(
    layer: DrawingLayer,
    isActive: Boolean,
    canDelete: Boolean,
    strokeCount: Int,
    onSave: (String) -> Unit,
    onMakeActive: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(layer.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${layer.name}\"?") },
            text = { Text(if (strokeCount == 0) "This layer is empty." else "Its $strokeCount highlight${if (strokeCount == 1) "" else "s"} will be removed. This can't be undone.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Drawing layer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(DrawingLayers.MAX_NAME) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isActive) {
                    Text("New highlights go on this layer.", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedButton(onClick = { onSave(name); onMakeActive() }, modifier = Modifier.fillMaxWidth()) { Text("Draw on this layer") }
                }
                if (canDelete) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete this layer…", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text("You always keep at least one layer.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
