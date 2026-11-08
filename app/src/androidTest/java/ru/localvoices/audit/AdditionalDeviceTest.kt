package ru.localvoices.audit
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.localvoices.*
import ru.localvoices.audio.*
import ru.localvoices.core.*
import ru.localvoices.data.*
import ru.localvoices.ml.*
import java.io.File
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class AdditionalDeviceTest {
    @Before fun setup() {
        androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking {withTimeout(30000){app.mlLock.withLock {}}}
        app.prefs.threads=1;app.prefs.whisperModel="ggml-base.bin";app.prefs.encoder="speaker"
    }
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as VoicesApp
    private val input get()=File(app.filesDir,"audit-input")
    private fun main(f:()->Unit)=ins.runOnMainSync(f)
    private fun waitFor(t:Long=30000,f:()->Boolean) { val end=System.currentTimeMillis()+t; while(System.currentTimeMillis()<end){if(f())return;Thread.sleep(100)};assertTrue("Timeout",f()) }
    @Test fun russianAudioAndUnicode()=runBlocking {
        val output=File(app.cacheDir,"russian-decoded.wav")
        AudioDecoder(app).decode(Uri.fromFile(File(input,"russian-synthetic.wav")),output)
        app.engines.whisper().use {
            val text=it.transcribe(Wav.read(output),"ru").joinToString(""){it.text}
            println("RUSSIAN_TRANSCRIPT: "+text)
            assertTrue(text.lowercase().contains("привет"))
            assertTrue(text.lowercase().contains("александр"))
            assertFalse(text.contains('\uFFFD'))
        }
    }
    @Test fun modelsValidAndInvalidImport()=runBlocking {
        val before=app.models.fingerprint("silero_vad.onnx")
        val bad=File(app.cacheDir,"bad-model.onnx").apply {writeBytes(ByteArray(2048))}
        var rejected=false
        try {app.models.import(app,"silero_vad.onnx",Uri.fromFile(bad))}catch(e:IllegalArgumentException){rejected=true}
        assertTrue(rejected);assertEquals(before,app.models.fingerprint("silero_vad.onnx"))
        app.models.import(app,"silero_vad.onnx",Uri.fromFile(File(input,"silero_vad.onnx")))
        assertEquals(before,app.models.fingerprint("silero_vad.onnx"))
    }
    @Test fun playerContinuesWhenTranscriptScrolled()=runBlocking {
        val original=Wav.read(File(input,"jfk.wav"))
        val long=FloatArray(original.size*20){original[it%original.size]}
        val audio=File(app.filesDir,"player-audit.wav");Wav.write(audio,long)
        val id=UUID.randomUUID().toString()
        app.dao.saveRecording(Recording(id,"Player audit",audio.absolutePath,status="Готово",duration=long.size/16000.0))
        app.dao.saveUtterances((0..49).map{ i->UtteranceEntity(UUID.randomUUID().toString(),id,i*.1,i*.1+.1,"cluster_1","Говорящий 1","Scroll row "+i,0.0)})
        val d=UiDevice.getInstance(ins)
        app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(d.wait(Until.hasObject(By.text("Player audit")),15000))
        d.findObject(By.text("Player audit")).click()
        assertTrue(d.wait(Until.hasObject(By.desc("Воспроизведение")),10000))
        d.findObject(By.desc("Воспроизведение")).click()
        val manager=app.getSystemService(AudioManager::class.java)
        waitFor(10000){manager.isMusicActive}
        d.takeScreenshot(File(input,"player-before.png"))
        UiScrollable(UiSelector().scrollable(true)).scrollToEnd(20)
        Thread.sleep(1500)
        d.takeScreenshot(File(input,"player-after.png"))
        assertTrue("Playback stopped when LazyColumn disposed the player item",manager.isMusicActive)
    }
}

