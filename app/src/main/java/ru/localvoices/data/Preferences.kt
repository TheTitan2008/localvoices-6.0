package ru.localvoices.data

import android.content.Context
import androidx.core.content.edit
import ru.localvoices.BuildConfig
import ru.localvoices.core.DiarizationConfig

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var encoder: String
        get() = if (BuildConfig.ASR_BACKEND == "gigaam") BuildConfig.SPEAKER_ENCODER else prefs.getString("encoder", "speaker")!!
        set(v) { prefs.edit { putString("encoder", v) } }
    var whisperModel: String
        get() = prefs.getString("model", "ggml-base.bin")!!
        set(v) { prefs.edit { putString("model", v) } }
    var language: String
        get() = prefs.getString("language", "ru")!!
        set(v) { prefs.edit { putString("language", v) } }
    var audioSource: String
        get() = prefs.getString("audio_source", "voice_recognition")!!
        set(v) { prefs.edit { putString("audio_source", v) } }
    /** Optional additive pipeline.  Disabled by default to preserve the legacy path. */
    var experimentalCascade: Boolean
        get() = prefs.getBoolean("experimental_cascade", BuildConfig.DEFAULT_EXPERIMENTAL_CASCADE)
        set(v) { prefs.edit { putBoolean("experimental_cascade", v) } }
    var threads: Int
        get() = prefs.getInt("threads", minOf(4,(Runtime.getRuntime().availableProcessors()/2).coerceAtLeast(1))).coerceIn(1, minOf(8,Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
        set(v) { prefs.edit { putInt("threads", v.coerceIn(1, 8)) } }
    fun number(key: String, default: Float) = prefs.getFloat(key, default).toDouble()
    fun setNumber(key: String, value: Float) { prefs.edit { putFloat(key, value) } }
    fun config() = DiarizationConfig(
        knownThreshold = number("threshold", .78f), knownMargin = number("margin", .08f),
        clusterMinSize = number("clusterSize", 3f).toInt(), minSamples = number("minSamples", 2f).toInt(),
        noiseThreshold = number("noise", .86f), smallSetThreshold = number("small", .92f), changeThreshold = number("change", .72f),
        clusteringBackend = BuildConfig.DIARIZATION_BACKEND,
    )
}
