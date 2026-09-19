package dev.opentv.app.ui

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text

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
    initialUrl: String,
    onSubmit: (String) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var fieldFocused by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    if (onCancel != null) BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    val submit = { if (url.isNotBlank()) onSubmit(url) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add a playlist", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Enter an M3U playlist URL, or pick one of the iptv-org playlists below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BasicTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .border(
                        width = 2.dp,
                        color = if (fieldFocused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    if (url.isEmpty()) {
                        Text(
                            "https://example.com/playlist.m3u",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = submit, modifier = Modifier.focusRequester(firstFocus)) {
                    Text("Load playlist")
                }
                if (onCancel != null) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Quick picks", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PRESETS.forEach { (label, presetUrl) ->
                    OutlinedButton(onClick = { url = presetUrl; onSubmit(presetUrl) }) {
                        Text(label)
                    }
                }
            }
        }
    }
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
                OutlinedButton(onClick = onChangePlaylist) { Text("Change playlist") }
            }
        }
    }
}
