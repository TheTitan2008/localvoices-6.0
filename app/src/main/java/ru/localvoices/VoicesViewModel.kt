package ru.localvoices

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import ru.localvoices.audio.*
import ru.localvoices.core.*
import ru.localvoices.data.*
import ru.localvoices.ml.*
import ru.localvoices.processing.ProcessWorker
import java.io.File
import java.util.UUID

data class DraftSample(val id: String, val file: File, val embedding: FloatArray, val quality: SampleQuality, val encoder: String)
@OptIn(ExperimentalCoroutinesApi::class)
class VoicesViewModel(application: Application, private val savedState: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle()) : AndroidViewModel(application) {
    val app = application as VoicesApp
    val recordings = app.dao.recordings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val profiles = app.dao.profiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selected = MutableStateFlow<String?>(savedState["selected"])
    val utterances = selected.flatMapLatest { if (it == null) flowOf(emptyList()) else app.dao.utterances(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val microphone = app.capture.microphone
    val people = app.dao.people().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val allSpeakers = app.dao.allSpeakers().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val dialogueSpeakers = selected.flatMapLatest { if(it==null)flowOf(emptyList())else app.dao.speakers(it) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val drafts = MutableStateFlow<List<DraftSample>>(emptyList())
    val editingProfile = MutableStateFlow<String?>(null)
    var capturePurpose = "conversation"
        private set
    private var captureFile: File? = null
    private val audioDir = File(app.filesDir, "audio").apply { mkdirs() }
    private val samplesDir = File(app.filesDir, "samples").apply { mkdirs() }
    init {
        viewModelScope.launch {selected.collect {savedState["selected"]=it}}
        viewModelScope.launch {app.capture.recordId.collect {if(it!=null)selected.value=it}}
        viewModelScope.launch {
            microphone.state.collect { state ->
                if (!state.recording && captureFile != null && !busy.value && !app.capture.conversation.value) stopRecording()
            }
        }
    }
    private fun uid() = UUID.randomUUID().toString()
    fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { message.value = e.message ?: "Неизвестная ошибка" } } }
    private fun task(block: suspend () -> Unit) = action { busy.value = true; try { withContext(Dispatchers.IO) { block() } } finally { busy.value = false } }
    fun startRecording(purpose: String) = action {
        require(!busy.value) { "Дождитесь текущей операции" }
        capturePurpose = purpose
        if(purpose=="conversation") { CaptureService.start(app);return@action }
        val file = File(app.cacheDir, "capture-${uid()}.wav"); captureFile = file
        try { microphone.start(file) } catch (e: Exception) { captureFile = null; file.delete(); throw e }
    }
    fun stopRecording() {
        if(app.capture.conversation.value) {CaptureService.stop(app);return}
        if (captureFile == null || busy.value) return
        task {
        val file = microphone.stop() ?: return@task
        try {
            microphone.state.value.error?.let { error(it) }
            require(file.length() >= 32044) { "Запись слишком короткая" }
            if (capturePurpose == "conversation") {
                val id = uid(); val target = File(audioDir, "$id.wav"); file.copyTo(target)
                app.dao.saveRecording(Recording(id, "Разговор ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}", target.absolutePath))
                selected.value = id; message.value = "Запись сохранена"
            } else app.mlLock.withLock { drafts.value += prepareSample(file) }
        } finally { file.delete(); captureFile = null }
        }
    }
    internal suspend fun prepareSample(file: File): DraftSample {
        app.awaitModels()
        val source = PcmFile(file)
        val engines = app.engines
        val spans = engines.vad(source)
        val speechSeconds = spans.sumOf { it.duration }
        require(speechSeconds >= 3) { "Нужно минимум 3 секунды речи" }
        val encoder = engines.embeddingId()
        val whisper = if (encoder.startsWith("whisper")) engines.whisper() else null
        val vectors = mutableListOf<FloatArray>()
        var squared = 0.0; var clipped = 0L; var total = 0L
        try {
            engines.extractor(whisper, encoder).use { extractor ->
                val block = FloatArray(5 * 16000)
                var filled = 0
                fun analyze(count: Int) {
                    if (count < 16000) return
                    val audio = block.copyOf(count)
                    val q = quality(audio)
                    squared += q.rms * q.rms * count
                    clipped += (q.clipping * count).toLong()
                    total += count
                    if (q.rms >= .006 && q.clipping <= .02) vectors += extractor.extract(audio)
                }
                for (span in spans) {
                    var offset = (span.start * 16000).toInt()
                    val end = (span.end * 16000).toInt().coerceAtMost(source.count)
                    while (offset < end) {
                        val n = minOf(block.size - filled, end - offset)
                        source.read(offset, n).copyInto(block, filled)
                        filled += n; offset += n
                        if (filled == block.size) { analyze(filled); filled = 0 }
                    }
                }
                analyze(filled)
            }
        } finally { whisper?.close() }
        val q = SampleQuality(total / 16000.0, kotlin.math.sqrt(squared / total.coerceAtLeast(1)), clipped.toDouble() / total.coerceAtLeast(1))
        require(q.acceptable && vectors.isNotEmpty()) { q.message }
        val vector = Vectors.centroid(Vectors.robustReferences(vectors))
        val id = uid(); val output = File(samplesDir, "$id.wav"); file.copyTo(output)
        return DraftSample(id, output, vector, q, encoder)
    }
    fun importAudio(uri: Uri) = task {
        val id = uid(); val target = File(audioDir, "$id.wav")
        try {
            AudioDecoder(app).decode(uri, target)
            require(target.length() > 32044) { "Аудиозапись слишком короткая" }
            app.dao.saveRecording(Recording(id, "Импортированная запись", target.absolutePath, duration = (target.length() - 44) / 32000.0))
            selected.value = id; message.value = "Аудио импортировано"
        } catch (e: Exception) { target.delete(); throw e }
    }
    fun importAudios(uris: List<Uri>) = task {
        var succeeded=0; val failures=mutableListOf<String>()
        for(uri in uris) {
            val id=uid(); val target=File(audioDir,"$id.wav")
            try {
                AudioDecoder(app).decode(uri,target)
                require(target.length()>32044){"Файл слишком короткий"}
                val title=runCatching {app.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {c->if(c.moveToFirst())c.getString(0)else null}}.getOrNull()
                    ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Импортированный диалог"
                app.dao.saveRecording(Recording(id,title,target.absolutePath,duration=(target.length()-44)/32000.0))
                selected.value=id
                succeeded++
            } catch(e:CancellationException){target.delete();throw e}
            catch(e:Exception){target.delete();failures+=e.message ?: "Не удалось прочитать файл"}
        }
        message.value="Импортировано отдельных диалогов: $succeeded. Ошибок: ${failures.size}." + failures.firstOrNull()?.let {" $it"}.orEmpty()
    }
    fun process(id: String) { ProcessWorker.enqueue(app, id) }
    fun processAll()=action {app.dao.recordingList().filter {it.status in listOf("Готова к обработке","Ошибка","Отменено") || it.status.startsWith("Запись прервана")}.forEach {process(it.id)}}
    fun cancel(id: String) { ProcessWorker.cancel(app, id); action { app.dao.status(id, "Отменено", 0) } }
    fun beginProfile(id: String? = null) {
        drafts.value.forEach { it.file.delete() }; drafts.value = emptyList(); editingProfile.value = id
    }
    fun removeDraft(id: String) { drafts.value.find { it.id == id }?.file?.delete(); drafts.value = drafts.value.filter { it.id != id } }
    fun saveProfile(name: String, replace: Boolean) = task {
        app.mlLock.withLock {
            require(name.isNotBlank()) { "Введите имя" }
            val existing = editingProfile.value?.let { id -> app.dao.profileList().find { it.profile.id == id } }
            val newSamples = drafts.value
            require(newSamples.isNotEmpty()) { "Запишите образцы" }
            val encoder = newSamples.first().encoder
            require(newSamples.all { it.encoder == encoder }) { "Образцы получены разными моделями" }
            require(existing == null || replace || existing.profile.encoder == encoder) { "Модель изменилась: выберите повторную регистрацию" }
            val oldSamples = if (replace) emptyList() else existing?.samples ?: emptyList()
            val vectors = oldSamples.map { EmbeddingCodec.decode(it.embedding) } + newSamples.map { it.embedding }
            val references = Vectors.robustReferences(vectors)
            val acceptedIndices = vectors.indices.filter { index -> references.any { it.contentEquals(Vectors.normalized(vectors[index])) } }.toSet()
            val id = existing?.profile?.id ?: uid()
            val samples = oldSamples.filterIndexed { index, _ -> index in acceptedIndices } + newSamples.mapIndexedNotNull { index, d ->
                if (index + oldSamples.size !in acceptedIndices) null else SampleEntity(d.id, id, d.file.absolutePath, EmbeddingCodec.encode(d.embedding), d.quality.duration, d.quality.rms, d.quality.clipping)
            }
            require(samples.isNotEmpty() && samples.sumOf { it.duration } >= 3) { "Нужен один качественный образец с минимум 3 секундами речи" }
            val domain = VoiceProfile(id, name.trim(), encoder, references)
            val profile = ProfileEntity(id, name.trim(), encoder, domain.dispersion)
            app.database.withTransaction {
                if (existing == null) app.dao.insertProfile(profile) else { app.dao.updateProfile(profile); app.dao.clearSamples(id) }
                app.dao.insertSamples(samples)
            }
            val retained = samples.map { it.path }.toSet()
            (existing?.samples?.map { it.path }.orEmpty() + newSamples.map { it.file.absolutePath }).filter { it !in retained }.forEach { File(it).delete() }
            drafts.value = emptyList(); editingProfile.value = null; message.value = "Голос сохранён"
        }
    }
    fun renameProfile(profile: ProfileEntity, name: String) = action {
        require(name.isNotBlank())
        app.database.withTransaction {
            app.dao.updateProfile(profile.copy(name = name.trim()))
            if(app.dao.personList().any {it.id==profile.id})PeopleRepository(app).name(profile.id,name)
            for (r in app.dao.recordingList()) app.dao.utteranceList(r.id).filter { it.speakerId == profile.id }.forEach { app.dao.updateUtterance(it.copy(name = name.trim())) }
        }
    }
    fun deleteProfile(id: String) = task {
        app.mlLock.withLock {
            val profile = app.dao.profileList().find { it.profile.id == id } ?: return@withLock
            app.database.withTransaction {
                val linked=app.dao.speakerList().filter {it.personId==id}
                app.dao.anonymizeProfile(id); app.dao.deleteProfile(id)
                for (recording in app.dao.recordingList()) {
                    val localIds=linked.filter {it.recordingId==recording.id}.map {it.localId}.toSet()+id
                    app.dao.utteranceList(recording.id).filter {it.speakerId in localIds}.forEach {app.dao.updateUtterance(it.copy(speakerId=null,name="Удалённый голос",confidence=0.0))}
                    val turns = JSONArray(recording.rawTurns)
                    for (i in 0 until turns.length()) {
                        val turn = turns.getJSONArray(i)
                        if (!turn.isNull(2) && turn.getString(2) in localIds) { turn.put(2, JSONObject.NULL); turn.put(3, "Удалённый голос"); turn.put(4, 0.0) }
                    }
                    app.dao.saveRecording(recording.copy(rawTurns = turns.toString()))
                    if(app.dao.allRevisions(recording.id).any {rev->Revisions.decode(rev.data).any {it.speakerId in localIds}})app.dao.deleteRevisions(recording.id)
                }
                app.dao.deletePersonLinks(id);app.dao.clearCandidate(id);app.dao.deletePerson(id)
            }
            profile.samples.forEach { File(it.path).delete() }
            message.value = "Голос и его образцы удалены"
        }
    }
    fun edit(row: UtteranceEntity, text: String, name: String, speaker: String?, entireCluster: Boolean) = action {
        app.database.withTransaction {
            Revisions.save(app,row.recordingId,"Ручная правка")
            val rows = if (entireCluster && row.speakerId != null) app.dao.utteranceList(row.recordingId).filter { it.speakerId == row.speakerId } else listOf(row)
            val assignedId = when {
                speaker == "__unknown__" -> null
                speaker != null -> speaker
                name != row.name && entireCluster && row.speakerId?.startsWith("cluster_") == true -> row.speakerId
                name != row.name -> "manual_${uid()}"
                else -> row.speakerId
            }
            rows.forEach { app.dao.updateUtterance(it.copy(text = if (it.id == row.id) text else it.text, name = name.ifBlank { "Неизвестный" }, speakerId = assignedId, confidence = 0.0)) }
        }
    }
    fun enrollCluster(recording: Recording, speakerId: String, name: String) = task {
        app.mlLock.withLock {
            require(name.isNotBlank()) { "Введите имя" }
            require(recording.encoder == app.engines.embeddingId()) { "Выберите исходный speaker encoder в настройках" }
            val turns = app.dao.utteranceList(recording.id).filter { it.speakerId == speakerId && it.end - it.start >= 3 }.take(6)
            require(turns.isNotEmpty()) { "Нужна хотя бы одна реплика длиной от 3 секунд. Используйте регистрацию голоса." }
            val audio = PcmFile(File(recording.path)); val prepared = mutableListOf<DraftSample>()
            try {
                for (turn in turns) {
                    val temp = File(app.cacheDir, "enroll-${uid()}.wav")
                    try { Wav.write(temp, audio.slice(Span(turn.start, turn.end))); prepared += prepareSample(temp) } finally { temp.delete() }
                }
                val refs = Vectors.robustReferences(prepared.map { it.embedding }); val accepted = prepared.filter { d -> refs.any { it.contentEquals(Vectors.normalized(d.embedding)) } }
                require(accepted.isNotEmpty())
                val id = uid(); val domain = VoiceProfile(id, name.trim(), recording.encoder, refs)
                app.database.withTransaction {
                    app.dao.insertProfile(ProfileEntity(id, name.trim(), recording.encoder, domain.dispersion))
                    app.dao.insertSamples(accepted.map { SampleEntity(it.id, id, it.file.absolutePath, EmbeddingCodec.encode(it.embedding), it.quality.duration, it.quality.rms, it.quality.clipping) })
                }
                prepared.filter { it !in accepted }.forEach { it.file.delete() }
                message.value = "Голос сохранён. Можно связать с ним реплики."
            } catch (e: Exception) { prepared.forEach { it.file.delete() }; throw e }
        }
    }
    fun importModel(slot: String, uri: Uri) = task { app.mlLock.withLock { app.models.import(app, slot, uri); message.value = "Модель $slot установлена" } }
    fun deleteModel(slot: String) = task { app.mlLock.withLock { app.models.file(slot).delete(); message.value = "Модель удалена" } }
    fun importModelPack(uri: Uri)=task {app.mlLock.withLock {app.models.importPack(app,uri);message.value="Пакет моделей установлен"}}
    fun namePerson(id: String, name: String)=task {app.mlLock.withLock {PeopleRepository(app).name(id,name)}}
    fun decideLink(speaker: DialogueSpeakerEntity, confirm: Boolean)=task {app.mlLock.withLock {PeopleRepository(app).decide(speaker,confirm)}}
    fun undo(id: String)=task {
        app.database.withTransaction {
            val revision=app.dao.revisions(id).firstOrNull() ?: error("Предыдущих версий нет")
            app.dao.clearUtterances(id);app.dao.saveUtterances(Revisions.decode(revision.data));app.dao.restoredRevision(revision.id)
        }
        message.value="Предыдущая версия восстановлена"
    }
    fun deleteRecording(id: String)=task {
        ProcessWorker.cancel(app,id)
        app.mlLock.withLock {
            val rec=app.dao.recording(id) ?: return@withLock
            app.database.withTransaction{app.dao.deleteRevisions(id);app.dao.deleteRecording(id)}
            val file=File(rec.path)
            if(file.canonicalFile.parentFile==audioDir.canonicalFile)file.delete()
            File(app.filesDir,"processing/$id").takeIf{it.canonicalPath.startsWith(File(app.filesDir,"processing").canonicalPath+File.separator)}?.let{dir->dir.listFiles()?.filter{it.isFile}?.forEach{it.delete()};dir.delete()}
            if(selected.value==id)selected.value=null
        }
    }
    fun remerge(id: String) = task {
        app.awaitModels()
        app.mlLock.withLock {
            val rec = app.dao.recording(id) ?: return@withLock
            require(rec.encoder.isNotEmpty()) { "Сначала обработайте запись" }
            val profiles = app.dao.profileList().filter { it.profile.encoder == rec.encoder }.map { it.domain() }
            val windows = app.dao.windows(id).map { VoiceWindow(Span(it.start, it.end), EmbeddingCodec.decode(it.embedding), it.quality) }
            val turns = Diarizer(app.prefs.config()).run(windows, profiles)
            app.database.withTransaction {
                val links=PeopleRepository(app).link(id,rec.encoder,windows,turns,profiles)
                val people=app.dao.personList().associateBy {it.id}
                val names=links.associate {it.localId to people[it.personId]?.name}
                val cachedAsr = RawCodec.parseAsr(rec.rawAsr)
                val words = if (cachedAsr.any { it.text.contains("▁") }) SentencePieceReconstructor.gigaAm(cachedAsr) else cachedAsr
                val rows = TranscriptMerger.merge(words, turns).map {it.copy(name=names[it.speakerId] ?: it.name)}
                Revisions.save(app,id,"Перед пересчётом говорящих")
                app.dao.clearUtterances(id); app.dao.saveUtterances(rows.map { UtteranceEntity(uid(), id, it.span.start, it.span.end, it.speakerId, it.name, it.text, it.confidence) })
                app.dao.saveRecording(rec.copy(rawTurns = RawCodec.turns(turns)))
            }
            message.value = "Диаризация пересчитана"
        }
    }
    fun export(uri: Uri, format: String, recordingId: String) = task {
        val rows = app.dao.utteranceList(recordingId)
        val text = when (format) {
            "json" -> TranscriptExport.json(rows.map {Utterance(Span(it.start,it.end),it.speakerId,it.name,it.text,it.confidence)})
            "srt" -> rows.mapIndexed { index, r -> "${index + 1}\n${timestamp(r.start, true)} --> ${timestamp(r.end, true)}\n${r.name}: ${r.text.trim()}\n" }.joinToString("\n")
            else -> rows.joinToString("\n\n") { "[${timestamp(it.start)}–${timestamp(it.end)}] ${it.name}:\n${it.text.trim()}" }
        }
        app.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(text) }
        message.value = "Расшифровка экспортирована"
    }
    fun exportAudio(uri: Uri, recordingId: String) = task {
        val recording = app.dao.recording(recordingId) ?: error("Запись не найдена")
        val source = File(recording.path)
        require(source.isFile && source.length() >= 44) { "Исходное аудио не найдено" }
        app.contentResolver.openOutputStream(uri, "wt")!!.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
        message.value = "Аудио экспортировано"
    }
    fun exportAll(uri: Uri)=task {
        val manifest=JSONObject().put("format_version",2)
        val dialogues=JSONArray(); val people=JSONArray();val links=JSONArray()
        app.dao.personList().forEach {people.put(JSONObject().put("person_id",it.id).put("name",it.name ?: JSONObject.NULL))}
        app.dao.speakerList().forEach {links.put(JSONObject().put("dialog_id",it.recordingId).put("local_speaker_id",it.localId).put("person_id",it.personId).put("candidate_id",it.candidateId ?: JSONObject.NULL).put("decision",it.decision))}
        app.contentResolver.openOutputStream(uri,"wt")!!.use { output -> java.util.zip.ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String){zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(text.toByteArray(Charsets.UTF_8));zip.closeEntry()}
            for(rec in app.dao.recordingList()) {
                val rows=app.dao.utteranceList(rec.id)
                entry("${rec.id}.json",TranscriptExport.json(rows.map {Utterance(Span(it.start,it.end),it.speakerId,it.name,it.text,it.confidence)}))
                entry("${rec.id}.txt",rows.joinToString("\n\n"){"[${timestamp(it.start)}–${timestamp(it.end)}] ${it.name}:\n${it.text.trim()}"})
                val audio = File(rec.path)
                if (audio.isFile) {
                    zip.putNextEntry(java.util.zip.ZipEntry("${rec.id}.wav"))
                    audio.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                dialogues.put(JSONObject().put("dialog_id",rec.id).put("title",rec.title).put("status",rec.status).put("duration_ms",(rec.duration*1000).toLong()).put("audio_file", if (audio.isFile) "${rec.id}.wav" else JSONObject.NULL).put("processing_ms",rec.processingMs).put("processing_stats",ProcessingStatsCodec.decode(rec.processingStats)).put("acoustic_events",JSONArray(rec.acousticEvents)))
            }
            entry("project.json",manifest.put("dialogues",dialogues).put("people",people).put("speaker_links",links).toString(2))
        } }
        message.value="Все диалоги экспортированы в ZIP"
    }
}
fun timestamp(seconds: Double, srt: Boolean = false): String {
    val ms = (seconds * 1000).toLong().coerceAtLeast(0)
    return if (srt) "%02d:%02d:%02d,%03d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    else "%02d:%02d".format(ms / 60000, ms / 1000 % 60)
}
