package ru.localvoices.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import ru.localvoices.VoicesApp
import ru.localvoices.core.*
import java.util.UUID

class PeopleRepository(private val app: VoicesApp) {
    /** Only compatible acoustic vectors are compared; uncertain cross-dialogue links need confirmation. */
    suspend fun link(recordingId: String, encoder: String, windows: List<VoiceWindow>, turns: List<Turn>, profiles: List<VoiceProfile>): List<DialogueSpeakerEntity> {
        val previous = app.dao.speakerList().filter { it.recordingId == recordingId }.associateBy { it.localId }
        val others = app.dao.speakerList().filter { it.recordingId != recordingId }.map { it.personId }.toSet()
        val people = app.dao.personList().filter { it.encoder == encoder && it.id in others }
        val result = mutableListOf<DialogueSpeakerEntity>()
        for ((localId, intervals) in turns.filter { it.speakerId != null }.groupBy { it.speakerId!! }) {
            val members = windows.filter { w -> w.quality >= .5 && intervals.sumOf { it.span.overlap(w.span) } >= w.span.duration * .6 }
            val centroid = Vectors.centroidOrNull(members.map { it.embedding }) ?: continue
            val known = profiles.find { it.id == localId }
            // Local cluster numbers may be reused for another voice after reprocessing.
            val old = previous[localId]?.takeIf { prior ->
                app.dao.personList().find {it.id==prior.personId}?.let {person->
                    person.encoder==encoder && Vectors.cosine(centroid,EmbeddingCodec.decode(person.centroid))>=app.prefs.config().knownThreshold
                } == true
            }
            val owner = when { known != null -> known.id; old != null -> old.personId; else -> "person_${UUID.randomUUID()}" }
            val existing = app.dao.personList().find { it.id == owner }
            app.dao.savePerson(PersonEntity(owner, existing?.name ?: known?.name, encoder, EmbeddingCodec.encode(centroid), members.size, existing?.created ?: System.currentTimeMillis()))
            val ranked = people.filter { it.id != owner }.map { it to Vectors.cosine(centroid, EmbeddingCodec.decode(it.centroid)) }.sortedByDescending { it.second }
            val best = ranked.firstOrNull()
            val config = app.prefs.config()
            val candidate = if (known == null && old?.decision != "confirmed" && old?.decision != "rejected" && members.size >= 3 &&
                best != null && best.second >= config.knownThreshold && best.second - (ranked.getOrNull(1)?.second ?: -1.0) >= config.knownMargin) best else null
            result += DialogueSpeakerEntity("$recordingId:$localId", recordingId, localId, owner, candidate?.first?.id, candidate?.second ?: 0.0,
                if (known != null) "confirmed" else old?.decision ?: if (candidate != null) "candidate" else "new")
        }
        app.database.withTransaction { app.dao.clearSpeakers(recordingId); result.forEach { app.dao.saveSpeaker(it) } }
        return result
    }
    suspend fun name(personId: String, name: String) {
        require(name.isNotBlank()) { "Введите имя" }
        app.database.withTransaction {
            val person = app.dao.personList().find { it.id == personId } ?: return@withTransaction
            app.dao.savePerson(person.copy(name = name.trim()))
            app.dao.profileList().find {it.profile.id==personId}?.let {app.dao.updateProfile(it.profile.copy(name=name.trim()))}
            for (s in app.dao.speakerList().filter { it.personId == personId })
                app.dao.utteranceList(s.recordingId).filter { it.speakerId == s.localId }.forEach { app.dao.updateUtterance(it.copy(name = name.trim())) }
        }
    }
    suspend fun decide(speaker: DialogueSpeakerEntity, confirm: Boolean) {
        app.database.withTransaction {
            val candidate = app.dao.personList().find { it.id == speaker.candidateId } ?: return@withTransaction
            app.dao.saveSpeaker(speaker.copy(personId = if (confirm) candidate.id else speaker.personId, candidateId = null, decision = if (confirm) "confirmed" else "rejected"))
            if (confirm && candidate.name != null) app.dao.utteranceList(speaker.recordingId).filter { it.speakerId == speaker.localId }.forEach { app.dao.updateUtterance(it.copy(name = candidate.name)) }
        }
    }
}

object Revisions {
    fun encode(rows: List<UtteranceEntity>) = JSONArray().apply { rows.forEach { r -> put(JSONObject().put("id",r.id).put("recordingId",r.recordingId).put("start",r.start).put("end",r.end).put("speaker",r.speakerId ?: JSONObject.NULL).put("name",r.name).put("text",r.text).put("confidence",r.confidence)) } }.toString()
    fun decode(value: String): List<UtteranceEntity> { val a=JSONArray(value); return (0 until a.length()).map { val r=a.getJSONObject(it); UtteranceEntity(r.getString("id"),r.getString("recordingId"),r.getDouble("start"),r.getDouble("end"),if(r.isNull("speaker")) null else r.getString("speaker"),r.getString("name"),r.getString("text"),r.getDouble("confidence")) } }
    suspend fun save(app: VoicesApp, id: String, reason: String) {
        val rows=app.dao.utteranceList(id)
        if(rows.isNotEmpty()) app.dao.saveRevision(RevisionEntity(UUID.randomUUID().toString(),id,System.currentTimeMillis(),reason,encode(rows)))
    }
}
