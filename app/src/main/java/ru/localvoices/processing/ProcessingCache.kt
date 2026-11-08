package ru.localvoices.processing

import android.util.AtomicFile
import org.json.JSONObject
import ru.localvoices.core.*
import ru.localvoices.data.RawCodec
import java.io.*

/** Each completed chunk/window is a separate atomic file: no quadratic JSON rewrites. */
class ProcessingCache(root: File, id: String, fingerprint: String, liveRoot: File? = null, liveFingerprint: String? = null, reuseLiveAsr: Boolean = false) {
    val directory=File(root,id).apply { mkdirs() }
    private val liveDirectory=liveRoot?.let { File(it,id).apply { mkdirs() } }
    private val liveAllowed=liveDirectory != null && liveFingerprint != null &&
        File(liveDirectory, "fingerprint").takeIf { it.exists() }?.readText() == liveFingerprint
    private val reuseLiveAsr = reuseLiveAsr
    init {
        val manifest=File(directory,"fingerprint")
        if(!manifest.exists() || manifest.readText()!=fingerprint) {
            directory.listFiles()?.forEach { require(it.isFile); it.delete() }
            manifest.writeText(fingerprint)
        }
    }
    fun asr(index: Int): List<AsrUnit>? = File(directory,"asr_$index.json").takeIf { it.exists() }?.let { runCatching { RawCodec.parseAsr(it.readText()) }.getOrNull() }
        ?: if (liveAllowed && reuseLiveAsr) File(liveDirectory!!,"asr_$index.json").takeIf { it.exists() }?.let { runCatching { RawCodec.parseAsr(it.readText()) }.getOrNull() } else null
    fun saveAsr(index: Int, rows: List<AsrUnit>) = write(File(directory,"asr_$index.json"),RawCodec.asr(rows).toByteArray())
    fun liveAsr(): List<AsrUnit> {
        if (!liveAllowed) return emptyList()
        return liveDirectory!!.listFiles { file -> file.name.startsWith("asr_") && file.name.endsWith(".json") }
            ?.sortedBy { it.name.removePrefix("asr_").removeSuffix(".json").toIntOrNull() ?: Int.MAX_VALUE }
            ?.flatMap { runCatching { RawCodec.parseAsr(it.readText()) }.getOrDefault(emptyList()) } ?: emptyList()
    }
    fun liveSpeech(): List<Span> {
        val root = liveDirectory ?: directory
        if (liveDirectory != null && !liveAllowed) return emptyList()
        val raw = File(root, "speech.tsv").takeIf { it.exists() }?.readLines()
            ?.mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { p ->
                runCatching { Span(p[0].toDouble(), p[1].toDouble()) }.getOrNull()
            } } ?: emptyList()
        return raw.sortedBy { it.start }.fold(mutableListOf<Span>()) { out, span ->
            val last = out.lastOrNull()
            if (last != null && span.start - last.end <= .35) out[out.lastIndex] = Span(last.start, maxOf(last.end, span.end))
            else out += span
            out
        }
    }
    fun saveLiveSpeech(spans: List<Span>) {
        val root = liveDirectory ?: directory
        val file = File(root, "speech.tsv")
        if (spans.isEmpty()) return
        FileOutputStream(file, true).bufferedWriter().use { out ->
            if (file.length() > 0) out.newLine()
            out.write(spans.joinToString("\n") { "${it.start}\t${it.end}" })
        }
    }
    /** Read provisional live windows once so final processing can reuse an
     * embedding even when acoustic segmentation moved a boundary by a few
     * frames.  The caller still validates the overlap before accepting it. */
    fun liveWindows(): List<VoiceWindow> {
        if (!liveAllowed) return emptyList()
        return liveDirectory!!.listFiles { file -> file.name.startsWith("window_") && file.name.endsWith(".bin") }
            ?.mapNotNull { file -> runCatching {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    val span=Span(input.readDouble(),input.readDouble()); val quality=input.readDouble(); val n=input.readInt(); require(n in 1..2048)
                    VoiceWindow(span,FloatArray(n){input.readFloat()},quality)
                }
            }.getOrNull() } ?: emptyList()
    }
    fun window(key: String): VoiceWindow? = File(directory,"window_$key.bin").takeIf { it.exists() }?.let { file -> runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val span=Span(input.readDouble(),input.readDouble()); val quality=input.readDouble(); val n=input.readInt(); require(n in 1..2048)
            VoiceWindow(span,FloatArray(n){input.readFloat()},quality)
        }
    }.getOrNull() } ?: if (liveAllowed) File(liveDirectory!!,"window_$key.bin").takeIf { it.exists() }?.let { file -> runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val span=Span(input.readDouble(),input.readDouble()); val quality=input.readDouble(); val n=input.readInt(); require(n in 1..2048)
            VoiceWindow(span,FloatArray(n){input.readFloat()},quality)
        }
    }.getOrNull() } else null
    fun saveWindow(key: String, w: VoiceWindow) {
        val bytes=ByteArrayOutputStream(); DataOutputStream(bytes).use { it.writeDouble(w.span.start);it.writeDouble(w.span.end);it.writeDouble(w.quality);it.writeInt(w.embedding.size);w.embedding.forEach(it::writeFloat) }
        write(File(directory,"window_$key.bin"),bytes.toByteArray())
    }
    private fun write(file: File, bytes: ByteArray) { val atomic=AtomicFile(file);val stream=atomic.startWrite();try {stream.write(bytes);atomic.finishWrite(stream)}catch(t:Throwable){atomic.failWrite(stream);throw t} }
}
