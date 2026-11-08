package ru.localvoices.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import ru.localvoices.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey val id: String,
    val title: String,
    val path: String,
    val created: Long = System.currentTimeMillis(),
    val status: String = "Готова к обработке",
    val stage: Int = 0,
    val error: String? = null,
    val duration: Double = 0.0,
    val rawAsr: String = "[]",
    val rawTurns: String = "[]",
    val encoder: String = "",
    @ColumnInfo(defaultValue = "'none'") val punctuationStatus: String = "none",
    @ColumnInfo(defaultValue = "0") val processingMs: Long = 0,
    @ColumnInfo(defaultValue = "'[]'") val acousticEvents: String = "[]",
    @ColumnInfo(defaultValue = "'{}'") val processingStats: String = "{}",
)
@Entity(tableName = "people")
data class PersonEntity(@PrimaryKey val id: String, val name: String?, val encoder: String, val centroid: ByteArray, val references: Int, val created: Long = System.currentTimeMillis())
@Entity(tableName = "dialogue_speakers", foreignKeys = [ForeignKey(entity = Recording::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)], indices = [Index("recordingId"), Index("personId")])
data class DialogueSpeakerEntity(@PrimaryKey val id: String, val recordingId: String, val localId: String, val personId: String,
    val candidateId: String? = null, val similarity: Double = 0.0, val decision: String = "new")
@Entity(tableName = "revisions", indices = [Index("recordingId")])
data class RevisionEntity(@PrimaryKey val id: String, val recordingId: String, val created: Long, val reason: String, val data: String)
@Entity(tableName = "profiles")
data class ProfileEntity(@PrimaryKey val id: String, val name: String, val encoder: String, val dispersion: Double, val created: Long = System.currentTimeMillis())
@Entity(tableName = "samples", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class SampleEntity(@PrimaryKey val id: String, val profileId: String, val path: String, val embedding: ByteArray, val duration: Double, val rms: Double, val clipping: Double)
@Entity(tableName = "utterances", foreignKeys = [ForeignKey(entity = Recording::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)], indices = [Index("recordingId")])
data class UtteranceEntity(@PrimaryKey val id: String, val recordingId: String, val start: Double, val end: Double, val speakerId: String?, val name: String, val text: String, val confidence: Double)
@Entity(tableName = "windows", foreignKeys = [ForeignKey(entity = Recording::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)], indices = [Index("recordingId")])
data class WindowEntity(@PrimaryKey val id: String, val recordingId: String, val start: Double, val end: Double, val embedding: ByteArray, val quality: Double)
data class ProfileWithSamples(@Embedded val profile: ProfileEntity, @Relation(parentColumn = "id", entityColumn = "profileId") val samples: List<SampleEntity>) {
    fun domain() = VoiceProfile(profile.id, profile.name, profile.encoder, samples.map { EmbeddingCodec.decode(it.embedding) })
}
@Dao
interface VoiceDao {
    @Query("SELECT * FROM recordings ORDER BY created DESC") fun recordings(): Flow<List<Recording>>
    @Query("SELECT * FROM recordings WHERE id=:id") suspend fun recording(id: String): Recording?
    @Query("SELECT * FROM recordings") suspend fun recordingList(): List<Recording>
    // REPLACE would DELETE the parent and cascade away its utterances/windows.
    @Upsert suspend fun saveRecording(recording: Recording)
    @Query("UPDATE recordings SET status=:status,stage=:stage,error=:error WHERE id=:id") suspend fun status(id: String, status: String, stage: Int, error: String? = null)
    @Query("SELECT * FROM utterances WHERE recordingId=:id ORDER BY start") fun utterances(id: String): Flow<List<UtteranceEntity>>
    @Query("SELECT * FROM utterances WHERE recordingId=:id ORDER BY start") suspend fun utteranceList(id: String): List<UtteranceEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveUtterances(rows: List<UtteranceEntity>)
    @Update suspend fun updateUtterance(row: UtteranceEntity)
    @Query("DELETE FROM utterances WHERE recordingId=:id") suspend fun clearUtterances(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveWindows(rows: List<WindowEntity>)
    @Query("DELETE FROM windows WHERE recordingId=:id") suspend fun clearWindows(id: String)
    @Query("SELECT * FROM windows WHERE recordingId=:id ORDER BY start") suspend fun windows(id: String): List<WindowEntity>
    @Transaction @Query("SELECT * FROM profiles ORDER BY name") fun profiles(): Flow<List<ProfileWithSamples>>
    @Transaction @Query("SELECT * FROM profiles ORDER BY name") suspend fun profileList(): List<ProfileWithSamples>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertProfile(profile: ProfileEntity)
    @Update suspend fun updateProfile(profile: ProfileEntity)
    @Insert suspend fun insertSamples(samples: List<SampleEntity>)
    @Query("DELETE FROM profiles WHERE id=:id") suspend fun deleteProfile(id: String)
    @Query("DELETE FROM samples WHERE profileId=:id") suspend fun clearSamples(id: String)
    @Query("UPDATE utterances SET speakerId=NULL,name='Удалённый голос',confidence=0 WHERE speakerId=:id") suspend fun anonymizeProfile(id: String)
    @Query("SELECT * FROM people ORDER BY created") fun people(): Flow<List<PersonEntity>>
    @Query("SELECT * FROM people ORDER BY created") suspend fun personList(): List<PersonEntity>
    @Upsert suspend fun savePerson(row: PersonEntity)
    @Query("DELETE FROM people WHERE id=:id") suspend fun deletePerson(id: String)
    @Query("DELETE FROM dialogue_speakers WHERE personId=:id") suspend fun deletePersonLinks(id: String)
    @Query("UPDATE dialogue_speakers SET candidateId=NULL,decision='new' WHERE candidateId=:id") suspend fun clearCandidate(id: String)
    @Query("SELECT * FROM dialogue_speakers WHERE recordingId=:id ORDER BY localId") fun speakers(id: String): Flow<List<DialogueSpeakerEntity>>
    @Query("SELECT * FROM dialogue_speakers") suspend fun speakerList(): List<DialogueSpeakerEntity>
    @Query("SELECT * FROM dialogue_speakers") fun allSpeakers(): Flow<List<DialogueSpeakerEntity>>
    @Upsert suspend fun saveSpeaker(row: DialogueSpeakerEntity)
    @Query("DELETE FROM dialogue_speakers WHERE recordingId=:id") suspend fun clearSpeakers(id: String)
    @Insert suspend fun saveRevision(row: RevisionEntity)
    @Query("SELECT * FROM revisions WHERE recordingId=:id AND reason NOT LIKE 'restored:%' ORDER BY created DESC") suspend fun revisions(id: String): List<RevisionEntity>
    @Query("SELECT * FROM revisions WHERE recordingId=:id") suspend fun allRevisions(id: String): List<RevisionEntity>
    @Query("UPDATE revisions SET reason='restored:' || reason WHERE id=:id") suspend fun restoredRevision(id: String)
    @Query("DELETE FROM recordings WHERE id=:id") suspend fun deleteRecording(id: String)
    @Query("DELETE FROM revisions WHERE recordingId=:id") suspend fun deleteRevisions(id: String)
}
@Database(entities = [Recording::class, ProfileEntity::class, SampleEntity::class, UtteranceEntity::class, WindowEntity::class, PersonEntity::class, DialogueSpeakerEntity::class, RevisionEntity::class], version = 3, exportSchema = false)
abstract class VoicesDatabase : RoomDatabase() { abstract fun dao(): VoiceDao }

object ProcessingStatsCodec {
    fun encode(stageMs: Map<String, Long>, counters: Map<String, Long>): String = JSONObject().apply {
        put("stage_ms", JSONObject().apply { stageMs.forEach { (key, value) -> put(key, value) } })
        put("counters", JSONObject().apply { counters.forEach { (key, value) -> put(key, value) } })
    }.toString()
    fun decode(value: String): JSONObject = runCatching { JSONObject(value) }.getOrDefault(JSONObject())
    fun withSummary(value: String, totalMs: Long, audioMs: Long): String = decode(value).apply {
        put("total_ms", totalMs)
        put("audio_duration_ms", audioMs)
        put("realtime_factor", if (audioMs > 0) totalMs.toDouble() / audioMs else JSONObject.NULL)
    }.toString()
}

object EmbeddingCodec {
    fun encode(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.isNotEmpty() && bytes.size % 4 == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}
object RawCodec {
    fun asr(rows: List<AsrUnit>) = JSONArray().apply {
        rows.forEach { row ->
            put(JSONArray().apply {
                put(row.span.start); put(row.span.end); put(row.text)
                put(row.tokenId ?: JSONObject.NULL)
                put(row.probability ?: JSONObject.NULL)
                put(row.segmentIndex ?: JSONObject.NULL)
                put(row.wordIndex ?: JSONObject.NULL)
                put(JSONArray(row.tokenIds))
            })
        }
    }.toString()
    fun turns(rows: List<Turn>) = JSONArray().apply { rows.forEach { put(JSONArray(listOf(it.span.start, it.span.end, it.speakerId, it.name, it.confidence))) } }.toString()
    fun parseAsr(json: String): List<AsrUnit> {
        val a = JSONArray(json)
        return (0 until a.length()).map { index ->
            val r = a.getJSONArray(index)
            val ids = if (r.length() > 7 && !r.isNull(7)) {
                val values = r.getJSONArray(7)
                (0 until values.length()).map { values.getInt(it) }
            } else emptyList()
            AsrUnit(
                span = Span(r.getDouble(0), r.getDouble(1)),
                text = r.getString(2),
                tokenId = if (r.length() > 3 && !r.isNull(3)) r.getInt(3) else null,
                probability = if (r.length() > 4 && !r.isNull(4)) r.getDouble(4) else null,
                segmentIndex = if (r.length() > 5 && !r.isNull(5)) r.getInt(5) else null,
                wordIndex = if (r.length() > 6 && !r.isNull(6)) r.getInt(6) else null,
                tokenIds = ids,
            )
        }
    }
}
