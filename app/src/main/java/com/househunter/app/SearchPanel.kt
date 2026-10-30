package com.househunter.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFF1F2328)

/** One line in the results: a place from the index, or one of my own notes. */
class SearchHit(val title: String, val subtitle: String, val entry: SearchEntry?, val noteId: String?)

/** Places first, then any of my notes that mention the words typed. */
fun buildSearchHits(query: String, index: SearchIndex?, markers: List<NoteMarker>): List<SearchHit> {
    if (query.isBlank()) return emptyList()
    val places = index?.search(query, 9).orEmpty().map { SearchHit(it.name, it.subtitle, it, null) }
    val notes = SearchIndex.searchNotes(markers, query).map { m ->
        SearchHit(notePreview(m.note, 44).ifEmpty { "(empty note)" }, "My note", null, m.id)
    }
    return places + notes
}

@Composable
fun SearchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), color = Color(0xF2FFFFFF), contentColor = Ink, shadowElevation = 6.dp) {
        Text("Search", modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge)
    }
}

/** The search box with its results underneath. The keyboard comes up as soon as it opens. */
@Composable
fun SearchPanel(
    query: String,
    onQuery: (String) -> Unit,
    hits: List<SearchHit>,
    indexReady: Boolean,
    onPick: (SearchHit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color.White, contentColor = Ink, shadowElevation = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    placeholder = { Text("Neighborhood, street, station, or note") },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { hits.firstOrNull()?.let(onPick) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        }
        val message = when {
            query.isBlank() -> "Find a neighborhood, street, MARTA station or town, or search your own notes. (No house numbers: the map has street names only.)"
            hits.isEmpty() && !indexReady -> "Loading…"
            hits.isEmpty() -> "No matches"
            else -> null
        }
        if (message != null) {
            Surface(Modifier.padding(top = 8.dp), shape = RoundedCornerShape(16.dp), color = Color.White, contentColor = Ink.copy(alpha = 0.75f), shadowElevation = 6.dp) {
                Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Surface(Modifier.padding(top = 8.dp), shape = RoundedCornerShape(16.dp), color = Color.White, contentColor = Ink, shadowElevation = 8.dp) {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(hits) { hit ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(hit) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(hit.title, style = MaterialTheme.typography.bodyLarge)
                            Text(hit.subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.65f))
                        }
                        HorizontalDivider(color = Ink.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
}
