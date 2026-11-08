package ru.localvoices.audit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.localvoices.VoicesApp
import ru.localvoices.VoicesViewModel
import ru.localvoices.audio.PcmFile
import ru.localvoices.audio.Wav
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SingleLongSampleTest {
    @Test fun oneRecordingOverFortySecondsIsSavedWithoutTruncation() {
        runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as VoicesApp
        withTimeout(120_000) { app.awaitModels() }
        app.prefs.encoder = "speaker"
        val source = File(app.cacheDir, "long-sample-source.wav")
        instrumentation.context.assets.open("long-sample-source.wav").use { input -> source.outputStream().use { output -> input.copyTo(output) } }
        val speech = Wav.read(source)
        val recording = File(app.cacheDir, "long-sample-test.wav")
        Wav.write(recording, FloatArray(speech.size * 2) { speech[it % speech.size] })
        assertTrue(PcmFile(recording).duration > 40)

        val vm = VoicesViewModel(app)
        val draft = withTimeout(120_000) { vm.prepareSample(recording) }
        assertEquals(recording.length(), draft.file.length())
        assertEquals(PcmFile(recording).duration, PcmFile(draft.file).duration, .001)
        vm.drafts.value = listOf(draft)
        val name = "Long sample ${UUID.randomUUID()}"
        instrumentation.runOnMainSync { vm.saveProfile(name, false) }
        var profile: ru.localvoices.data.ProfileWithSamples? = null
        withTimeout(30_000) {
            while (profile == null) {
                profile = app.dao.profileList().firstOrNull { it.profile.name == name }
                kotlinx.coroutines.delay(100)
            }
        }
        assertEquals(1, profile!!.samples.size)
        assertEquals(recording.length(), File(profile!!.samples.single().path).length())
        recording.delete(); source.delete()
        }
    }
}
