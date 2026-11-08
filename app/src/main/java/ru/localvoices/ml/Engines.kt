package ru.localvoices.ml

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.*
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor as SherpaExtractor
import kotlinx.coroutines.*
import ru.localvoices.core.*
import ru.localvoices.data.Preferences
import ru.localvoices.audio.PcmFile
import ru.localvoices.BuildConfig
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

class ModelStore(context: Context) {
    private val assets = context.assets
    val directory = File(context.filesDir, "models").apply { mkdirs() }
    val preparation = kotlinx.coroutines.flow.MutableStateFlow("Подготовка встроенных моделей")
    val bundled = listOf("ggml-base.bin", "silero_vad.onnx", "speaker.onnx", "segmentation.onnx", "punctuation.gguf") +
        if (BuildConfig.ASR_BACKEND == "gigaam") listOf("gigaam-v3-e2e-ctc-Q4_K_M.gguf") else emptyList()
    val slots = listOf("ggml-tiny.bin", "ggml-base.bin", "ggml-small.bin", "silero_vad.onnx", "speaker.onnx", "segmentation.onnx", "punctuation.gguf", "gigaam-v3-e2e-ctc-Q4_K_M.gguf")
    private val supportedHashes = mapOf(
        "segmentation.onnx" to "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d",
        "punctuation.gguf" to "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        "ggml-tiny.bin" to "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
        "ggml-base.bin" to "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        "ggml-small.bin" to "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
        "silero_vad.onnx" to "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        "speaker.onnx" to "5ef208a9da1453335308a6b6f4e6dfbd7e183a38b604de0a57664f45d257fe94",
        "gigaam-v3-e2e-ctc-Q4_K_M.gguf" to "35581e11e048eef785657cff07e5fced794bba6d9c75143257f6452c8aeea655",
    )
    fun file(slot: String): File { require(slot in slots); return File(directory, slot) }
    suspend fun installBundled() = withContext(Dispatchers.IO) {
        for (slot in bundled) {
            ensureActive()
            preparation.value = "Подготовка $slot"
            val target = file(slot)
            if (target.length() > 1024 && fingerprint(slot) == supportedHashes[slot]) continue
            val temp = File(directory, "$slot.bundled.part")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                assets.open("models/$slot").use { input -> temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                require(hash == supportedHashes[slot]) { "Повреждена встроенная модель $slot" }
                if (target.exists()) require(target.delete()) { "Не удалось заменить $slot" }
                require(temp.renameTo(target)) { "Не удалось подготовить $slot: освободите место на устройстве" }
            } finally { temp.delete() }
        }
        preparation.value = "Модели готовы"
    }
    fun requireFile(slot: String) = file(slot).also { require(it.length() > 1024) { "Установите модель $slot в настройках" } }
    fun fingerprint(slot: String): String = MessageDigest.getInstance("SHA-256").let { digest ->
        requireFile(slot).inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    suspend fun import(context: Context, slot: String, uri: Uri) = withContext(Dispatchers.IO) {
        val temp = File(directory, "$slot.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { out ->
                val b = ByteArray(65536); var total = 0L
                while (true) { ensureActive(); val n = input.read(b); if (n < 0) break; total += n; require(total <= 2_000_000_000L) { "Модель превышает 2 ГБ" }; digest.update(b, 0, n); out.write(b, 0, n) }
            } }
            require(temp.length() > 1024) { "Файл модели слишком мал" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(hash == supportedHashes[slot]) { "Неверная или повреждённая модель $slot. Используйте файлы из scripts/download-models.ps1 и ссылки README." }
            if (slot.startsWith("ggml")) temp.inputStream().use {
                val magic = ByteArray(4)
                require(it.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x6c, 0x6d, 0x67, 0x67))) { "Нужна модель Whisper GGML, не GGUF" }
            }
            val target = file(slot)
            require(temp.renameTo(target)) { "Не удалось заменить модель" }
        } finally { temp.delete() }
    }
    suspend fun importPack(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val imported = mutableSetOf<String>()
        context.contentResolver.openInputStream(uri)!!.use { source -> java.util.zip.ZipInputStream(source).use { zip ->
            while (true) {
                ensureActive(); val entry=zip.nextEntry ?: break
                if(entry.isDirectory || entry.name !in slots) { zip.closeEntry(); continue }
                val temp=File(context.cacheDir,"model-pack-${entry.name}")
                try {
                    temp.outputStream().use { output -> val b=ByteArray(65536); var size=0L; while(true) { ensureActive(); val n=zip.read(b); if(n<0)break;size+=n;require(size<=2_000_000_000L);output.write(b,0,n) } }
                    import(context,entry.name,Uri.fromFile(temp)); imported+=entry.name
                } finally {temp.delete()}
                zip.closeEntry()
            }
        } }
        require(imported.containsAll(listOf("silero_vad.onnx","speaker.onnx","segmentation.onnx","punctuation.gguf")) && imported.any { it.startsWith("ggml-") }) { "Нужен полный пакет: Whisper, Silero, speaker, segmentation и LLM. Корректные модели уже установлены." }
    }
}
object WhisperNative {
    init { System.loadLibrary("voices") }
    external fun open(path: String): Long
    external fun close(ptr: Long)
    external fun cancel(ptr: Long)
    external fun transcribe(ptr: Long, input: FloatArray, language: String, threads: Int): Array<ByteArray>
    external fun embedding(ptr: Long, input: FloatArray, threads: Int): FloatArray
}
class WhisperEngine(model: File, private val threads: Int) : AutoCloseable {
    private var ptr = WhisperNative.open(model.absolutePath)
    fun transcribe(samples: FloatArray, language: String): List<AsrUnit> = WhisperNative.transcribe(ptr, samples, language, threads).map { bytes ->
        val parts = bytes.toString(Charsets.UTF_8).split('\t', limit = 3)
        AsrUnit(Span(parts[0].toDouble() / 100, parts[1].toDouble() / 100), parts[2])
    }
    fun embedding(samples: FloatArray) = Vectors.normalized(WhisperNative.embedding(ptr, samples, threads))
    fun cancel() {if(ptr!=0L)WhisperNative.cancel(ptr)}
    override fun close() {if(ptr!=0L){WhisperNative.close(ptr);ptr=0}}
}
interface SpeakerEmbeddingExtractor : AutoCloseable {
    val id: String
    fun extract(samples: FloatArray): FloatArray
}
class WhisperSpeakerExtractor(private val engine: WhisperEngine, override val id: String) : SpeakerEmbeddingExtractor {
    override fun extract(samples: FloatArray) = engine.embedding(samples)
    override fun close() {} // Engine ownership stays with caller.
}
class DedicatedSpeakerExtractor(model: File, threads: Int, override val id: String) : SpeakerEmbeddingExtractor {
    private val extractor = SherpaExtractor(config = SpeakerEmbeddingExtractorConfig(model.absolutePath, threads))
    override fun extract(samples: FloatArray): FloatArray {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, 16000); stream.inputFinished()
            require(extractor.isReady(stream)) { "Недостаточно речи для speaker encoder" }
            return Vectors.normalized(extractor.compute(stream))
        } finally { stream.release() }
    }
    override fun close() = extractor.release()
}
class Engines(private val store: ModelStore, private val prefs: Preferences) {
    private val encoder = prefs.encoder
    private val model = prefs.whisperModel
    private val threads = prefs.threads
    val usesWhisper get() = encoder == "whisper"
    val whisperModel get() = model
    fun whisper() = WhisperEngine(store.requireFile(model), threads)
    fun embeddingId(): String {
        val slot = if (encoder == "whisper") model else "speaker.onnx"
        return "$encoder:pool-v1:${store.fingerprint(slot)}"
    }
    fun extractor(whisper: WhisperEngine?, id: String): SpeakerEmbeddingExtractor =
        if (encoder == "whisper") WhisperSpeakerExtractor(requireNotNull(whisper), id)
        else DedicatedSpeakerExtractor(store.requireFile("speaker.onnx"), threads, id)
    suspend fun vad(samples: FloatArray): List<Span> = detectSpeech(samples.size) { start, count -> samples.copyOfRange(start, min(start + count, samples.size)) }
    suspend fun vad(source: PcmFile): List<Span> = detectSpeech(source.count, source::read)
    private suspend fun detectSpeech(sampleCount: Int, read: (Int, Int) -> FloatArray): List<Span> {
        val vad = Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
            model = store.requireFile("silero_vad.onnx").absolutePath,
            minSilenceDuration = .35f, minSpeechDuration = .12f, maxSpeechDuration = 120f)))
        val spans = mutableListOf<Span>()
        fun drain() { while (!vad.empty()) { val s = vad.front(); val start = min(s.start, sampleCount); spans += Span(start / 16000.0, min(sampleCount, s.start + s.samples.size) / 16000.0); vad.pop() } }
        try {
            var start = 0
            while (start < sampleCount) {
                currentCoroutineContext().ensureActive()
                // Read 32-second chunks rather than reopening the file for every VAD frame.
                val chunk = read(start, min(512 * 1000, sampleCount - start))
                var offset = 0
                while (offset < chunk.size) {
                    currentCoroutineContext().ensureActive()
                    val frame = FloatArray(512); chunk.copyInto(frame, 0, offset, min(offset + 512, chunk.size)); vad.acceptWaveform(frame); drain(); offset += 512
                }
                start += chunk.size
            }
            vad.flush(); drain()
        } finally { vad.release() }
        return spans.filter { it.duration >= .25 }
    }
}
data class SampleQuality(val duration: Double, val rms: Double, val clipping: Double) {
    val acceptable get() = duration >= 3 && rms >= .006 && clipping <= .02
    val message get() = when { duration < 3 -> "Нужно минимум 3 секунды речи"; rms < .006 -> "Слишком тихо"; clipping > .02 -> "Перегрузка микрофона"; else -> "Хороший образец" }
}
fun quality(samples: FloatArray) = SampleQuality(samples.size / 16000.0, sqrt(samples.sumOf { it.toDouble() * it } / max(1, samples.size)), samples.count { abs(it) >= .99f }.toDouble() / max(1, samples.size))
fun FloatArray.sliceAudio(span: Span): FloatArray = copyOfRange((span.start * 16000).toInt().coerceIn(0, size), (span.end * 16000).toInt().coerceIn(0, size))
