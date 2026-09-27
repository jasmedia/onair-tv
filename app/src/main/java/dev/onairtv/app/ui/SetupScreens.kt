package dev.onairtv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.onairtv.app.data.SavedPlaylist

/**
 * Quick-pick playlists from the iptv-org project, so nobody has to type a URL with a remote.
 * Remove these before publishing to the Play Store: ship the app as a pure player.
 */
private val PRESETS = listOf(
    "India" to "https://iptv-org.github.io/iptv/countries/in.m3u",
    "Malayalam" to "https://iptv-org.github.io/iptv/languages/mal.m3u",
    "News" to "https://iptv-org.github.io/iptv/categories/news.m3u",
    "All (large)" to "https://iptv-org.github.io/iptv/index.m3u",
)

@Composable
fun SetupScreen(
    onSubmit: (url: String, name: String?, epgUrl: String?) -> Unit,
    onCancel: (() -> Unit)?,
    initial: SavedPlaylist? = null,
) {
    var url by rememberSaveable(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var epgUrl by rememberSaveable(initial) { mutableStateOf(initial?.epgUrl.orEmpty()) }
    val firstFocus = remember { FocusRequester() }

    if (onCancel != null) BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    val submit = { if (url.isNotBlank()) onSubmit(url, initial?.name, epgUrl) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (initial == null) "Add a playlist" else "Edit ${initial.name}",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "Enter an M3U playlist URL, or pick one of the iptv-org playlists below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            UrlField(
                value = url,
                onValueChange = { url = it },
                placeholder = "https://example.com/playlist.m3u",
                onDone = submit,
            )

            Text(
                "TV guide (XMLTV) — leave blank to use the playlist's own",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            UrlField(
                value = epgUrl,
                onValueChange = { epgUrl = it },
                placeholder = "https://example.com/guide.xml.gz",
                onDone = submit,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = submit, modifier = Modifier.focusRequester(firstFocus)) {
                    Text(if (initial == null) "Load playlist" else "Save")
                }
                if (onCancel != null) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }

            // Presets never set a guide URL; the iptv-org lists advertise their own.
            if (initial == null) {
                Spacer(Modifier.height(8.dp))
                Text("Quick picks", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PRESETS.forEach { (label, presetUrl) ->
                        OutlinedButton(onClick = { url = presetUrl; onSubmit(presetUrl, label, null) }) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single-line URL field. ▲/▼ move focus out of it rather than the cursor within it: a text field
 * otherwise swallows the D-pad, and on a remote there is no other way to reach the next field.
 */
@Composable
private fun UrlField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDone: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionUp -> FocusDirection.Up
                    Key.DirectionDown -> FocusDirection.Down
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                true
            }
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .border(
                width = 2.dp,
                color = if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            inner()
        },
    )
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Loading playlist…", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onChangePlaylist: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text("Couldn't load the playlist", style = MaterialTheme.typography.headlineSmall)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Retry") }
                OutlinedButton(onClick = onChangePlaylist) { Text("Playlists") }
            }
        }
    }
}
