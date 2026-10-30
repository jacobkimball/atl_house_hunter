package com.househunter.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

private fun countText(markers: Int, strokes: Int) =
    "${if (markers == 1) "1 marker" else "$markers markers"} and ${if (strokes == 1) "1 highlight" else "$strokes highlights"}"

/** Export and Import live here (requirement Q11). */
@Composable
fun BackupDialog(
    markerCount: Int,
    strokeCount: Int,
    lastBackupAt: Long,
    ready: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasWork = markerCount + strokeCount > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Back up & restore") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("On this phone now: ${countText(markerCount, strokeCount)}.", style = MaterialTheme.typography.bodyMedium)
                if (lastBackupAt > 0) {
                    Text("Last backup: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastBackupAt))}", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        if (hasWork) "You haven't backed up yet." else "No backup yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasWork) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    "Export saves your markers, notes and highlights as one small file. Keep it in Google Drive, " +
                        "email it to yourself, or carry it to a new phone and Import it there.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onExport, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Export to a file…") }
                OutlinedButton(onClick = onImport, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Import from a file…") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** After a backup file has been read and checked: add it to what's here, or replace what's here. */
@Composable
fun ImportChoiceDialog(backup: Backup, onAdd: () -> Unit, onReplace: () -> Unit, onCancel: () -> Unit) {
    val saved = if (backup.exportedAt > 0) " Saved ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.exportedAt))}." else ""
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Import this backup?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("It has ${countText(backup.markers.size, backup.strokes.size)}.$saved", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Adding keeps everything on this phone and brings in what's missing. If a note exists in both, " +
                        "the most recently edited copy wins.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add to my map") }
                OutlinedButton(onClick = onReplace, modifier = Modifier.fillMaxWidth()) { Text("Replace my map…") }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        },
        confirmButton = {},
    )
}

@Composable
fun ConfirmReplaceDialog(currentMarkers: Int, currentStrokes: Int, onReplace: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Replace everything?") },
        text = {
            Text(
                "The ${countText(currentMarkers, currentStrokes)} on this phone will be removed and replaced by the backup's. " +
                    "You can undo the highlights right afterward, but not the markers. Export first if you're unsure.",
            )
        },
        confirmButton = { TextButton(onClick = onReplace) { Text("Replace") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
