package com.github.tvbox.osc.ui.music

import android.net.Uri
import android.text.TextUtils
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.nio.charset.Charset
import kotlin.math.roundToLong

data class LyricWord(val timeMs: Long, val text: String)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val durationMs: Long = 0L,
)

private const val LYRIC_LINE_MAX_SPAN_MS = 6_000L

private enum class LrcFormat { LRC, QRC, YRC, SRT, ASS }

internal fun lyricCharTimes(line: LyricLine, nextStartMs: Long): LongArray {
    val text = line.text
    if (text.isEmpty()) return LongArray(0)
    val joined = line.words.joinToString("") { it.text }
    if (line.words.isEmpty() || joined != text) {
        return spreadTimes(line.timeMs, lyricLineEnd(line, nextStartMs), text.length)
    }
    val times = LongArray(text.length)
    var cursor = 0
    for (index in line.words.indices) {
        val word = line.words[index]
        val wordEnd = line.words.getOrNull(index + 1)?.timeMs ?: lyricLineEnd(line, nextStartMs)
        val span = (wordEnd - word.timeMs).coerceAtLeast(1L)
        for (offset in word.text.indices) {
            times[cursor + offset] = word.timeMs + span * offset / word.text.length
        }
        cursor += word.text.length
    }
    return times
}

internal fun lyricRevealCount(times: LongArray, positionMs: Long): Int {
    var low = 0
    var high = times.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (times[mid] <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result + 1
}

private fun spreadTimes(startMs: Long, endMs: Long, count: Int): LongArray {
    val span = (endMs - startMs).coerceAtLeast(count.toLong())
    return LongArray(count) { startMs + span * it / count }
}

private fun lyricLineEnd(line: LyricLine, nextStartMs: Long): Long {
    val declared = if (line.durationMs > 0L) line.timeMs + line.durationMs else 0L
    val limit = line.timeMs + LYRIC_LINE_MAX_SPAN_MS
    val end = when {
        declared > line.timeMs && nextStartMs > line.timeMs -> minOf(declared, nextStartMs)
        declared > line.timeMs -> declared
        nextStartMs > line.timeMs -> nextStartMs
        else -> limit
    }
    return minOf(end, limit).coerceAtLeast(line.timeMs + 1L)
}

private fun tagMs(tag: MatchResult): Long? {
    val minute = tag.groupValues[1].toLongOrNull() ?: return null
    val second = tag.groupValues[2].toLongOrNull() ?: return null
    return minute * 60_000 + second * 1000 + fractionMs(tag.groupValues[3])
}

private fun fractionMs(value: String): Long = when (value.length) {
    0 -> 0L
    1 -> value.toLongOrNull().orZero() * 100
    2 -> value.toLongOrNull().orZero() * 10
    else -> value.take(3).toLongOrNull().orZero()
}

private fun Long?.orZero(): Long = this ?: 0L

private fun normalizeWords(words: List<LyricWord>): List<LyricWord> {
    val result = ArrayList<LyricWord>(words.size)
    for ((index, word) in words.withIndex()) {
        val text = when {
            index == 0 && index == words.lastIndex -> word.text.trim()
            index == 0 -> word.text.trimStart()
            index == words.lastIndex -> word.text.trimEnd()
            else -> word.text
        }
        if (text.isNotEmpty()) result.add(LyricWord(word.timeMs, text))
    }
    return result
}

object MusicLrc {

    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val wordTimeTag = Regex("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")
    private val pairTag = Regex("\\[(\\d{1,9}),(\\d{1,9})]")
    private val qrcWordTag = Regex("\\((\\d{1,9}),(\\d{1,9})\\)")
    private val yrcWordTag = Regex("\\((\\d{1,9}),(\\d{1,9}),(\\d{1,9})\\)")
    private val assKaraokeTag = Regex("\\{[^}]*\\\\[kK](\\d+)[^}]*}")
    private val srtTime = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->")
    private val breakTag = Regex("(?i)<br\\s*/?>")
    private val htmlTag = Regex("<[^>]{1,20}>")
    private val braceTag = Regex("\\{[^}]{0,40}\\}")
    private val assTag = Regex("\\{[^}]*\\}")
    private val assBreak = Regex("\\\\[Nn]")
    private val assDialogue = Regex("(?im)^[ \\t]*dialogue[ \\t]*:")

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36"

    suspend fun load(source: String?): List<LyricLine> {
        val path = source?.trim().orEmpty()
        if (path.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val raw = read(path)
            if (raw.isNullOrBlank()) {
                LOG.i("echo-music lyric raw empty: kind=" + kindOf(path) + " srcLen=" + path.length)
                emptyList()
            } else {
                val lines = parse(raw)
                LOG.i(
                    "echo-music lyric raw len=" + raw.length +
                        " format=" + formatOf(raw) + " lines=" + lines.size +
                        " wordLines=" + lines.count { it.words.isNotEmpty() }
                )
                lines
            }
        }
    }

    private fun kindOf(path: String): String = when {
        path.startsWith("data:") -> "data"
        path.startsWith("http://") || path.startsWith("https://") -> "http"
        else -> "file"
    }

    private fun formatOf(raw: String): LrcFormat = when {
        raw.contains("-->") -> LrcFormat.SRT
        isAss(raw) -> LrcFormat.ASS
        isYrc(raw) -> LrcFormat.YRC
        isQrc(raw) -> LrcFormat.QRC
        else -> LrcFormat.LRC
    }

    private fun isAss(raw: String): Boolean =
        raw.contains("[Script Info]", ignoreCase = true) || assDialogue.containsMatchIn(raw)

    private fun isQrc(raw: String): Boolean = pairTag.containsMatchIn(raw) && qrcWordTag.containsMatchIn(raw)

    private fun isYrc(raw: String): Boolean = pairTag.containsMatchIn(raw) && yrcWordTag.containsMatchIn(raw)

    fun parse(raw: String): List<LyricLine> = when (formatOf(raw)) {
        LrcFormat.SRT -> parseSrt(raw)
        LrcFormat.ASS -> parseAss(raw)
        LrcFormat.YRC -> parseTimed(raw, yrcWordTag, true)
        LrcFormat.QRC -> parseTimed(raw, qrcWordTag, false)
        LrcFormat.LRC -> parseLrc(raw)
    }

    private fun parseTimed(raw: String, wordTag: Regex, tagLeads: Boolean): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        for (line in raw.lineSequence()) {
            val head = pairTag.find(line) ?: continue
            val start = head.groupValues[1].toLongOrNull() ?: continue
            val duration = head.groupValues[2].toLongOrNull() ?: 0L
            val words = normalizeWords(timedWords(line.substring(head.range.last + 1), wordTag, start, tagLeads))
            val text = words.joinToString("") { it.text }
            if (text.isEmpty()) continue
            lines.add(LyricLine(start, text, words, duration))
        }
        lines.sortBy { it.timeMs }
        return lines
    }

    private fun timedWords(
        body: String,
        wordTag: Regex,
        lineStartMs: Long,
        tagLeads: Boolean,
    ): List<LyricWord> {
        val matches = wordTag.findAll(body).toList()
        if (matches.isEmpty()) {
            val text = body.trim()
            return if (text.isEmpty()) emptyList() else listOf(LyricWord(lineStartMs, text))
        }
        val first = matches.first().groupValues[1].toLongOrNull() ?: 0L
        val relative = first < lineStartMs
        val words = ArrayList<LyricWord>(matches.size + 1)
        if (tagLeads) {
            val head = body.substring(0, matches.first().range.first).trim()
            if (head.isNotEmpty()) words.add(LyricWord(lineStartMs, head))
        }
        var previousEnd = 0
        for (index in matches.indices) {
            val match = matches[index]
            val offset = match.groupValues[1].toLongOrNull() ?: continue
            val startMs = if (relative) lineStartMs + offset else offset
            val begin = if (tagLeads) match.range.last + 1 else previousEnd
            val end = if (tagLeads) matches.getOrNull(index + 1)?.range?.first ?: body.length else match.range.first
            val text = body.substring(begin, end)
            if (text.isNotEmpty()) words.add(LyricWord(startMs, text))
            previousEnd = match.range.last + 1
        }
        return words
    }

    private fun parseAss(raw: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue
            val fields = trimmed.substringAfter(':').split(",", limit = 10)
            if (fields.size < 10) continue
            val start = assTimeMs(fields[1]) ?: continue
            val words = normalizeWords(assWords(fields[9], start))
            val text = if (words.isEmpty()) assPlain(fields[9]).trim() else words.joinToString("") { it.text }
            if (text.isEmpty()) continue
            val end = assTimeMs(fields[2])
            val duration = if (end != null && end > start) end - start else 0L
            lines.add(LyricLine(start, text, words, duration))
        }
        lines.sortBy { it.timeMs }
        return lines
    }

    private fun assWords(payload: String, startMs: Long): List<LyricWord> {
        val matches = assKaraokeTag.findAll(payload).toList()
        if (matches.isEmpty()) return emptyList()
        val words = ArrayList<LyricWord>(matches.size + 1)
        val head = assPlain(payload.substring(0, matches.first().range.first))
        if (head.isNotEmpty()) words.add(LyricWord(startMs, head))
        var cursor = startMs
        for (index in matches.indices) {
            val match = matches[index]
            val tail = matches.getOrNull(index + 1)?.range?.first ?: payload.length
            val text = assPlain(payload.substring(match.range.last + 1, tail))
            if (text.isNotEmpty()) words.add(LyricWord(cursor, text))
            cursor += match.groupValues[1].toLongOrNull().orZero() * 10L
        }
        return words
    }

    private fun assPlain(payload: String): String = payload
        .replace(assTag, "")
        .replace(assBreak, "\n")
        .replace("\\h", " ")

    private fun assTimeMs(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size < 3) return null
        val hour = parts[0].toLongOrNull() ?: return null
        val minute = parts[1].toLongOrNull() ?: return null
        val second = parts[2].toDoubleOrNull() ?: return null
        return hour * 3_600_000 + minute * 60_000 + (second * 1000).roundToLong()
    }

    private fun parseLrc(raw: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        for (line in raw.lineSequence()) {
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) continue
            val stamped = tags.mapNotNull { tagMs(it) }
            if (stamped.isEmpty()) continue
            val body = line.substring(tags.last().range.last + 1)
            val words = if (stamped.size == 1) lrcWords(body, stamped.first()) else emptyList()
            val text = if (words.isEmpty()) {
                wordTimeTag.replace(body, "").trim()
            } else {
                words.joinToString("") { it.text }
            }
            if (text.isEmpty()) continue
            for (time in stamped) {
                lines.add(LyricLine(time, text, words))
            }
        }
        lines.sortBy { it.timeMs }
        return lines
    }

    private fun lrcWords(body: String, lineMs: Long): List<LyricWord> {
        val matches = wordTimeTag.findAll(body).toList()
        if (matches.isEmpty()) return emptyList()
        val words = ArrayList<LyricWord>(matches.size + 1)
        var cursor = 0
        var cursorMs = lineMs
        for (match in matches) {
            val text = body.substring(cursor, match.range.first)
            if (text.isNotEmpty()) words.add(LyricWord(cursorMs, text))
            cursor = match.range.last + 1
            cursorMs = tagMs(match) ?: cursorMs
        }
        val tail = body.substring(cursor)
        if (tail.isNotEmpty()) words.add(LyricWord(cursorMs, tail))
        return normalizeWords(words)
    }

    private fun parseSrt(raw: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        for (block in raw.split(Regex("\\r?\\n\\s*\\r?\\n"))) {
            val rows = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val timeRow = rows.indexOfFirst { srtTime.containsMatchIn(it) }
            if (timeRow < 0) continue
            val match = srtTime.find(rows[timeRow]) ?: continue
            val text = rows.drop(timeRow + 1).joinToString("\n")
                .let { breakTag.replace(it, "\n") }
                .let { htmlTag.replace(braceTag.replace(it, ""), "") }
                .trim()
            if (text.isEmpty()) continue
            val hour = match.groupValues[1].toLong()
            val minute = match.groupValues[2].toLong()
            val second = match.groupValues[3].toLong()
            val ms = match.groupValues[4].padEnd(3, '0').take(3).toLong()
            lines.add(LyricLine(hour * 3_600_000 + minute * 60_000 + second * 1000 + ms, text))
        }
        lines.sortBy { it.timeMs }
        return lines
    }

    private suspend fun read(source: String): String? = try {
        when {
            source.startsWith("data:") -> decodeDataUri(source)
            source.startsWith("http://") || source.startsWith("https://") -> fetch(source.substringBefore('#'))
            else -> {
                val file = File(source)
                if (file.exists()) decode(file.readBytes()) else null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (th: Throwable) {
        LOG.i("echo-music lyric load failed: " + th.message)
        null
    }

    private fun decodeDataUri(source: String): String? {
        val comma = source.indexOf(',')
        if (comma < 0) return null
        val fragment = source.indexOf('#', comma + 1)
        val meta = source.substring(5, comma).lowercase()
        val payload = source.substring(comma + 1, if (fragment < 0) source.length else fragment)
        val bytes = if (meta.contains(";base64")) {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }
        return decode(bytes)
    }

    private suspend fun fetch(url: String): String? {
        val raw = Http.getRaw(url) {
            headers("User-Agent", UA)
        }
        return decode(raw.body)
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size)
        detector.dataEnd()
        val name = detector.detectedCharset
        detector.reset()
        if (TextUtils.isEmpty(name)) return String(bytes, Charsets.UTF_8)
        return runCatching { String(bytes, Charset.forName(name)) }.getOrElse { String(bytes, Charsets.UTF_8) }
    }
}
