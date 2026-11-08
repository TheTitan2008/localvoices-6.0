package ru.localvoices.audit

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.*
import ru.localvoices.*
import ru.localvoices.audio.*
import ru.localvoices.core.*
import ru.localvoices.data.*
import ru.localvoices.ml.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V2DeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as VoicesApp
    private val input get()=File(app.filesDir,"audit-input")
    private fun uid()=UUID.randomUUID().toString()
    private fun main(f:()->Unit)=ins.runOnMainSync(f)
    private fun waitFor(t:Long=30000,f:()->Boolean) {val end=System.currentTimeMillis()+t;while(System.currentTimeMillis()<end){if(f())return;Thread.sleep(100)};assertTrue("Timeout",f())}
    private fun vm():VoicesViewModel {lateinit var result:VoicesViewModel;main {result=VoicesViewModel(app)};return result}
    @Before fun models() {
        androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
        runBlocking {withTimeout(30000){app.mlLock.withLock {}}}
        for(slot in listOf("ggml-base.bin","silero_vad.onnx","speaker.onnx","segmentation.onnx","punctuation.gguf"))if(!app.models.file(slot).exists())File(input,slot).copyTo(app.models.file(slot))
        app.prefs.encoder="speaker";app.prefs.whisperModel="ggml-base.bin";app.prefs.threads=1
    }
    @Test fun realLocalLlmAndWordPreservation()=runBlocking {
        val text="привет меня зовут александр сегодня мы проверяем запись разговора"
        val result=Punctuator(app.models,1).run(listOf(Utterance(Span(0.0,4.0),null,"Неизвестный",text,0.0))){}
        assertTrue(PunctuationGuard.accepts(text,result.rows.single().text))
        assertEquals(1,result.accepted+result.rejected)
        println("LOCAL_LLM: accepted=${result.accepted}; rejected=${result.rejected}; text=${result.rows.single().text}")
    }
    @Test fun learnedAcousticBoundaries()=runBlocking {
        val source=PcmFile(File(input,"jfk.wav"))
        val result=AcousticSegmenter(app.models,2).run(source)
        assertTrue(result.speech.isNotEmpty())
        assertTrue(result.speech.all {it.start>=0 && it.end<=source.duration+.001})
        println("SEGMENTATION: ${result.speech}")
    }
    @Test fun crossDialogueSuggestionConfirmationNamingAndUndo()=runBlocking {
        val a=uid();val b=uid();val encoder=uid()
        for(id in listOf(a,b))app.dao.saveRecording(Recording(id,"Identity test",File(input,"jfk.wav").absolutePath))
        val windows=(0..2).map{VoiceWindow(Span(it*2.0,it*2.0+2),floatArrayOf(1f,0f))}
        val turns=listOf(Turn(Span(0.0,6.0),"cluster_1","Говорящий 1",1.0))
        val repo=PeopleRepository(app)
        val first=repo.link(a,encoder,windows,turns,emptyList()).single()
        val second=repo.link(b,encoder,windows,turns,emptyList()).single()
        assertNotEquals(first.personId,second.personId);assertEquals(first.personId,second.candidateId)
        app.dao.saveUtterances(listOf(UtteranceEntity(uid(),b,0.0,2.0,"cluster_1","Говорящий 1","исходный текст",1.0)))
        repo.decide(second,true);repo.name(first.personId,"Александр")
        assertEquals("Александр",app.dao.utteranceList(b).single().name)
        assertEquals(first.personId,app.dao.speakerList().single {it.recordingId==b}.personId)
        val v=vm();val row=app.dao.utteranceList(b).single()
        main{v.edit(row,"исправлено",row.name,row.speakerId,false)}
        waitFor{runBlocking{app.dao.utteranceList(b).single().text=="исправлено"}}
        main{v.undo(b)}
        waitFor{runBlocking{app.dao.utteranceList(b).single().text=="исходный текст"}}
    }
    @Test fun threeMp3FilesRemainIndependentAndZipIsStrict()=runBlocking {
        val before=app.dao.recordingList().map{it.id}.toSet();val v=vm()
        main{v.importAudios((1..3).map{Uri.fromFile(File(input,"dialog-$it.mp3"))})}
        waitFor(60000){runBlocking{app.dao.recordingList().count{it.id !in before}==3}}
        val imported=app.dao.recordingList().filter{it.id !in before}
        assertEquals(3,imported.map{it.path}.distinct().size)
        assertTrue(imported.all{it.duration>10 && File(it.path).exists()})
        val zip=File(app.cacheDir,"all-dialogues.zip")
        main{v.exportAll(Uri.fromFile(zip))}
        waitFor{v.message.value=="Все диалоги экспортированы в ZIP"}
        java.util.zip.ZipFile(zip).use { archive ->
            for(rec in imported){val json=JSONObject(archive.getInputStream(archive.getEntry("${rec.id}.json")).bufferedReader().readText());assertEquals(setOf("transcript"),json.keys().asSequence().toSet())}
            assertTrue(JSONObject(archive.getInputStream(archive.getEntry("project.json")).bufferedReader().readText()).getJSONArray("dialogues").length()>=3)
        }
    }
    @Test fun foregroundRecordingSurvivesBackground()=runBlocking {
        val device=UiDevice.getInstance(ins)
        device.executeShellCommand("pm grant ru.localvoices android.permission.RECORD_AUDIO")
        app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1000);main{CaptureService.start(app)}
        waitFor{app.capture.microphone.state.value.recording}
        val id=app.capture.recordId.value!!
        try {
            device.pressHome()
            // Emulator audio clock can lag wall time; wait for recorded PCM.
            waitFor(20000){app.capture.microphone.state.value.seconds>1}
            assertTrue(app.capture.microphone.state.value.recording)
        } finally {main{CaptureService.stop(app)};waitFor{!app.capture.conversation.value}}
        assertTrue(File(app.dao.recording(id)!!.path).length()>32044)
        ru.localvoices.processing.ProcessWorker.cancel(app,id)
        Unit
    }
}
