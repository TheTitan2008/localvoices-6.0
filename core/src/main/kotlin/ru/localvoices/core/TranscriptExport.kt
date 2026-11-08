package ru.localvoices.core

import kotlin.math.roundToLong

/** Competition interchange format. All times refer to the original dialogue. */
object TranscriptExport {
    fun json(rows: List<Utterance>): String = rows.sortedBy { it.span.start }.mapIndexed { index, row ->
        val id = row.speakerId ?: "unresolved_${index + 1}"
        "{\"speaker_id\":${quote(id)},\"start_ms\":${(row.span.start * 1000).roundToLong()},\"end_ms\":${(row.span.end * 1000).roundToLong()},\"text\":${quote(row.text.trim())}}"
    }.joinToString(",", "{\"transcript\":[", "]}")
    private fun quote(value: String) = buildString {
        append('"')
        value.forEach { c -> append(when (c) {
            '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            else -> if (c < ' ') "\\u%04x".format(c.code) else c.toString()
        }) }
        append('"')
    }
}

object PunctuationGuard {
    private val words = Regex("[+-]?\\d+(?:[.,]\\d+)*|[\\p{L}\\p{M}\\p{N}]+(?:['’\\-][\\p{L}\\p{M}\\p{N}]+)*")
    fun accepts(original: String, proposed: String): Boolean {
        if (proposed.isBlank() || proposed.length > original.length * 2 + 64) return false
        return words.findAll(original).map { it.value.lowercase(java.util.Locale.ROOT) }.toList() ==
            words.findAll(proposed).map { it.value.lowercase(java.util.Locale.ROOT) }.toList()
    }
}
