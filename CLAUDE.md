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
./gradlew test --tests "dev.onairtv.app.data.M3uParserTest"          # run one test class
./gradlew test --tests "dev.onairtv.app.data.M3uParserTest.parsesAllChannels"  # run one test method
```

There is no emulator/instrumented test suite — only JVM unit tests under `app/src/test`, currently
covering `M3uParser`. There is no linter configured (no ktlint/detekt).

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
  with no Android dependencies, which is why it's the one thing under test. It resolves per-channel
  `User-Agent`/`Referer` from three possible sources, in priority order: `#EXTVLCOPT` lines, `#EXTINF`
  attributes, then Kodi-style `url|User-Agent=...&Referer=...` suffixes on the stream URL itself.
- **`PlaylistRepository`** (`data/PlaylistRepository.kt`) owns both the disk cache
  (`filesDir/playlist.m3u`) and `SharedPreferences` (playlist URL, last-watched channel URL). It's the
  only place that talks to `OkHttpClient` or touches `Context`.
- **Navigation is local `Composable` state, not a nav library.** `OnAirTvApp` in `MainActivity.kt` holds
  `showSetup`, `selectedGroup`, and `playback: Playback?` (`Playback` = the current channel list +
  position) as plain `remember`/`rememberSaveable` state, and decides which screen to show with a
  `when`. Going from the channel list into the player passes the *whole visible channel list* plus a
  position, so zapping (▲/▼) moves through that same filtered/group list without re-querying state.
- **`PlayerScreen`** owns the `ExoPlayer` instance directly (`remember { ExoPlayer.Builder(...) }`),
  with lifecycle-driven pause/resume and a "retry once as forced-HLS" fallback in `onPlayerError` for
  extension-less URLs. Rapid zapping is debounced with a 300ms delay before actually swapping the
  media source, so holding ▲/▼ doesn't load every channel in between. `onChannelStarted` is how the
  last-watched channel gets persisted back through `MainViewModel.rememberChannel` into
  `PlaylistRepository`.
- Focus handling for D-pad navigation is manual in a few places (`FocusRequester` +
  `LaunchedEffect { requestFocus() }` wrapped in `runCatching`), notably to restore focus to the
  last-watched channel row when returning to `ChannelsScreen` from the player.

## Notes for changes here

- `SetupScreens.kt` bundles hardcoded iptv-org preset playlists (`PRESETS`). The README says these
  must be removed before any Play Store release — the app should ship as a pure player with no
  bundled channel links. Don't add more presets; ask before removing them.
- `usesCleartextTraffic="true"` in the manifest is intentional (many IPTV streams are plain `http://`).
