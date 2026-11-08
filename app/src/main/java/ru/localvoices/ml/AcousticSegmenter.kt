package ru.localvoices.ml

import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.localvoices.audio.PcmFile
import ru.localvoices.core.Span
import kotlin.math.*

data class AcousticSegments(val speech: List<Span>, val overlaps: List<Span>)

/** Learned acoustic boundaries; chunk-local cluster numbers never become person identities. */
class AcousticSegmenter(private val store: ModelStore, private val threads: Int) {
    // Sherpa 1.12.15 JNI looks up invoke(IIJ)Integer. Kotlin's indy lambda only
    // exposes the erased signature, so retain an explicit typed callback class.
    private class Progress(private val job: kotlinx.coroutines.Job?) : (Int,Int,Long)->Int {
        override fun invoke(processed: Int, total: Int, arg: Long): Int = if(job?.isActive==false)1 else 0
    }
    suspend fun run(source: PcmFile): AcousticSegments {
        val context = currentCoroutineContext()
        val model = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(store.requireFile("segmentation.onnx").absolutePath),
                numThreads = threads),
            embedding = SpeakerEmbeddingExtractorConfig(store.requireFile("speaker.onnx").absolutePath, threads),
            minDurationOn = .15f, minDurationOff = .15f))
        val speech = mutableListOf<Span>(); val overlaps = mutableListOf<Span>()
        try {
            var offset = 0
            while (offset < source.count) {
                context.ensureActive()
                val samples = source.read(offset, min(30 * 16000, source.count - offset))
                val rows = model.processWithCallback(samples, Progress(context[kotlinx.coroutines.Job]))
                context.ensureActive()
                val base = offset / 16000.0
                for (row in rows) {
                    val start = base + row.start.toDouble().coerceIn(0.0, samples.size / 16000.0)
                    val end = base + row.end.toDouble().coerceIn(row.start.toDouble().coerceAtLeast(0.0), samples.size / 16000.0)
                    if (end > start) speech += Span(start, end)
                }
                for (i in rows.indices) for (j in 0 until i) if (rows[i].speaker != rows[j].speaker) {
                    val start = max(rows[i].start, rows[j].start).toDouble().coerceAtLeast(0.0)
                    val end = min(rows[i].end, rows[j].end).toDouble().coerceAtMost(samples.size / 16000.0)
                    if (end - start >= .1) overlaps += Span(base + start, base + end)
                }
                offset += samples.size
            }
        } finally { model.release() }
        return AcousticSegments(speech.distinct().sortedBy { it.start }, overlaps.distinct().sortedBy { it.start })
    }
}
