package dev.onairtv.app.data

import java.net.URI

/** A playlist the user has added. Identified by its URL; the name is only for display. */
data class SavedPlaylist(val name: String, val url: String)

/** Storage format and list operations for saved playlists. Pure, so it can be unit-tested. */
object SavedPlaylists {

    /** One `name<TAB>url` line per playlist, keeping the order. */
    fun encode(playlists: List<SavedPlaylist>): String =
        playlists.joinToString("\n") { "${it.name.clean()}\t${it.url.clean()}" }

    fun decode(text: String?): List<SavedPlaylist> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val url = line.substring(tab + 1).trim()
            if (url.isEmpty()) null else SavedPlaylist(line.substring(0, tab).trim(), url)
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
