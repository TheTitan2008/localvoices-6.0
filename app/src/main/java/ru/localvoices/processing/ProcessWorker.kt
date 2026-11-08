package ru.localvoices.processing

import android.app.*
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import ru.localvoices.*
import ru.localvoices.audio.*
import ru.localvoices.core.*
import ru.localvoices.data.*
import ru.localvoices.ml.*
import java.io.File
import java.util.UUID
import kotlin.math.*

class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as VoicesApp
    private val id = inputData.getString("id")!!
    private var activeWhisper: WhisperEngine? = null
    private var activeLlm: ru.localvoices.llm.LocalLlm? = null
    private val stageTimes = linkedMapOf<String, Long>()
    private var activeStage = 0
    private var stageStartedAt = 0L
    private var overallStartedAt = 0L
    private val stages = listOf("Подготовка аудио", "Поиск речевых фрагментов", "Распознавание текста", "Извлечение голосовых признаков", "Сопоставление известных голосов", "Кластеризация HDBSCAN", "Формирование текста", "Сохранение результата")
    private suspend fun stage(number: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (activeStage > 0) stageTimes["stage_$activeStage"] = now - stageStartedAt
        activeStage = number; stageStartedAt = now
        app.dao.status(id, stages[number - 1], number)
        setProgress(workDataOf("stage" to number, "stageName" to stages[number - 1], "completed" to 0, "total" to 0, "etaMs" to 0))
        setForeground(notification(stages[number - 1]))
    }
    private suspend fun reportProgress(completed: Int, total: Int) {
        if (activeStage <= 0 || total <= 0) return
        val elapsed = (android.os.SystemClock.elapsedRealtime() - overallStartedAt).coerceAtLeast(0)
        val eta = if (completed > 0) (elapsed.toDouble() * (total - completed) / completed).toLong().coerceAtLeast(0) else 0L
        val label = stages[activeStage - 1]
        app.dao.status(id, "$label · $completed/$total · осталось примерно ${eta / 1000} с", activeStage)
        setProgress(workDataOf("stage" to activeStage, "stageName" to label, "completed" to completed, "total" to total, "etaMs" to eta))
    }
    private fun notification(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("processing", "Обработка аудио", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "processing").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Локальные голоса").setContentText(text).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Отменить", WorkManager.getInstance(applicationContext).createCancelPendingIntent(getId())).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id.hashCode(), notification)
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        // A separate cancellation watcher interrupts native ASR while its JNI call is blocking.
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) { if (isStopped) { synchronized(this@ProcessWorker) { activeWhisper?.cancel(); activeLlm?.cancel() }; break }; delay(150) }
        }
        try {
            setForeground(notification("Ожидание обработки"))
            app.awaitModels()
            app.dao.status(id,"В очереди",0)
            app.mlLock.withLock {
                val recording = app.dao.recording(id) ?: return@withLock
                val started = android.os.SystemClock.elapsedRealtime()
                overallStartedAt = started
                val counters = linkedMapOf<String, Long>()
                fun count(name: String, delta: Long = 1L) { counters[name] = (counters[name] ?: 0L) + delta }
                if (BuildConfig.ASR_BACKEND != "gigaam") app.models.requireFile("punctuation.gguf")
                val config = app.prefs.config()
                val experimentalCascade = app.prefs.experimentalCascade
                val engines = app.engines
                val language = app.prefs.language
                stage(1)
                val samples = PcmFile(File(recording.path))
                require(samples.count >= 16000) { "Запись короче секунды" }
                val source = File(recording.path)
                val asrModelFingerprint = if (BuildConfig.ASR_BACKEND == "gigaam") app.models.fingerprint("gigaam-v3-e2e-ctc-Q4_K_M.gguf") else app.models.fingerprint(engines.whisperModel)
                val segmentationFingerprint = app.models.fingerprint("segmentation.onnx")
                val liveFingerprint = "live-v1:${BuildConfig.ASR_BACKEND}:$language:$asrModelFingerprint:${engines.embeddingId()}:$segmentationFingerprint:preprocess-v2:reconstruct-v2:cascade=$experimentalCascade"
                val cache = ProcessingCache(File(app.filesDir,"processing"),id,"v6:${BuildConfig.ASR_BACKEND}:${source.length()}:${source.lastModified()}:$language:$asrModelFingerprint:${engines.embeddingId()}:$segmentationFingerprint:preprocess-v2:reconstruct-v2:cascade=$experimentalCascade",
                    liveRoot = File(app.filesDir,"processing-live"), liveFingerprint = liveFingerprint)
                stage(2)
                val cachedSpeech = cache.liveSpeech()
                val speech = if (cachedSpeech.isNotEmpty() && cachedSpeech.maxOf { it.end } >= samples.duration - 1.0) {
                    count("vad_cache_hits")
                    cachedSpeech
                } else {
                    count("vad_full_runs")
                    engines.vad(samples)
                }
                require(speech.isNotEmpty()) { "Речь не обнаружена" }
                count("speech_spans", speech.size.toLong())
                count("audio_ms", (samples.duration * 1000.0).toLong())
                val liveWindows = cache.liveWindows()
                fun cachedEmbedding(window: Span, key: String): VoiceWindow? {
                    cache.window(key)?.let { count("embedding_cache_hits"); return it }
                    // Live windows are provisional and may be shifted slightly
                    // by the final acoustic segmentation. Reuse only a strong
                    // temporal match; never copy an embedding from a different
                    // speaker-sized interval.
                    return liveWindows.asSequence()
                        .map { it to it.span.overlap(window) / max(.001, min(it.span.duration, window.duration)) }
                        .filter { it.second >= .80 }
                        .maxByOrNull { it.second }
                        ?.let { (value, _) -> count("live_embedding_hits"); value.copy(span = window) }
                }
                val asr = mutableListOf<AsrUnit>()
                val windows = mutableListOf<VoiceWindow>()
                var acoustic = AcousticSegments(emptyList(),emptyList())
                val encoderId = engines.embeddingId()
                val whisper = if (engines.usesWhisper) engines.whisper() else null
                val gigaam = if (BuildConfig.ASR_BACKEND == "gigaam") GigaAmEngine(app.models.file("gigaam-v3-e2e-ctc-Q4_K_M.gguf"), app.prefs.threads) else null
                synchronized(this@ProcessWorker) { activeWhisper = whisper }
                try {
                    stage(3)
                    // Keep brief pauses and ASR context, independently of speaker windows.
                    val blocks = mutableListOf<Span>()
                    val gigaBlockLimit = 24.0
                    for (s in speech) {
                        val span=Span(max(0.0,s.start-.15),min(samples.duration,s.end+.15))
                        val last=blocks.lastOrNull()
                        val limit = if (BuildConfig.ASR_BACKEND == "gigaam") gigaBlockLimit else 120.0
                        if(last!=null && span.start-last.end<=1.2 && span.end-last.start<=limit) blocks[blocks.lastIndex]=Span(last.start,span.end)
                        else blocks+=span
                    }
                    // GigaAM's ordinary offline transcribe path is intended for
                    // short utterance blocks. Split long VAD runs before native
                    // inference; keep a small overlap only when a hard cut is
                    // unavoidable so words at the boundary retain context.
                    if (BuildConfig.ASR_BACKEND == "gigaam") {
                        val split = mutableListOf<Span>()
                        for (block in blocks) {
                            var start = block.start
                            while (start < block.end) {
                                val end = min(start + gigaBlockLimit, block.end)
                                split += Span(start, end)
                                if (end >= block.end) break
                                start = end - .35
                            }
                        }
                        blocks.clear(); blocks += split
                    }
                    val liveAsr = if (BuildConfig.ASR_BACKEND == "gigaam") cache.liveAsr()
                        .sortedBy { it.span.start }
                        .fold(mutableListOf<AsrUnit>()) { out, row ->
                            val duplicate = out.any { old ->
                                old.text.trim().equals(row.text.trim(), ignoreCase = true) &&
                                    kotlin.math.abs(old.span.start - row.span.start) < .8
                            }
                            if (!duplicate) out += row
                            out
                        } else emptyList()
                    count("live_asr_rows", liveAsr.size.toLong())
                    asr += liveAsr
                    count("asr_blocks_total", blocks.size.toLong())
                    for ((index,span) in blocks.withIndex()) {
                        ensureActive(); if (isStopped) throw CancellationException()
                        val liveCoverage = liveAsr.filter { it.span.overlap(span) > 0.0 }
                            .sumOf { it.span.overlap(span) }.coerceAtMost(span.duration) / span.duration.coerceAtLeast(.001)
                        // Live ASR is canonical for a block once most of its
                        // time is covered. Only uncovered/partial blocks are
                        // sent through the expensive final GigaAM call.
                        if (BuildConfig.ASR_BACKEND == "gigaam" && liveCoverage >= .55) {
                            count("asr_blocks_reused_live")
                            continue
                        }
                        val cached = cache.asr(index)
                        if (cached != null) count("asr_blocks_reused_disk")
                        else count("asr_blocks_inferred")
                        val rows = cached ?: run {
                            // Optional first-stage rejection: VAD can occasionally
                            // mark a very quiet non-speech burst. Avoid invoking
                            // ASR for only the unmistakable near-silence case.
                            // Ordinary quiet speech is retained.
                            if (BuildConfig.ASR_BACKEND == "gigaam" && quality(samples.slice(span)).rms < .0005) {
                                count("asr_blocks_skipped_quiet")
                                cache.saveAsr(index, emptyList())
                                emptyList()
                            } else {
                            val decoded = if (gigaam != null) gigaam.transcribe(samples.slice(span), "ru") else whisper!!.transcribe(samples.slice(span), language)
                            decoded.filter { it.text.isNotEmpty() }.map {
                                it.copy(span = Span(span.start + it.span.start.coerceAtMost(span.duration), span.start + it.span.end.coerceAtMost(span.duration)))
                            }.also { cache.saveAsr(index, it) }
                            }
                        }
                        val acceptedRows = if (BuildConfig.ASR_BACKEND == "gigaam") {
                            rows.filter { row ->
                                val key = row.text.trim().lowercase().replace(Regex("\\s+"), " ")
                                asr.none { old -> old.text.trim().lowercase().replace(Regex("\\s+"), " ") == key && kotlin.math.abs(old.span.start - row.span.start) < .8 }
                            }
                        } else rows
                        asr += acceptedRows
                        reportProgress(index + 1, blocks.size)
                    }
                    stage(4)
                    // Do not keep the large ASR context resident with the dedicated diarizer.
                    if (whisper != null && !engines.usesWhisper) synchronized(this@ProcessWorker) {activeWhisper=null;whisper.close()}
                    acoustic = AcousticSegmenter(app.models,app.prefs.threads).run(samples)
                    // Keep the Whisper-v4 segmentation contract: acoustic spans are the source
                    // of embedding windows. Silero remains a quality gate below, but intersecting
                    // the two segmenters here can erase most windows because their timestamps use
                    // different frame grids (the main v5 regression).
                    val speechOnlySpans = acoustic.speech.ifEmpty { speech }
                    engines.extractor(whisper, encoderId).use { extractor ->
                        for (span in speechOnlySpans) {
                            val sparse = experimentalCascade && span.duration >= 8.0
                            val embeddingStep = if (sparse) 3.75 else 1.25
                            val embeddingWindow = if (sparse) 3.0 else 2.5
                            for (window in SpeechWindowPlanner.plan(listOf(span), embeddingWindow, embeddingStep)) {
                                ensureActive(); if (isStopped) throw CancellationException()
                                val audio = samples.slice(window); val q = quality(audio)
                                val overlap = acoustic.overlaps.sumOf {it.overlap(window)}
                                // VAD already marked this as speech.  Only skip
                                // unmistakably unusable windows in the optional
                                // cascade; quiet speech remains eligible.
                                // Do not spend speaker-encoder time on unmistakable
                                // silence/clipping. VAD remains the primary speech
                                // gate; quiet but plausible speech is retained.
                                 if (q.rms < .0005 || q.clipping > .20) { count("embedding_windows_skipped_quality"); continue }
                                if (experimentalCascade && overlap < window.duration * .15 &&
                                    (q.rms < .0005 || q.clipping > .20)) {
                                     count("embedding_windows_skipped_cascade"); continue
                                }
                                val score = if (q.rms >= .006 && q.clipping <= .02 && overlap < window.duration * .15) 1.0 else .2
                                val key="${(window.start*16000).toLong()}_${(window.end*16000).toLong()}"
                                count("embedding_windows_total")
                                windows += cachedEmbedding(window, key) ?: VoiceWindow(window, extractor.extract(audio), score).also {cache.saveWindow(key,it)}
                                require(windows.size <= 3000) { "Слишком много речевых окон: разделите запись" }
                            }
                        }
                        // Acoustic change candidates come from neighboring overlapping windows,
                        // independently of ASR sentence boundaries. Refine to 1.25-second anchors.
                        val initial = windows.toList()
                        for ((a, b) in initial.zipWithNext()) {
                            ensureActive()
                            if (a.span.overlap(b.span) <= 0 || a.quality < .5 || b.quality < .5 ||
                                Vectors.cosine(a.embedding, b.embedding) >= config.changeThreshold) continue
                            val boundary = (a.span.start + a.span.end + b.span.start + b.span.end) / 4
                            for (anchor in listOf(Span(max(a.span.start, boundary - 1.25), boundary), Span(boundary, min(b.span.end, boundary + 1.25)))) {
                                if (anchor.duration < 1) continue
                                val audio = samples.slice(anchor); val q = quality(audio)
                                if (q.rms >= .006 && q.clipping <= .02 && acoustic.overlaps.none {it.overlap(anchor)>.1}) {
                                    val key="anchor_${(anchor.start*16000).toLong()}_${(anchor.end*16000).toLong()}"
                                    windows += cachedEmbedding(anchor, key) ?: VoiceWindow(anchor, extractor.extract(audio)).also {cache.saveWindow(key,it)}
                                }
                                require(windows.size <= 3000) { "Слишком много акустических окон: разделите запись" }
                            }
                        }
                        windows.sortBy { it.span.start }
                    }
                } finally { synchronized(this@ProcessWorker) { activeWhisper = null }; whisper?.close(); gigaam?.close() }
                stage(5)
                val profiles = app.dao.profileList().filter { it.profile.encoder == encoderId }.map { it.domain() }
                count("profiles", profiles.size.toLong())
                stage(6)
                count("clustering_windows", windows.size.toLong())
                val context = currentCoroutineContext()
                val turns = Diarizer(config).run(windows, profiles) { context.ensureActive() }
                count("turns", turns.size.toLong())
                ensureActive(); if (isStopped) throw CancellationException()
                stage(7)
                val words = if (BuildConfig.ASR_BACKEND == "gigaam") SentencePieceReconstructor.gigaAm(asr) else asr
                val rawResult = TranscriptMerger.merge(words, turns, wordLevel = BuildConfig.WORD_LEVEL_ATTRIBUTION)
                val punctuation = if (BuildConfig.ASR_BACKEND == "gigaam") PunctuationResult(rawResult, 0, 0) else
                    Punctuator(app.models,app.prefs.threads).run(rawResult) { llm -> synchronized(this@ProcessWorker) {activeLlm=llm} }
                count("asr_rows", asr.size.toLong())
                count("utterances", punctuation.rows.size.toLong())
                count("overlap_events", acoustic.overlaps.size.toLong())
                stage(8)
                stageTimes["stage_8"] = android.os.SystemClock.elapsedRealtime() - stageStartedAt
                val totalMs = android.os.SystemClock.elapsedRealtime() - started
                counters["total_ms"] = totalMs
                app.database.withTransaction {
                    val links=PeopleRepository(app).link(id,encoderId,windows,turns,profiles)
                    val people=app.dao.personList().associateBy {it.id}
                    val names=links.associate {it.localId to people[it.personId]?.name}
                    val result=punctuation.rows.map {it.copy(name=names[it.speakerId] ?: it.name)}
                    Revisions.save(app,id,"Перед повторной обработкой")
                    app.dao.clearUtterances(id); app.dao.clearWindows(id)
                    app.dao.saveUtterances(result.map { UtteranceEntity(UUID.randomUUID().toString(), id, it.span.start, it.span.end, it.speakerId, it.name, it.text, it.confidence) })
                    app.dao.saveWindows(windows.map { WindowEntity(UUID.randomUUID().toString(), id, it.span.start, it.span.end, EmbeddingCodec.encode(it.embedding), it.quality) })
                    app.dao.saveRecording(recording.copy(status = "Готово", stage = 8, duration = samples.duration, rawAsr = RawCodec.asr(asr), rawTurns = RawCodec.turns(turns), encoder = encoderId,
                        punctuationStatus="LLM: принято ${punctuation.accepted}, отклонено ${punctuation.rejected}",processingMs=totalMs,
                        processingStats=ProcessingStatsCodec.withSummary(ProcessingStatsCodec.encode(stageTimes, counters), totalMs, (samples.duration * 1000).toLong()),
                        acousticEvents=org.json.JSONArray().apply {acoustic.overlaps.forEach {put(org.json.JSONObject().put("type","overlap").put("start_ms",(it.start*1000).toLong()).put("end_ms",(it.end*1000).toLong()))}}.toString()))
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { app.dao.status(id, "Отменено", 0) }; throw e
        } catch (t: Exception) {
            app.dao.status(id, "Ошибка", 0, t.message ?: t.javaClass.simpleName)
            Result.failure(workDataOf("error" to (t.message ?: "Ошибка обработки")))
        } finally { watcher.cancel() }
    }
    companion object {
        fun enqueue(context: Context, id: String) = WorkManager.getInstance(context).enqueueUniqueWork("process_$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ProcessWorker>().setInputData(workDataOf("id" to id)).build())
        fun cancel(context: Context, id: String) = WorkManager.getInstance(context).cancelUniqueWork("process_$id")
    }
}
