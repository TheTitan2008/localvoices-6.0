package ru.localvoices.llm

/** A real CPU llama.cpp context. Call from a background dispatcher, one caller at a time. */
class LocalLlm(path: String, threads: Int) : AutoCloseable {
    private var handle = open(path, threads)
    fun complete(text: String): String {
        check(handle != 0L)
        return generate(handle, text.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)
    }
    fun cancel() { if (handle != 0L) abort(handle) }
    override fun close() { if (handle != 0L) { release(handle); handle = 0 } }
    private external fun open(path: String, threads: Int): Long
    private external fun generate(handle: Long, text: ByteArray): ByteArray
    private external fun abort(handle: Long)
    private external fun release(handle: Long)
    companion object { init { System.loadLibrary("local_llm") } }
}
