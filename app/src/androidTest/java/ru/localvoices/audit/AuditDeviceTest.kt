package ru.localvoices.audit

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.json.JSONArray
import ru.localvoices.*
import ru.localvoices.audio.*
import ru.localvoices.core.*
import ru.localvoices.data.*
import ru.localvoices.ml.*
import ru.localvoices.processing.ProcessWorker
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AuditDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as VoicesApp
    private val fixture get() = File(app.filesDir, "audit-input/jfk.wav")
    private fun uid() = UUID.randomUUID().toString()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(timeout: Long = 30000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        assertTrue("Timeout waiting for app state", condition())
    }
    private fun vm(): VoicesViewModel { lateinit var v: VoicesViewModel; main { v=VoicesViewModel(app) }; return v }
    @Before fun prepare() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking {withTimeout(30000){app.mlLock.withLock {}}}
        val input = File(app.filesDir, "audit-input")
        for (slot in listOf("ggml-base.bin","silero_vad.onnx","speaker.onnx","segmentation.onnx","punctuation.gguf")) {
            val target=app.models.file(slot)
            if (!target.exists()) File(input,slot).copyTo(target)
        }
        app.prefs.language="en"; app.prefs.encoder="speaker"; app.prefs.whisperModel="ggml-base.bin"
        app.prefs.threads=1
    }
    @Test fun a_uiLaunchAndScreens() {
        val device=UiDevice.getInstance(instrumentation)
        app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Записать разговор")),15000))
        device.findObject(By.text("Голоса")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Добавить голос")),5000))
        device.findObject(By.text("Добавить голос")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Сохранить голос")),5000))
        device.pressBack()
        device.findObject(By.text("Настройки")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Модель Whisper")),5000))
    }
    @Test fun b_actualNativeWhisperAndBothEncoders() = runBlocking {
        val samples=Wav.read(fixture)
        app.engines.whisper().use { whisper ->
            val text=whisper.transcribe(samples,"en").joinToString("") { it.text }
            assertTrue("Actual ASR: "+text,text.lowercase().contains("country"))
            val vector=whisper.embedding(samples.copyOfRange(0,80000))
            assertEquals(512,vector.size)
            assertTrue(abs(vector.sumOf { it.toDouble()*it }-1)<1e-4)
        }
        app.engines.extractor(null,app.engines.embeddingId()).use { encoder ->
            val a=encoder.extract(samples.copyOfRange(0,80000))
            assertEquals(256,a.size)
            assertTrue(a.all { it.isFinite() })
            assertTrue(Vectors.cosine(a,encoder.extract(samples.copyOfRange(0,80000)))>.999)
        }
    }
    @Test fun c_vadSpeechSilenceQuality() = runBlocking {
        assertTrue(app.engines.vad(Wav.read(fixture)).sumOf { it.duration }>3)
        assertTrue(app.engines.vad(FloatArray(16000*3)).isEmpty())
        assertFalse(quality(FloatArray(16000*4)).acceptable)
        assertFalse(quality(FloatArray(16000*4) { 1f }).acceptable)
    }
    @Test fun d_importDecodeRealWav() = runBlocking {
        val out=File(app.cacheDir,"audit-import.wav")
        AudioDecoder(app).decode(Uri.fromFile(fixture),out)
        val original=PcmFile(fixture); val decoded=PcmFile(out)
        assertEquals(original.duration,decoded.duration,.05)
        assertTrue(quality(decoded.read(0,decoded.count)).rms>.01)
    }
    @Test fun e_roomParentUpdatePreservesChildren() = runBlocking {
        val id=uid(); val rec=Recording(id,"Original",fixture.absolutePath)
        app.dao.saveRecording(rec)
        app.dao.saveUtterances(listOf(UtteranceEntity(uid(),id,0.0,1.0,null,"Неизвестный","test",0.0)))
        app.dao.saveWindows(listOf(WindowEntity(uid(),id,0.0,1.0,EmbeddingCodec.encode(floatArrayOf(1f,0f)),1.0)))
        app.dao.saveRecording(rec.copy(title="Updated"))
        assertEquals(1,app.dao.utteranceList(id).size)
        assertEquals(1,app.dao.windows(id).size)
        assertEquals("Updated",app.dao.recording(id)!!.title)
    }
    @Test fun f_profileSaveRenameDelete() = runBlocking {
        val speech=Wav.read(fixture)
        val engine=app.engines
        val encoderId=engine.embeddingId()
        val vector=engine.extractor(null,encoderId).use { it.extract(speech) }
        val drafts=(0..2).map {
            val file=File(app.filesDir,"audit-sample-"+uid()+".wav"); Wav.write(file,speech)
            DraftSample(uid(),file,vector.copyOf(),quality(speech),encoderId)
        }
        val v=vm(); v.drafts.value=drafts
        main { v.saveProfile("Audit person",false) }
        waitFor { runBlocking { app.dao.profileList().any { it.profile.name=="Audit person" } } }
        val person=app.dao.profileList().first { it.profile.name=="Audit person" }
        assertEquals(3,person.samples.size)
        main { v.renameProfile(person.profile,"Audit renamed") }
        waitFor { runBlocking { app.dao.profileList().any { it.profile.id==person.profile.id && it.profile.name=="Audit renamed" } } }
        main { v.deleteProfile(person.profile.id) }
        waitFor { runBlocking { app.dao.profileList().none { it.profile.id==person.profile.id } } }
        waitFor { drafts.all { !it.file.exists() } }
    }
    @Test fun g_editClusterAndExport() = runBlocking {
        val id=uid(); app.dao.saveRecording(Recording(id,"Editing",fixture.absolutePath))
        val row=UtteranceEntity(uid(),id,0.0,1.0,"cluster_1","Говорящий 1","one",0.0)
        val other=row.copy(id=uid(),start=1.0,end=2.0,text="two")
        app.dao.saveUtterances(listOf(row,other))
        val v=vm()
        main { v.edit(row,"edited","Говорящий 2","cluster_2",true) }
        waitFor { runBlocking { app.dao.utteranceList(id).all { it.speakerId=="cluster_2" } } }
        assertEquals("two",app.dao.utteranceList(id)[1].text)
        val edited=app.dao.utteranceList(id)[0]
        main { v.edit(edited,"edited","Неизвестный","__unknown__",false) }
        waitFor { runBlocking { app.dao.utteranceList(id)[0].speakerId==null } }
        for (format in listOf("json","txt","srt")) {
            val file=File(app.cacheDir,"audit-export-"+uid()+"."+format)
            main { v.export(Uri.fromFile(file),format,id) }
            waitFor { file.exists() && file.length()>0 && !v.busy.value }
            if (format=="json") assertEquals(2,org.json.JSONObject(file.readText()).getJSONArray("transcript").length())
            else assertTrue(file.readText().contains("edited"))
        }
    }
    @Test fun h_fullBackgroundPipeline() = runBlocking {
        val id=uid(); app.dao.saveRecording(Recording(id,"Pipeline",fixture.absolutePath))
        ProcessWorker.enqueue(app,id)
        waitFor(240000) { runBlocking { app.dao.recording(id)!!.status in listOf("Готово","Ошибка") } }
        val rec=app.dao.recording(id)!!
        assertEquals("Pipeline error: "+rec.error,"Готово",rec.status)
        assertTrue(JSONArray(rec.rawAsr).length()>0)
        assertTrue(app.dao.windows(id).isNotEmpty())
        assertTrue(app.dao.utteranceList(id).any { it.text.isNotBlank() })
    }
    @Test fun i_cancellation() = runBlocking {
        val id=uid(); app.dao.saveRecording(Recording(id,"Cancel",fixture.absolutePath))
        val v=vm(); main { v.process(id) }; Thread.sleep(100); main { v.cancel(id) }
        waitFor { runBlocking { app.dao.recording(id)!!.status=="Отменено" } }
        waitFor { WorkManager.getInstance(app).getWorkInfosForUniqueWork("process_"+id).get().all { it.state.isFinished } }
    }
    @Test fun j_microphonePauseResume() = runBlocking {
        val device=UiDevice.getInstance(instrumentation)
        device.executeShellCommand("pm grant ru.localvoices android.permission.RECORD_AUDIO")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val mic=Microphone(scope); val file=File(app.cacheDir,"audit-mic.wav")
        try {
            mic.start(file); delay(1200)
            assertTrue(mic.state.value.recording)
            mic.pause(); val before=mic.state.value.seconds; delay(600)
            assertTrue(abs(mic.state.value.seconds-before)<.2)
            mic.pause(); delay(1100); mic.stop()
            assertNull(mic.state.value.error)
            assertTrue(PcmFile(file).duration>1)
            assertFalse(mic.state.value.recording)
        } finally { mic.stop(); scope.cancel() }
    }
}

