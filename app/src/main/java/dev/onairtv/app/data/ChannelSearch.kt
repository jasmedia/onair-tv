package dev.onairtv.app.data

import java.text.Normalizer

/**
 * Channel-name search. Case- and accent-insensitive, and every word of the query must appear
 * somewhere in the name, in any order: "news bbc" finds "BBC World News".
 */
object ChannelSearch {

    private val combiningMarks = Regex("\\p{Mn}+")
    private val whitespace = Regex("\\s+")

    fun filter(channels: List<Channel>, query: String): List<Channel> {
        val terms = normalize(query).split(whitespace).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return channels
        return channels.filter { channel ->
            val name = normalize(channel.name)
            terms.all { it in name }
        }
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()
}
