package ru.localvoices.audio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.localvoices.VoicesApp
import ru.localvoices.BuildConfig
import ru.localvoices.core.*
import ru.localvoices.data.Recording
import ru.localvoices.ml.GigaAmEngine
import ru.localvoices.ml.WhisperEngine
import ru.localvoices.ml.quality
import ru.localvoices.processing.ProcessWorker
import ru.localvoices.processing.ProcessingCache
import java.io.File
import java.util.UUID
import kotlin.math.min

/** Application-owned capture survives Activity recreation and remains independent of the list UI. */
class CaptureController(private val app: VoicesApp) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val microphone=Microphone(scope) { app.prefs.audioSource }
    val conversation=MutableStateFlow(false)
    val recordId=MutableStateFlow<String?>(null)
    val preview=MutableStateFlow<List<AsrUnit>>(emptyList())
    val previewStatus=MutableStateFlow("")
    /** Number of provisional speaker windows computed while recording. */
    val liveEmbeddingCount=MutableStateFlow(0)
    val liveSpeakerCount=MutableStateFlow(0)
    val closing=MutableStateFlow(false)
    private var file: File?=null
    private var previewJob: Job?=null
    private var previewEngine: AutoCloseable?=null
    private var previewCanceller: (() -> Unit)?=null
    private val finishLock=Mutex()
    init {scope.launch {microphone.state.collect {state->if(conversation.value && !state.recording && !closing.value)CaptureService.stop(app)}}}
    suspend fun start() {
        require(!microphone.state.value.recording)
        val id=UUID.randomUUID().toString()
        val audio=File(app.filesDir,"audio").apply{mkdirs()}
        val target=File(audio,"$id.wav");file=target;recordId.value=id;preview.value=emptyList()
        app.dao.saveRecording(Recording(id,"Разговор "+java.text.SimpleDateFormat("dd.MM HH:mm",java.util.Locale.getDefault()).format(java.util.Date()),target.absolutePath,status="Запись"))
        liveEmbeddingCount.value = 0; liveSpeakerCount.value = 0
        conversation.value=true
        try { microphone.start(target) } catch(e:Exception) { conversation.value=false;app.dao.status(id,"Ошибка записи",0,e.message);throw e }
        previewJob=scope.launch(Dispatchers.Default) {
            try { app.awaitModels() } catch(e:CancellationException) {throw e} catch(e:Exception) {previewStatus.value="Подготовка моделей: ${e.message}. Аудио продолжает записываться.";return@launch}
            if(!app.mlLock.tryLock()) {previewStatus.value="Черновик недоступен: выполняется другая обработка";return@launch}
            try {
                val isGigaAm = BuildConfig.ASR_BACKEND == "gigaam"
                val language = app.prefs.language
                val asrFingerprint = if (isGigaAm) app.models.fingerprint("gigaam-v3-e2e-ctc-Q4_K_M.gguf") else app.models.fingerprint(app.engines.whisperModel)
                val liveFingerprint = "live-v1:${BuildConfig.ASR_BACKEND}:$language:$asrFingerprint:${app.engines.embeddingId()}:${app.models.fingerprint("segmentation.onnx")}:preprocess-v2:reconstruct-v2:cascade=${app.prefs.experimentalCascade}"
                val liveCache = ProcessingCache(File(app.filesDir,"processing-live"), id, liveFingerprint)
                val local: AutoCloseable
                val transcribe: (FloatArray) -> List<AsrUnit>
                if (isGigaAm) {
                    val giga = GigaAmEngine(app.models.file("gigaam-v3-e2e-ctc-Q4_K_M.gguf"), app.prefs.threads)
                    local = giga
                    transcribe = { input -> SentencePieceReconstructor.gigaAm(giga.transcribe(input, "ru")) }
                } else {
                    val whisper = app.engines.whisper()
                    local = whisper
                    previewCanceller = { whisper.cancel() }
                    transcribe = { input -> whisper.transcribe(input, app.prefs.language) }
                }
                val liveTracker = OnlineSpeakerTracker()
                val embeddingEngine = if (isGigaAm && app.engines.usesWhisper) app.engines.whisper() else null
                val liveExtractor = runCatching {
                    app.engines.extractor(embeddingEngine ?: if (isGigaAm) null else local as? WhisperEngine, app.engines.embeddingId())
                }.getOrNull()
                synchronized(this@CaptureController){previewEngine=local}
                try {
                    var cursor=0
                    var liveAsrIndex=0
                    val minPreviewSamples = if (isGigaAm) 4 * 16000 else 6 * 16000
                    val maxPreviewSamples = if (isGigaAm) 8 * 16000 else 12 * 16000
                    previewStatus.value = if (isGigaAm) "Черновик GigaAM · окончательный текст уточнится после остановки"
                        else "Черновик Whisper · окончательный текст уточнится после остановки"
                    while(isActive && microphone.state.value.recording) {
                        delay(if (isGigaAm) 500 else 1000)
                        val input=PcmFile(target)
                        if(input.count-cursor<minPreviewSamples)continue
                        val count=min(maxPreviewSamples,input.count-cursor)
                        val raw=input.read(cursor,count)
                        val localSpeech = app.engines.vad(raw)
                        if(localSpeech.isNotEmpty()) {
                            val offset=cursor/16000.0
                            liveCache.saveLiveSpeech(localSpeech.map { Span(offset + it.start, offset + it.end) })
                            // Speaker embeddings are computed incrementally on
                            // completed speech windows.  Final processing still
                            // reconciles these provisional labels globally.
                            liveExtractor?.let { extractor ->
                                val spans = SpeechWindowPlanner.plan(localSpeech, 2.5, 1.25)
                                for (window in spans) {
                                    val audio = raw.copyOfRange((window.start * 16000).toInt().coerceIn(0, raw.size), (window.end * 16000).toInt().coerceIn(0, raw.size))
                                    if (audio.size >= 16000 && quality(audio).rms >= .0005) {
                                        val globalWindow = Span(offset + window.start, offset + window.end)
                                        val vector = extractor.extract(audio)
                                        liveTracker.observe(vector)
                                        val liveQuality = if (quality(audio).rms >= .006 && quality(audio).clipping <= .02) 1.0 else .2
                                        liveCache.saveWindow("${(globalWindow.start*16000).toLong()}_${(globalWindow.end*16000).toLong()}", VoiceWindow(globalWindow, vector, liveQuality))
                                        liveEmbeddingCount.value += 1
                                        liveSpeakerCount.value = liveTracker.size()
                                    }
                                }
                            }
                            // Start GigaAM text inference only after the cheap
                            // speech windows have been handed to the speaker
                            // encoder, so speaker evidence begins accumulating
                            // as early as possible.
                            val rows=transcribe(raw).map { a -> a.copy(span=Span(offset+a.span.start,offset+a.span.end)) }
                            liveCache.saveAsr(liveAsrIndex++, rows)
                            preview.value=preview.value+rows
                        }
                        cursor+=count
                    }
                } finally {
                    synchronized(this@CaptureController){previewEngine=null;previewCanceller=null;liveExtractor?.close();local.close()}; embeddingEngine?.close()
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){previewStatus.value="Черновик: ${e.message}. Аудио продолжает записываться."}
            finally {app.mlLock.unlock()}
        }
    }
    suspend fun finish()=finishLock.withLock {
        if(!conversation.value)return@withLock
        closing.value=true
        try {
            microphone.stop()
            synchronized(this){previewCanceller?.invoke()}
            // Give the live pipeline a short tail window after AudioRecord has
            // stopped. The loop observes recording=false, flushes the final
            // chunk and writes its ASR/embedding cache, which the final worker
            // can reuse instead of recomputing the whole recording.
            previewJob?.let { job ->
                withTimeoutOrNull(2500) { job.join() }
                if (job.isActive) {
                    synchronized(this){previewCanceller?.invoke()}
                    job.cancelAndJoin()
                }
            }
            previewJob=null
            val id=recordId.value ?: return@withLock
            val input=file ?: return@withLock
            if(input.length()>32044) {
                val error=microphone.state.value.error
                app.dao.status(id,if(error==null) "Готова к обработке" else "Запись прервана: доступен фрагмент",0,error)
                if(error==null)ProcessWorker.enqueue(app,id)
            } else app.dao.status(id,"Запись слишком короткая",0)
        } finally {conversation.value=false;closing.value=false}
    }
}

class CaptureService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var starting=false
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app=application as VoicesApp
        if(intent?.action==STOP) {
            scope.launch { try{app.capture.finish()}finally{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()} }
            return START_NOT_STICKY
        }
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("capture","Запись разговора",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,0,Intent(this,CaptureService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Идёт запись разговора")
            .setContentText("Аудио сохраняется на устройстве").setOngoing(true).addAction(android.R.drawable.ic_media_pause,"Остановить",stop).build()
        if(Build.VERSION.SDK_INT>=30)startForeground(1001,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1001,notification)
        if(!app.capture.conversation.value && !starting) {starting=true;scope.launch {
            try {app.capture.start()}
            catch(e:Exception) {app.capture.previewStatus.value=e.message ?: "Ошибка записи";stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
            finally {starting=false}
        }}
        return START_NOT_STICKY
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    companion object {
        const val STOP="ru.localvoices.STOP_CAPTURE"
        fun start(context: Context)=ContextCompat.startForegroundService(context,Intent(context,CaptureService::class.java))
        fun stop(context: Context)=context.startService(Intent(context,CaptureService::class.java).setAction(STOP))
    }
}
