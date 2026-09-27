package dev.onairtv.app.data

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InterruptedIOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.StringReader
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Reads an XMLTV guide into an [EpgGuide], keeping only what the playlist can use.
 *
 * Real guides are 10-100 MB with well over 100k programmes, against a heap of roughly 192 MB, so
 * nothing is buffered: a streaming parse drops programmes for other channels, and programmes
 * outside the horizon, as it meets them. A 500-channel playlist typically lands at ~15k kept.
 *
 * SAX (`javax.xml.parsers`) rather than `android.util.Xml`: it resolves to the JDK in unit tests,
 * so this is plain-JUnit testable like [M3uParser], instead of needing Robolectric.
 *
 * A parse of a real guide runs for seconds, so it watches for its thread being interrupted and
 * gives up: cancelling the job that started it (switching playlists does) must actually stop the
 * work, not just discard the result. Call it inside `runInterruptible` to hook that up.
 */
object XmltvParser {

    /** How far ahead to keep programmes. A day is plenty for "now / next". */
    const val DEFAULT_HORIZON_MILLIS = 24L * 60 * 60 * 1000

    fun parse(
        input: InputStream,
        channels: List<Channel>,
        now: Long,
        horizonMillis: Long = DEFAULT_HORIZON_MILLIS,
        zone: TimeZone = TimeZone.getDefault(),
    ): EpgGuide {
        val wantedIds = channels.mapNotNull { it.tvgId?.let(::epgIdKey) }.toHashSet()
        val wantedNames = channels.map { epgNameKey(it.name) }.toHashSet()
        // Nothing to match against: don't read a byte of what may be a 100 MB file.
        if (wantedIds.isEmpty() && wantedNames.isEmpty()) return EpgGuide.EMPTY

        val handler = XmltvHandler(wantedIds, wantedNames, now, now + horizonMillis, zone)
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        val reader = factory.newSAXParser().xmlReader
        // Guides open with `<!DOCTYPE tv SYSTEM "xmltv.dtd">`, which the parser would otherwise go
        // and fetch. Resolving every external entity to nothing also closes off XXE/SSRF on a file
        // that came from a third party.
        reader.entityResolver = NoExternalEntities
        reader.contentHandler = handler
        reader.parse(InputSource(input))
        return handler.build()
    }

    private object NoExternalEntities : EntityResolver {
        override fun resolveEntity(publicId: String?, systemId: String?) =
            InputSource(StringReader(""))
    }
}

/** Unwraps a gzipped stream, leaving a plain one alone. */
internal fun maybeGunzip(input: InputStream): InputStream {
    // The magic bytes, not the file extension or Content-Encoding: OkHttp transparently inflates
    // `Content-Encoding: gzip`, but leaves a .xml.gz served as application/gzip compressed, so the
    // headers mislead in both directions.
    val pushback = PushbackInputStream(input, 2)
    val header = ByteArray(2)
    var read = 0
    while (read < 2) {
        val n = pushback.read(header, read, 2 - read)
        if (n < 0) break
        read += n
    }
    if (read > 0) pushback.unread(header, 0, read)
    val gzipped = read == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()
    return if (gzipped) GZIPInputStream(pushback) else pushback
}

/**
 * Collects programmes for the wanted channels only.
 *
 * `<channel>` elements map display names onto ids; the DTD requires all of them before the first
 * `<programme>`, and a file that breaks that rule only loses the name fallback, since every
 * `<programme>` carries its channel id anyway.
 */
private class XmltvHandler(
    private val wantedIds: Set<String>,
    private val wantedNames: Set<String>,
    private val now: Long,
    private val horizonEnd: Long,
    private val zone: TimeZone,
) : DefaultHandler() {

    /** Channel id key -> its programmes. A channel is "accepted" once it has an entry here. */
    private val byId = HashMap<String, MutableList<Programme>>()
    /** Display name key -> channel id, for the channels a playlist can only match by name. */
    private val nameToId = HashMap<String, String>()

    private var depth = 0
    /** Depth at which an uninteresting subtree started; while set, elements only count depth. */
    private var skipDepth = -1
    /** Counts <programme> elements, to check for cancellation without doing it per element. */
    private var seen = 0

    // Current <channel>
    private var channelId: String? = null
    private var channelNames = ArrayList<String>()

    // Current <programme>
    private var programmeKey: String? = null
    private var programmeStart = 0L
    private var programmeStop = 0L
    private var title: String? = null
    private var text: StringBuilder? = null

    override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
        depth++
        if (skipDepth >= 0) return

        when (qName) {
            "channel" -> {
                channelId = attrs.getValue("id")?.takeIf { it.isNotBlank() }
                channelNames = ArrayList()
                if (channelId == null) skipDepth = depth
            }

            "programme" -> {
                // Cheap, but often enough to abandon a 100k-programme file promptly.
                if (++seen % 512 == 0 && Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("Guide parse cancelled")
                }
                startProgramme(attrs)
            }

            "display-name" -> if (channelId != null) text = StringBuilder()

            // Only the first title is kept, and <desc> never is.
            "title" -> if (programmeKey != null && title == null) text = StringBuilder()

            else -> if (programmeKey != null || channelId != null) skipDepth = depth
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        // SAX splits text around entities and CDATA, so it has to be accumulated.
        text?.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        if (skipDepth >= 0) {
            if (depth == skipDepth) skipDepth = -1
            depth--
            return
        }
        when (qName) {
            "channel" -> endChannel()
            "programme" -> endProgramme()
            "display-name" -> {
                finishText()?.let { channelNames += it }
            }
            "title" -> {
                // Guard: a second <title> collects no text, and must not erase the first.
                finishText()?.let { title = it }
            }
        }
        depth--
    }

    override fun endDocument() {
        byId.values.forEach { it.sortBy(Programme::start) }
    }

    fun build(): EpgGuide {
        val ids = byId.filterValues { it.isNotEmpty() }.mapValues { (_, list) -> list.toList() }
        // Aliases the same list instances rather than copying them.
        val names = nameToId.mapNotNull { (name, id) -> ids[id]?.let { name to it } }.toMap()
        val validUntil = ids.values.asSequence().flatten().maxOfOrNull { it.stop } ?: 0L
        return EpgGuide(ids, names, validUntil)
    }

    private fun finishText(): String? {
        val value = text?.toString()?.trim()?.ifEmpty { null }
        text = null
        return value
    }

    private fun endChannel() {
        val id = channelId ?: return
        val key = epgIdKey(id)
        val nameKeys = channelNames.map(::epgNameKey).filter { it in wantedNames }
        if (key in wantedIds || nameKeys.isNotEmpty()) {
            byId.getOrPut(key) { ArrayList() }
            nameKeys.forEach { nameToId[it] = key }
        }
        channelId = null
        channelNames = ArrayList()
    }

    /** Reads the attributes before descending, so an unwanted programme costs no allocation. */
    private fun startProgramme(attrs: Attributes) {
        val id = attrs.getValue("channel")?.let(::epgIdKey)
        // Accepted either because the playlist asks for this id, or because a <channel> claimed it.
        val known = id != null && (id in wantedIds || byId.containsKey(id))
        val start = if (known) attrs.getValue("start")?.let { parseXmltvTime(it, zone) } else null
        val stop = if (start != null) attrs.getValue("stop")?.let { parseXmltvTime(it, zone) } else null
        if (start == null || stop == null || stop <= now || start >= horizonEnd) {
            skipDepth = depth
            return
        }
        programmeKey = id
        programmeStart = start
        programmeStop = stop
        title = null
    }

    private fun endProgramme() {
        val key = programmeKey ?: return
        title?.let { byId.getOrPut(key) { ArrayList() } += Programme(programmeStart, programmeStop, it) }
        programmeKey = null
        title = null
        text = null
    }
}
