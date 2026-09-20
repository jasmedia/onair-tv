package dev.onairtv.app.data

/** One playable channel from an M3U playlist. */
data class Channel(
    val index: Int,
    val name: String,
    val url: String,
    val logo: String?,
    val tvgId: String?,
    val groups: List<String>,
    val userAgent: String?,
    val referrer: String?,
)

/**
 * Parses extended M3U playlists (the format used by iptv-org and most IPTV providers).
 *
 * Supports:
 *  - `#EXTINF:-1 tvg-id="..." tvg-logo="..." group-title="A;B",Channel Name`
 *  - `#EXTGRP:Group` (fallback group)
 *  - `#EXTVLCOPT:http-user-agent=...` / `#EXTVLCOPT:http-referrer=...`
 *  - Kodi-style headers appended to the URL: `http://host/stream.m3u8|User-Agent=x&Referer=y`
 */
object M3uParser {

    const val UNGROUPED = "Undefined"

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    // Providers write this both quoted and bare, so it can't share attrRegex (which needs quotes).
    private val tvgUrlRegex = Regex(
        """(?:url-tvg|x-tvg-url)\s*=\s*(?:"([^"]*)"|'([^']*)'|(\S+))""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): List<Channel> {
        val channels = ArrayList<Channel>()
        var info: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        var extGroup: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = line
                    userAgent = null
                    referrer = null
                    extGroup = null
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> userAgent = value.ifEmpty { null }
                        "http-referrer", "http-referer" -> referrer = value.ifEmpty { null }
                    }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    extGroup = line.substringAfter(':').trim().ifEmpty { null }

                line.startsWith("#") -> Unit // #EXTM3U and other directives

                else -> {
                    val extinf = info
                    if (extinf != null) {
                        channels += buildChannel(
                            index = channels.size,
                            extinf = extinf,
                            rawUrl = line,
                            vlcUserAgent = userAgent,
                            vlcReferrer = referrer,
                            extGroup = extGroup,
                        )
                    }
                    info = null
                    userAgent = null
                    referrer = null
                    extGroup = null
                }
            }
        }
        return channels
    }

    /**
     * The XMLTV guide URL a playlist advertises on its `#EXTM3U` header line, as `url-tvg` or
     * `x-tvg-url`. Some providers list several guides, comma-separated; only the first is used.
     *
     * Kept out of [parse] on purpose: the channel list is compared to decide whether to swap in a
     * freshly downloaded playlist, and a header-only change must not count as a different list.
     */
    fun tvgUrl(text: String): String? {
        for (raw in text.lineSequence().take(20)) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.startsWith("#EXTINF", ignoreCase = true)) break
            if (!line.startsWith("#EXTM3U", ignoreCase = true)) continue
            val match = tvgUrlRegex.find(line) ?: return null
            val value = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
            return value.substringBefore(',').trim().ifEmpty { null }
        }
        return null
    }

    private fun buildChannel(
        index: Int,
        extinf: String,
        rawUrl: String,
        vlcUserAgent: String?,
        vlcReferrer: String?,
        extGroup: String?,
    ): Channel {
        val attrs = attrRegex.findAll(extinf)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

        // Kodi-style "url|Header=value&Header2=value2"
        val url = rawUrl.substringBefore('|').trim()
        val pipeHeaders = rawUrl.substringAfter('|', "")
            .split('&')
            .mapNotNull { pair ->
                val k = pair.substringBefore('=', "").trim()
                val v = pair.substringAfter('=', "").trim()
                if (k.isEmpty() || v.isEmpty()) null else k.lowercase() to v
            }
            .toMap()

        val name = titleOf(extinf)
            ?: attrs["tvg-name"]?.ifEmpty { null }
            ?: "Channel ${index + 1}"

        val groups = (attrs["group-title"]?.ifEmpty { null } ?: extGroup)
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { null }
            ?: listOf(UNGROUPED)

        return Channel(
            index = index,
            name = name,
            url = url,
            logo = attrs["tvg-logo"]?.ifEmpty { null },
            tvgId = attrs["tvg-id"]?.ifEmpty { null },
            groups = groups,
            userAgent = vlcUserAgent ?: attrs["user-agent"]?.ifEmpty { null } ?: pipeHeaders["user-agent"],
            referrer = vlcReferrer ?: attrs["referrer"]?.ifEmpty { null } ?: pipeHeaders["referer"]
                ?: pipeHeaders["referrer"],
        )
    }

    /** Channel title = text after the first comma that is not inside a quoted attribute. */
    private fun titleOf(extinf: String): String? {
        var inQuotes = false
        for (i in extinf.indices) {
            when (extinf[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return extinf.substring(i + 1).trim().ifEmpty { null }
            }
        }
        return null
    }
}
