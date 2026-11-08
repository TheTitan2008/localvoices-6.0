package ru.localvoices.core
import kotlin.test.*
import kotlin.math.*
class AuditEdgeCasesTest {
    @Test fun mergedAcousticConfidenceIsDurationWeighted() {
        val windows=listOf(VoiceWindow(Span(0.0,1.0),floatArrayOf(1f,0f)),VoiceWindow(Span(1.0,2.0),floatArrayOf(1f,0f)))
        val turns=Diarizer(DiarizationConfig()).run(windows,emptyList(),listOf(Identity("A","Alex",.6,1.0),Identity("A","Alex",.99,1.0)))
        assertEquals(.795,turns.single().confidence,1e-9)
    }
    @Test fun balancedUnknownPopulationDoesNotCrash() {
        val vectors=(0 until 32).flatMap { axis -> listOf(FloatArray(32) { if(it==axis) 1f else 0f },FloatArray(32) { if(it==axis) -1f else 0f }) }
        val windows=vectors.mapIndexed { i,v -> VoiceWindow(Span(i*3.0,i*3.0+2),v) }
        val turns=Diarizer(DiarizationConfig()).run(windows,emptyList())
        assertEquals(windows.size,turns.size)
    }
    @Test fun confidenceUsesOverlappingTurnRatherThanFirstOccurrence() {
        val turns=listOf(Turn(Span(0.0,1.0),"A","Alex",.6),Turn(Span(2.0,3.0),"A","Alex",.99))
        val result=TranscriptMerger.merge(listOf(AsrUnit(Span(2.0,3.0),"second turn")),turns)
        assertEquals(.99,result.single().confidence,1e-9)
    }
    @Test fun characterizeClusterRecoveryOfKnownIdentity() {
        fun v(a: Double)=floatArrayOf(cos(a).toFloat(),sin(a).toFloat())
        val profile=VoiceProfile("A","Alex","test",listOf(v(0.0),v(0.0),v(0.0)))
        val cfg=DiarizationConfig(knownThreshold=.9998)
        val windows=listOf(-.03,.03,-.03,.03).mapIndexed { i,a -> VoiceWindow(Span(i*3.0,i*3.0+2),v(a)) }
        assertTrue(windows.all { KnownSpeakerMatcher(cfg).match(it.embedding,listOf(profile))==null })
        assertNotNull(KnownSpeakerMatcher(cfg).match(Vectors.centroid(windows.map {it.embedding}),listOf(profile)))
        val result=Diarizer(cfg).run(windows,listOf(profile))
        assertTrue(result.all { it.speakerId == "A" })
    }
}

