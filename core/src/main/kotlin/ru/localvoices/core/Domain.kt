package ru.localvoices.core

import kotlin.math.*

data class Span(val start: Double, val end: Double) {
    init { require(start >= 0 && end >= start && start.isFinite() && end.isFinite()) }
    val duration get() = end - start
    fun overlap(other: Span) = max(0.0, min(end, other.end) - max(start, other.start))
}

/** Plans speaker-encoder windows only inside speech detected by VAD. */
object SpeechWindowPlanner {
    fun intersect(primary: List<Span>, speech: List<Span>, minOverlap: Double = .08): List<Span> =
        primary.flatMap { a -> speech.mapNotNull { b ->
            val start = max(a.start, b.start)
            val end = min(a.end, b.end)
            if (end - start >= minOverlap) Span(start, end) else null
        } }.sortedBy { it.start }

    fun plan(spans: List<Span>, window: Double, step: Double, minWindow: Double = 1.0, mergeGap: Double = .35): List<Span> {
        require(window > 0 && step > 0 && minWindow > 0)
        val merged = mutableListOf<Span>()
        for (span in spans.filter { it.duration >= minWindow }.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && span.start - last.end <= mergeGap)
                merged[merged.lastIndex] = Span(last.start, max(last.end, span.end))
            else merged += span
        }
        val result = mutableListOf<Span>()
        for (segment in merged) {
            var start = segment.start
            while (start < segment.end) {
                val end = min(start + window, segment.end)
                if (end - start >= minWindow) result += Span(start, end)
                if (end >= segment.end) break
                start += step
            }
        }
        return result
    }
}
/**
 * A decoder emission or a reconstructed word.  The optional provenance fields
 * keep the old two-argument constructor source-compatible while allowing the
 * GigaAM path to preserve token IDs and decoder metadata in the cache.
 */
data class AsrUnit(
    val span: Span,
    val text: String,
    val tokenId: Int? = null,
    val probability: Double? = null,
    val segmentIndex: Int? = null,
    val wordIndex: Int? = null,
    val tokenIds: List<Int> = emptyList(),
)

/** Reconstruct SentencePiece emissions before speaker attribution. */
object SentencePieceReconstructor {
    fun gigaAm(tokens: List<AsrUnit>): List<AsrUnit> {
        if (tokens.isEmpty()) return emptyList()
        val result = mutableListOf<AsrUnit>()
        val text = StringBuilder()
        val ids = mutableListOf<Int>()
        var start = 0.0
        var end = 0.0
        var hasBoundary = false
        var pendingBoundary = false
        var emittedWord = false
        var probabilitySum = 0.0
        var probabilityCount = 0

        fun commit() {
            if (text.isEmpty()) {
                ids.clear()
                probabilitySum = 0.0
                probabilityCount = 0
                return
            }
            val prefix = if (emittedWord && hasBoundary) " " else ""
            result += AsrUnit(
                span = Span(start, end),
                text = prefix + text.toString(),
                probability = probabilitySum.takeIf { probabilityCount > 0 }?.div(probabilityCount),
                tokenIds = ids.toList(),
            )
            emittedWord = true
            text.setLength(0)
            ids.clear()
            probabilitySum = 0.0
            probabilityCount = 0
        }

        tokens.forEach { token ->
            val piece = token.text
            val boundary = piece.startsWith("▁")
            if (boundary) {
                commit()
                hasBoundary = true
                start = token.span.start
                end = token.span.end
                if (piece.length > 1) {
                    text.append(piece.removePrefix("▁"))
                    pendingBoundary = false
                } else pendingBoundary = true
            } else {
                if (text.isEmpty() && !pendingBoundary) start = token.span.start
                text.append(piece)
                end = token.span.end
                pendingBoundary = false
            }
            token.tokenId?.let(ids::add)
            token.probability?.let { probabilitySum += it; probabilityCount++ }
        }
        commit()
        return result
    }
}
data class VoiceWindow(val span: Span, val embedding: FloatArray, val quality: Double = 1.0)
data class VoiceProfile(val id: String, val name: String, val encoder: String, val references: List<FloatArray>) {
    val centroid get() = Vectors.centroid(references)
    val dispersion get() = references.map { 1 - Vectors.cosine(it, centroid) }.average()
}
data class Identity(val id: String, val name: String, val similarity: Double, val margin: Double)
data class Turn(val span: Span, val speakerId: String?, val name: String, val confidence: Double)
data class Utterance(val span: Span, val speakerId: String?, val name: String, val text: String, val confidence: Double)

/** Lightweight online tracker used while recording.  Final processing still
 * performs global clustering; this tracker only gives the live preview stable
 * provisional speaker labels without waiting for the recording to stop. */
class OnlineSpeakerTracker(private val threshold: Double = .72) {
    private data class Prototype(val id: String, var centroid: FloatArray, var count: Int)
    private val prototypes = mutableListOf<Prototype>()
    fun observe(embedding: FloatArray): String {
        val best = prototypes.maxByOrNull { Vectors.cosine(embedding, it.centroid) }
        if (best != null && Vectors.cosine(embedding, best.centroid) >= threshold) {
            best.centroid = Vectors.centroid(listOf(best.centroid, embedding)); best.count++
            return best.id
        }
        val id = "live_${prototypes.size + 1}"
        prototypes += Prototype(id, Vectors.normalized(embedding), 1)
        return id
    }
    fun size() = prototypes.size
}
data class DiarizationConfig(
    val knownThreshold: Double = .78, val knownMargin: Double = .08,
    val clusterMinSize: Int = 3, val minSamples: Int = 2,
    val noiseThreshold: Double = .86, val smallSetThreshold: Double = .92,
    val changeThreshold: Double = .72,
    val clusteringBackend: String = "hdbscan",
) {
    init {
        require(listOf(knownThreshold, noiseThreshold, smallSetThreshold, changeThreshold).all { it in 0.0..1.0 })
        require(knownMargin in 0.0..1.0 && clusterMinSize >= 2 && minSamples >= 1)
        require(clusteringBackend == "hdbscan" || clusteringBackend == "ahc")
    }
}

object Vectors {
    fun centroidOrNull(vectors: List<FloatArray>): FloatArray? =
        runCatching { centroid(vectors) }.getOrNull()
    fun normalized(v: FloatArray): FloatArray {
        require(v.isNotEmpty() && v.all { it.isFinite() }) { "Некорректный эмбеддинг" }
        val norm = sqrt(v.sumOf { it.toDouble() * it })
        require(norm > 1e-10) { "Нулевой эмбеддинг" }
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }
    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        val x = normalized(a); val y = normalized(b)
        return x.indices.sumOf { x[it].toDouble() * y[it] }.coerceIn(-1.0, 1.0)
    }
    fun centroid(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty() && vectors.all { it.size == vectors.first().size })
        val sum = FloatArray(vectors.first().size)
        vectors.map(::normalized).forEach { v -> v.indices.forEach { sum[it] += v[it] } }
        return normalized(sum)
    }
    // Medoid filtering prevents a single outlier from moving the enrollment centroid.
    fun robustReferences(vectors: List<FloatArray>, threshold: Double = .55): List<FloatArray> {
        require(vectors.isNotEmpty()) { "Нужен образец голоса" }
        val normalized = vectors.map(::normalized)
        val medoid = normalized.maxBy { a -> normalized.sumOf { cosine(a, it) } }
        return normalized.filter { cosine(it, medoid) >= threshold }.also {
            require(it.isNotEmpty()) { "Не удалось получить устойчивый голосовой профиль. Запишите образец заново." }
        }
    }
}

class KnownSpeakerMatcher(private val config: DiarizationConfig) {
    fun match(embedding: FloatArray, profiles: List<VoiceProfile>): Identity? {
        val ranked = profiles.map { p ->
            // Require agreement with the aggregate and multiple enrollment references.
            val scores = p.references.map { Vectors.cosine(embedding, it) }.sortedDescending()
            val score = min(Vectors.cosine(embedding, p.centroid), scores.take(3).average())
            p to score
        }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        val margin = best.second - (ranked.getOrNull(1)?.second ?: -1.0)
        return if (best.second >= config.knownThreshold && margin >= config.knownMargin)
            Identity(best.first.id, best.first.name, best.second, margin) else null
    }
}
