package ru.localvoices

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.localvoices.audio.CaptureController
import kotlinx.coroutines.*
import ru.localvoices.audio.Wav
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.sync.Mutex
import ru.localvoices.data.*
import ru.localvoices.ml.*

class VoicesApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val modelPreparation by lazy { appScope.async {
        try { models.installBundled() }
        catch (e: Exception) { models.preparation.value = "Ошибка подготовки: ${e.message}"; throw e }
    } }
    suspend fun awaitModels() = modelPreparation.await()
    override fun onCreate() {
        super.onCreate()
        if (prefs.whisperModel != "ggml-base.bin") prefs.whisperModel = "ggml-base.bin"
        modelPreparation
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            delay(1500)
            if(!capture.conversation.value) for(row in dao.recordingList().filter {it.status=="Запись"}) {
                val file=File(row.path)
                if(file.length()>=44)RandomAccessFile(file,"rw").use{Wav.header(it,(file.length()-44).toInt())}
                dao.status(row.id,"Запись прервана: можно распознать",0)
            }
        }
    }
    val database by lazy { Room.databaseBuilder(this, VoicesDatabase::class.java, "voices.db").addMigrations(object : Migration(1,2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE recordings ADD COLUMN punctuationStatus TEXT NOT NULL DEFAULT 'none'")
            db.execSQL("ALTER TABLE recordings ADD COLUMN processingMs INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE recordings ADD COLUMN acousticEvents TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("CREATE TABLE IF NOT EXISTS people (id TEXT NOT NULL PRIMARY KEY, name TEXT, encoder TEXT NOT NULL, centroid BLOB NOT NULL, `references` INTEGER NOT NULL, created INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS dialogue_speakers (id TEXT NOT NULL PRIMARY KEY, recordingId TEXT NOT NULL, localId TEXT NOT NULL, personId TEXT NOT NULL, candidateId TEXT, similarity REAL NOT NULL, decision TEXT NOT NULL, FOREIGN KEY(recordingId) REFERENCES recordings(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_dialogue_speakers_recordingId ON dialogue_speakers(recordingId)")
            db.execSQL("CREATE INDEX index_dialogue_speakers_personId ON dialogue_speakers(personId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS revisions (id TEXT NOT NULL PRIMARY KEY, recordingId TEXT NOT NULL, created INTEGER NOT NULL, reason TEXT NOT NULL, data TEXT NOT NULL)")
            db.execSQL("CREATE INDEX index_revisions_recordingId ON revisions(recordingId)")
        }
    }, object : Migration(2,3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE recordings ADD COLUMN processingStats TEXT NOT NULL DEFAULT '{}'")
        }
    }).build() }
    val dao get() = database.dao()
    val prefs by lazy { Preferences(this) }
    val models by lazy { ModelStore(this) }
    val engines get() = Engines(models, prefs)
    val mlLock = Mutex()
    val capture by lazy { CaptureController(this) }
}
