package ru.localvoices.ml

import ru.localvoices.core.AsrUnit
import ru.localvoices.core.Span
import java.io.File

object GigaAmNative {
    init { System.loadLibrary("gigaam") }
    external fun open(path: String, threads: Int): Long
    external fun close(ptr: Long)
    external fun transcribe(ptr: Long, input: FloatArray, language: String): Array<ByteArray>
}

/** GigaAM-v3 E2E CTC: punctuation is produced by the model, token timestamps
 * are returned by transcribe.cpp at the encoder frame resolution. */
class GigaAmEngine(model: File, threads: Int) : AutoCloseable {
    private var ptr = GigaAmNative.open(model.absolutePath, threads)

    fun transcribe(samples: FloatArray, language: String): List<AsrUnit> {
        check(ptr != 0L)
        val duration = samples.size / 16000.0
        val raw = GigaAmNative.transcribe(ptr, samples, language)
        var previousEnd = 0.0
        return raw.mapIndexedNotNull { index, row ->
            val parts = row.toString(Charsets.UTF_8).split('\t', limit = 7)
            // New bridge format: id, t0_ms, t1_ms, segment, word, p, raw_piece.
            // Keep a fallback for caches/native libraries produced by older APKs.
            if (parts.size >= 7) {
                val token = parts[6]
                if (token.isEmpty()) return@mapIndexedNotNull null
                val rawStart = parts[1].toDoubleOrNull()?.div(1000.0) ?: 0.0
                val rawEnd = parts[2].toDoubleOrNull()?.div(1000.0) ?: 0.0
                val estimated = rawEnd <= rawStart || rawStart < previousEnd
                val start = (if (estimated) maxOf(previousEnd, duration * index / raw.size.coerceAtLeast(1)) else rawStart.coerceIn(0.0, duration)).coerceAtMost((duration - .001).coerceAtLeast(0.0))
                val end = if (estimated) maxOf(start + .005, duration * (index + 1) / raw.size.coerceAtLeast(1)) else rawEnd.coerceIn(start + .001, duration)
                previousEnd = end.coerceAtMost(duration)
                AsrUnit(
                    span = Span(start.coerceAtMost(duration), end.coerceAtMost(duration)),
                    text = token,
                    tokenId = parts[0].toIntOrNull(),
                    // The current transcribe.cpp GigaAM decoder leaves p at 0.0;
                    // do not expose that sentinel as a real confidence score.
                    probability = parts[5].toDoubleOrNull()?.takeIf { it > 0.0 },
                    segmentIndex = parts[3].toIntOrNull(),
                    wordIndex = parts[4].toIntOrNull(),
                    tokenIds = listOfNotNull(parts[0].toIntOrNull()),
                )
            } else {
                if (parts.size < 4 || parts[2].isEmpty()) return@mapIndexedNotNull null
                AsrUnit(Span(parts[0].toDouble() / 1000.0, parts[1].toDouble() / 1000.0), parts[2])
            }
        }
    }

    override fun close() {
        if (ptr != 0L) { GigaAmNative.close(ptr); ptr = 0L }
    }
}
