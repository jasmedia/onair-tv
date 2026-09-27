package dev.onairtv.app.data

import java.net.URI

/**
 * A playlist the user has added. Identified by its URL; the name is only for display.
 *
 * [epgUrl] overrides the XMLTV guide the playlist advertises in its own `#EXTM3U` header; null
 * means "use whatever the playlist says", which is what iptv-org lists want.
 */
data class SavedPlaylist(val name: String, val url: String, val epgUrl: String? = null)

/** Storage format and list operations for saved playlists. Pure, so it can be unit-tested. */
object SavedPlaylists {

    /**
     * One `name<TAB>url` line per playlist, keeping the order, with a third `<TAB>epgUrl` field
     * when one is set. The two-field form is a strict prefix of the three-field one, so playlists
     * stored by older versions decode unchanged and need no migration.
     */
    fun encode(playlists: List<SavedPlaylist>): String =
        playlists.joinToString("\n") { playlist ->
            val epg = playlist.epgUrl?.clean()?.ifEmpty { null }
            "${playlist.name.clean()}\t${playlist.url.clean()}" + (epg?.let { "\t$it" } ?: "")
        }

    fun decode(text: String?): List<SavedPlaylist> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val url = parts[1].trim()
            if (url.isEmpty()) null else SavedPlaylist(
                name = parts[0].trim(),
                url = url,
                epgUrl = parts.getOrNull(2)?.trim()?.ifEmpty { null },
            )
        }.distinctBy { it.url }.toList()

    /** Replaces the playlist with the same URL (keeping its position), or appends it. */
    fun upsert(playlists: List<SavedPlaylist>, playlist: SavedPlaylist): List<SavedPlaylist> =
        if (playlists.any { it.url == playlist.url }) {
            playlists.map { if (it.url == playlist.url) playlist else it }
        } else {
            playlists + playlist
        }

    /** A readable name for a URL-only playlist, e.g. `in (iptv-org.github.io)`. */
    fun defaultName(url: String): String {
        val uri = runCatching { URI(url.substringBefore('|').trim()) }.getOrNull() ?: return url
        val host = uri.host?.removePrefix("www.")
        val file = uri.path.orEmpty().substringAfterLast('/')
            .removeSuffix(".m3u8").removeSuffix(".m3u")
        return when {
            file.isNotBlank() && host != null -> "$file ($host)"
            file.isNotBlank() -> file
            host != null -> host
            else -> url
        }
    }

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
