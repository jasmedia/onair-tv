# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

OnAir TV: a minimal IPTV player for Google TV / Android TV, in the spirit of TiviMate. Loads an M3U
playlist (e.g. iptv-org lists), browses channels by group, and plays full-screen with remote-control
zapping. Stack: Kotlin, Jetpack Compose for TV (`androidx.tv:tv-material`), Media3 ExoPlayer
(HLS/DASH/TS), OkHttp, Coil.

## Commands

```bash
./gradlew assembleDebug           # build debug APK
./gradlew installDebug            # build + install on a connected/adb-connected device
./gradlew test                    # run all unit tests (app/src/test)
# --tests needs the concrete task, not the `test` lifecycle task:
./gradlew testDebugUnitTest --tests "dev.onairtv.app.data.M3uParserTest"          # one test class
./gradlew testDebugUnitTest --tests "dev.onairtv.app.data.M3uParserTest.parsesAllChannels"  # one method
```

There is no emulator/instrumented test suite — only JVM unit tests under `app/src/test`. The pure
pieces (`M3uParser`, `ChannelSearch`, `SavedPlaylists`, `Epg`, `XmltvParser`, and the
`visibleChannels` / `zapPosition` / `isHlsUrl` / EPG-formatting helpers pulled out of the screens)
are plain JUnit. `PlaylistRepositoryTest` and
`MainViewModelTest` run under Robolectric (needs the JDK 21 below) with OkHttp's `MockWebServer`
standing in for the playlist host. The ViewModel tests set `Dispatchers.Main` to an
`UnconfinedTestDispatcher` and wait for loads by joining `viewModelScope`'s child jobs, since
`download` runs on the real `Dispatchers.IO`. There is no linter configured (no ktlint/detekt).

To sideload onto a real Google TV: `adb connect <tv-ip>:5555` then `./gradlew installDebug` (see
README for the developer-options / pairing steps).

## Running in the emulator

**JDK:** Gradle 8.11.1 only runs on JDK ≤ 23. The Homebrew default (`openjdk`, 26) and Android Studio's
bundled JBR (25) are both too new and fail with a bare version string as the error (e.g. `25.0.3`).
Use Homebrew's `openjdk@21` (installed, not linked). `local.properties` points at the SDK.

```bash
export ANDROID_HOME=~/Library/Android/sdk
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# 1. Start the Google TV AVD (API 36) in the background, wait for full boot
nohup emulator -avd Television_4K > /tmp/onairtv-emulator.log 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 5; done

# 2. Build, install, launch (TV apps use the LEANBACK_LAUNCHER category)
./gradlew installDebug
adb shell monkey -p dev.onairtv.app -c android.intent.category.LEANBACK_LAUNCHER 1

# 3. Verify: screenshot + crash log
adb shell input keyevent KEYCODE_WAKEUP   # display sleeps → screencap is all black otherwise
adb exec-out screencap -p > /tmp/onairtv.png
adb logcat -d -b crash | tail
```

- A cold boot of `Television_4K` takes ~8 min, and the first Gradle build takes ~11 min (dependency
  download). Both are much faster afterwards. Keep the emulator running between builds and just re-run step 2.
- A black screenshot usually means the display is asleep, not a crash. Wake it first (see above).
- In the emulator window, the keyboard acts as the remote: arrows = D-pad, Enter = OK, Esc = Back.

**Shutting down gracefully.** Do this instead of killing the process. It lets the emulator save its
`default_boot` snapshot, so the next start is a quick boot rather than an 8-minute cold boot:

```bash
adb emu kill                  # graceful: saves quickboot snapshot, then exits
./gradlew --stop              # optional: stop the Gradle daemon to free memory
```

Avoid `kill -9` on the emulator process. It skips the snapshot save and can corrupt the AVD, which then
forces a cold boot (or a wipe via Device Manager → Wipe Data).

## Architecture

Single-module app (`:app`), no DI framework — one `PlaylistRepository` is instantiated directly by
`MainViewModel`. Data flows one way: `PlaylistRepository` → `MainViewModel` (`StateFlow<PlaylistState>`)
→ `MainActivity`'s `OnAirTvApp` composable, which switches screens based on state.

- **`PlaylistState`** (sealed interface in `MainViewModel.kt`): `NotConfigured` → `Loading` →
  `Ready(channels, groups)` | `Failed(message)`. `MainViewModel` shows the on-disk cached playlist
  immediately, then refreshes from the network in the background, swapping in the fresh list only if
  it differs — this is why the UI can go from `Ready` back to a *different* `Ready` without a `Loading`
  flash in between.
- **`M3uParser`** (`data/M3uParser.kt`) turns raw M3U text into `List<Channel>`. It's a pure function
  with no Android dependencies, and has the most detailed tests. It resolves per-channel
  `User-Agent`/`Referer` from three possible sources, in priority order: `#EXTVLCOPT` lines, `#EXTINF`
  attributes, then Kodi-style `url|User-Agent=...&Referer=...` suffixes on the stream URL itself.
- **`PlaylistRepository`** (`data/PlaylistRepository.kt`) owns both the disk cache (one file per
  playlist, `filesDir/playlists/<sha256(url)>.m3u`) and `SharedPreferences` (saved playlists, current
  playlist URL, last-watched channel URL, favorite channel URLs). It's the only place that talks to
  `OkHttpClient` or touches `Context`. On first run after upgrading it migrates the old single
  `playlist_url` + `filesDir/playlist.m3u` into the saved list.
- **Multiple playlists.** Saved playlists are an ordered `List<SavedPlaylist>` (name + URL, keyed by
  URL), stored as `name<TAB>url` lines by `SavedPlaylists` (a pure object, under test).
  `MainViewModel.open(url)` is the single load path (startup, add, switch, retry): it cancels any
  in-flight load, shows the cached copy, then refreshes. Favorites and last-watched are global, not
  per playlist (they're stream URLs). `PlaylistsScreen` switches/adds/removes; each row is a
  `ListItem` plus a `Remove` button reached with ▶.
- **Favorites and search.** Favorites are a `Set` of stream URLs (like last-watched, keyed by URL so
  they survive playlist refreshes), exposed as `MainViewModel.favorites` and toggled by holding OK
  (`ListItem.onLongClick` in the list, key `repeatCount == 1` in the player). `FAVORITES` is a virtual
  group next to `ALL_CHANNELS`. The search query is hoisted into `OnAirTvApp` (so it survives a trip
  into the player). A non-blank query overrides the selected group and searches the whole playlist via
  `ChannelSearch` (a pure function, under test).
- **Navigation is local `Composable` state, not a nav library.** `OnAirTvApp` in `MainActivity.kt` holds
  `showSetup`, `showPlaylists`, `selectedGroup`, `searchQuery`, and `playback: Playback?` (`Playback` = the current channel list +
  position) as plain `remember`/`rememberSaveable` state, and decides which screen to show with a
  `when`. Going from the channel list into the player passes the *whole visible channel list* plus a
  position, so zapping (▲/▼) moves through that same filtered/group list without re-querying state.
- **`PlayerScreen`** owns the `ExoPlayer` instance directly (`remember { ExoPlayer.Builder(...) }`),
  with lifecycle-driven pause/resume and a "retry once as forced-HLS" fallback in `onPlayerError` for
  extension-less URLs. Rapid zapping is debounced with a 300ms delay before actually swapping the
  media source, so holding ▲/▼ doesn't load every channel in between. `onChannelStarted` is how the
  last-watched channel gets persisted back through `MainViewModel.rememberChannel` into
  `PlaylistRepository`.
- **In-player channel list.** A short OK press in `PlayerScreen` opens `ChannelListOverlay` (the same
  `channels` list the player zaps through, reusing `ChannelRow` from `ChannelsScreen.kt`). OK opens it
  on key-*up* so holding OK (favorite toggle on the first key repeat) never opens it. While it's open
  the player's `onKeyEvent` returns `false` so the rows get the D-pad. Back is handled in the overlay's
  `onPreviewKeyEvent`, not a `BackHandler`: with a row focused, Compose consumes Back to move focus
  out of it, so the back dispatcher never fires.
- **EPG ("now / next").** A second, independent flow: `MainViewModel.guide: StateFlow<EpgGuide>`,
  loaded by its own `epgJob` at the end of `open`'s load job, never a field on `PlaylistState.Ready`
  — a guide that fails or is slow cannot then break or delay the playlist. The guide URL comes from
  `url-tvg` / `x-tvg-url` on the `#EXTM3U` header (`M3uParser.tvgUrl`, deliberately *not* part of
  `parse`, whose result is compared to decide whether to swap in a fresh playlist), overridden by
  `SavedPlaylist.epgUrl` when the user sets one. `XmltvParser` is a streaming SAX parse that is
  handed the playlist's channels and drops every other channel's programmes, and anything outside a
  24 h horizon, at parse time — real guides are 10–100 MB on a ~192 MB heap. It uses
  `javax.xml.parsers` rather than `android.util.Xml` so it stays plain-JUnit testable (the
  `org.xmlpull` classes in the stubbed `android.jar` are Robolectric-only), and installs an
  `EntityResolver` returning nothing, because guides open with `<!DOCTYPE tv SYSTEM "xmltv.dtd">`
  and Xerces would otherwise fetch it. `PlaylistRepository` caches the raw response bytes (still
  gzipped if that's how they arrived) at `filesDir/epg/<sha256(epgUrl)>.xml`, refreshing when the
  file's mtime is over 6 h old and returning null rather than throwing at every step.
- **The EPG clock lives in the composition, not the ViewModel** (`rememberEpgClock` in `ui/EpgUi.kt`,
  called once in `OnAirTvApp`). Two reasons: an always-on coroutine in `viewModelScope` would hang
  every ViewModel test, which waits for the scope to go idle; and passing the tick down as an unread
  `State<Long>` inside `EpgSource` means only the rows that call `rememberNowNext` resubscribe, so a
  tick recomposes ~10 visible rows instead of the whole tree. `ChannelBanner` looks its own
  programme up internally for the same reason — reading the clock in `PlayerScreen` would recompose
  the `AndroidView` every 30 s.
- Focus handling for D-pad navigation is manual in a few places (`FocusRequester` +
  `LaunchedEffect { requestFocus() }` wrapped in `runCatching`), notably to restore focus to the
  last-watched channel row when returning to `ChannelsScreen` from the player.

## Notes for changes here

- `SetupScreens.kt` bundles hardcoded iptv-org preset playlists (`PRESETS`). The README says these
  must be removed before any Play Store release — the app should ship as a pure player with no
  bundled channel links. Don't add more presets; ask before removing them.
- `usesCleartextTraffic="true"` in the manifest is intentional (many IPTV streams are plain `http://`).
