package ru.localvoices.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.*
import java.nio.ByteOrder
import kotlin.math.*

object Wav {
    fun write(file: File, samples: FloatArray) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0); header(f, samples.size * 2)
            val buffer = ByteArray(8192); var i = 0
            while (i < samples.size) {
                val count = min(4096, samples.size - i)
                for (j in 0 until count) { val v = (samples[i + j].coerceIn(-1f, 1f) * 32767).toInt(); buffer[j * 2] = v.toByte(); buffer[j * 2 + 1] = (v shr 8).toByte() }
                f.write(buffer, 0, count * 2); i += count
            }
        }
    }
    fun header(f: RandomAccessFile, bytes: Int) {
        fun int(v: Int) { f.writeInt(Integer.reverseBytes(v)) }
        fun short(v: Int) { f.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt()) }
        f.seek(0); f.writeBytes("RIFF"); int(bytes + 36); f.writeBytes("WAVEfmt "); int(16); short(1); short(1)
        int(16000); int(32000); short(2); short(16); f.writeBytes("data"); int(bytes)
    }
    fun read(file: File): FloatArray = RandomAccessFile(file, "r").use { f ->
        require(f.length() >= 44); f.seek(44)
        FloatArray(((f.length() - 44) / 2).toInt()) { java.lang.Short.reverseBytes(f.readShort()).toFloat() / 32768 }
    }
}
/** Random access to canonical PCM avoids loading an hour-long conversation into RAM. */
class PcmFile(val file: File) {
    val count: Int = ((file.length() - 44) / 2).toInt().also { require(it >= 0) }
    val duration get() = count / 16000.0
    fun slice(span: ru.localvoices.core.Span): FloatArray = read((span.start * 16000).toInt(), (span.duration * 16000).toInt())
    fun read(start: Int, size: Int): FloatArray = RandomAccessFile(file, "r").use { f ->
        require(start in 0..count)
        val n = size.coerceIn(0, count - start); val bytes = ByteArray(n * 2)
        f.seek(44 + start * 2L); f.readFully(bytes)
        FloatArray(n) { ((bytes[it * 2].toInt() and 255) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() / 32768f }
    }
}
data class RecorderState(val recording: Boolean = false, val paused: Boolean = false, val seconds: Double = 0.0, val level: Float = 0f, val error: String? = null)
class Microphone(private val scope: CoroutineScope, private val sourceMode: () -> String = { "voice_recognition" }) {
    val state = MutableStateFlow(RecorderState())
    private var recorder: AudioRecord? = null
    private var job: Job? = null
    private var file: File? = null
    @SuppressLint("MissingPermission")
    fun start(target: File) {
        if (job?.isCompleted == true) job = null
        check(job == null); file = target
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Микрофон не поддерживает PCM 16 кГц" }
        val source = when (sourceMode()) {
            "mic" -> MediaRecorder.AudioSource.MIC
            "unprocessed" -> if (Build.VERSION.SDK_INT >= 24) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val mic = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(8192, minimum * 2))
        if (mic.state != AudioRecord.STATE_INITIALIZED) { mic.release(); error("Микрофон недоступен") }
        recorder = mic; state.value = RecorderState(recording = true)
        try { mic.startRecording() } catch (t: Throwable) { mic.release(); recorder = null; throw t }
        job = scope.launch(Dispatchers.IO) {
            var count = 0
            try {
                RandomAccessFile(target, "rw").use { f ->
                    f.setLength(0); Wav.header(f, 0)
                    val buffer = ShortArray(2048)
                    try {
                        while (isActive && state.value.recording) {
                            val n = mic.read(buffer, 0, buffer.size)
                            check(n >= 0 || !state.value.recording) { "Ошибка микрофона: $n" }
                            if (n <= 0 || state.value.paused) continue
                            var sum = 0.0
                            val bytes = ByteArray(n * 2)
                            for (i in 0 until n) { val v = buffer[i].toInt(); bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte(); sum += (v / 32768.0).pow(2) }
                            f.write(bytes); count += n
                            state.value = state.value.copy(seconds = count / 16000.0, level = (sqrt(sum / n) * 6).toFloat().coerceIn(0f, 1f))
                            if (count >= 16000 * 60 * 60) break
                        }
                    } finally { Wav.header(f, count * 2) }
                }
            } catch (t: Throwable) { state.value = state.value.copy(error = t.message) }
            finally { runCatching { mic.stop() }; mic.release(); recorder = null; state.value = state.value.copy(recording = false) }
        }
    }
    fun pause() { state.value = state.value.copy(paused = !state.value.paused) }
    suspend fun stop(): File? {
        state.value = state.value.copy(recording = false)
        runCatching { recorder?.stop() }; job?.join(); job = null
        return file
    }
}

/** MediaCodec decoding supports SAF audio containers without trusting their extension.
 * Streaming linear resampling carries phase across codec buffers. Output is bounded.
 */
class AudioDecoder(private val context: Context) {
    suspend fun decode(uri: Uri, output: File) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("В файле нет поддерживаемой аудиодорожки")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val c = MediaCodec.createDecoderByType(mime); codec = c
            c.configure(format, null, null, 0); c.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false; var outputEnded = false
            val info = MediaCodec.BufferInfo()
            var sourceIndex = 0L; var nextOutput = 0.0; var previous = 0f; var written = 0
            RandomAccessFile(output, "rw").use { f ->
                f.setLength(0); Wav.header(f, 0)
                try {
                    while (!outputEnded) {
                        ensureActive()
                        if (!inputEnded) {
                            val index = c.dequeueInputBuffer(10000)
                            if (index >= 0) {
                                val buffer = c.getInputBuffer(index)!!
                                val size = extractor.readSampleData(buffer, 0)
                                if (size < 0) { c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                                else { c.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                            }
                        }
                        when (val index = c.dequeueOutputBuffer(info, 10000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val out = c.outputFormat; rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                                require(encoding == AudioFormat.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_16BIT) { "Неподдерживаемый PCM" }
                            }
                            else -> if (index >= 0) {
                                val b = c.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN); b.position(info.offset); b.limit(info.offset + info.size)
                                val width = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                while (b.remaining() >= channels * width) {
                                    var mono = 0f
                                    repeat(channels) { mono += if (width == 4) b.float else b.short / 32768f }; mono /= channels
                                    require(mono.isFinite()) { "Повреждённый PCM" }
                                    while (nextOutput <= sourceIndex) {
                                        val alpha = (nextOutput - (sourceIndex - 1)).coerceIn(0.0, 1.0)
                                        val value = (previous + (mono - previous) * alpha).coerceIn(-1.0, 1.0)
                                        f.writeShort(java.lang.Short.reverseBytes((value * 32767).toInt().toShort()).toInt())
                                        nextOutput += rate / 16000.0; written++
                                        require(written <= 16000 * 60 * 60) { "Максимальная длительность: 60 минут" }
                                    }
                                    previous = mono; sourceIndex++
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                c.releaseOutputBuffer(index, false)
                            }
                        }
                    }
                } finally { Wav.header(f, written * 2) }
            }
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
}
