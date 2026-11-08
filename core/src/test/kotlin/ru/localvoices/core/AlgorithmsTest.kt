package ru.localvoices.core

import kotlin.test.*
import kotlin.math.*

class AlgorithmsTest {
    private fun vector(angle: Double) = floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat())
    private fun profile(id: String, angle: Double) = VoiceProfile(id, id, "test", listOf(vector(angle - .01), vector(angle), vector(angle + .01)))
    @Test fun normalizationRejectsInvalidVectors() {
        assertFailsWith<IllegalArgumentException> { Vectors.normalized(floatArrayOf(0f, 0f)) }
        assertFailsWith<IllegalArgumentException> { Vectors.normalized(floatArrayOf(Float.NaN)) }
        assertEquals(1.0, Vectors.cosine(floatArrayOf(3f, 4f), floatArrayOf(6f, 8f)), 1e-6)
    }
    @Test fun openSetRejectsStrangerEvenWithOneProfile() {
        val matcher = KnownSpeakerMatcher(DiarizationConfig())
        assertNull(matcher.match(vector(PI / 2), listOf(profile("A", 0.0))))
        assertEquals("A", matcher.match(vector(.02), listOf(profile("A", 0.0)))?.id)
    }
    @Test fun nearTieDoesNotIdentify() {
        assertNull(KnownSpeakerMatcher(DiarizationConfig()).match(vector(.03), listOf(profile("A", 0.0), profile("B", .06))))
    }
    @Test fun robustEnrollmentDropsBadSample() {
        val refs = Vectors.robustReferences(listOf(vector(0.0), vector(.02), vector(-.02), vector(PI)))
        assertEquals(3, refs.size)
    }
    @Test fun oneLongRecordingCanSupplyOneReference() {
        val reference = Vectors.robustReferences(listOf(vector(0.0)))
        assertEquals(1, reference.size)
        val person = VoiceProfile("one", "One", "test", reference)
        val matcher = KnownSpeakerMatcher(DiarizationConfig())
        assertEquals("one", matcher.match(vector(.01), listOf(person))?.id)
        assertNull(matcher.match(vector(PI / 2), listOf(person)))
    }
    @Test fun hdbscanFindsTwoDensityClustersAndNoise() {
        val x = (0..7).map { vector(it * .003) } + (0..7).map { vector(1.5 + it * .003) } + listOf(vector(3.0))
        val labels = Hdbscan(3, 2).fit(x)
        assertTrue(labels.take(8).distinct().size == 1 && labels[0] >= 0)
        assertTrue(labels.drop(8).take(8).distinct().size == 1 && labels[8] >= 0)
        assertNotEquals(labels[0], labels[8]); assertEquals(-1, labels.last())
    }
    @Test fun hdbscanDoesNotForceTinyInputIntoClusters() {
        assertContentEquals(intArrayOf(-1, -1), Hdbscan(3, 2).fit(listOf(vector(0.0), vector(1.0))))
    }
    @Test fun identicalVectorsRemainNumericallyStable() {
        val turns = Diarizer(DiarizationConfig()).run((0..3).map { VoiceWindow(Span(it * 3.0, it * 3.0 + 2), vector(0.0)) }, emptyList())
        assertEquals(1, turns.map { it.speakerId }.distinct().size)
        assertTrue(turns.first().speakerId != null)
    }
    @Test fun smallHeterogeneousSetRemainsUnknown() {
        val turns = Diarizer(DiarizationConfig()).run(listOf(VoiceWindow(Span(0.0, 2.0), vector(0.0)), VoiceWindow(Span(3.0, 5.0), vector(1.5))), emptyList())
        assertTrue(turns.all { it.speakerId == null })
    }
    @Test fun singleWindowIsNotInventedSpeaker() {
        val turns = Diarizer(DiarizationConfig()).run(listOf(VoiceWindow(Span(0.0, 2.0), vector(0.0))), emptyList())
        assertNull(turns.single().speakerId)
    }
    @Test fun knownAndUnknownVoicesWorkTogether() {
        val windows = listOf(VoiceWindow(Span(0.0, 2.0), vector(0.0))) + (0..3).map { VoiceWindow(Span(3.0 + it * 3, 5.0 + it * 3), vector(1.5 + it * .001)) }
        val turns = Diarizer(DiarizationConfig()).run(windows, listOf(profile("Alex", 0.0)))
        assertEquals("Alex", turns.first().name)
        assertTrue(turns.drop(1).all { it.name == "Говорящий 1" })
    }
    @Test fun mergerPreservesEveryCharacterWithSpeakerChange() {
        val asr = listOf(AsrUnit(Span(0.0, 1.0), " Привет"), AsrUnit(Span(1.0, 2.0), ", мир!"), AsrUnit(Span(2.0, 3.0), " Пока."))
        val turns = listOf(Turn(Span(0.0, 2.0), "A", "Александр", .9), Turn(Span(2.0, 3.0), "B", "Мария", .9))
        val result = TranscriptMerger.merge(asr, turns)
        assertEquals(asr.joinToString("") { it.text }, result.joinToString("") { it.text })
        assertEquals(listOf("Александр", "Мария"), result.map { it.name })
        assertEquals(Span(0.0, 2.0), result[0].span)
    }
    @Test fun mergerDoesNotGuessSpeakerForAmbiguousSentence() {
        val result = TranscriptMerger.merge(listOf(AsrUnit(Span(0.0, 4.0), "Целое предложение")), listOf(Turn(Span(0.0, 2.0), "A", "A", .9), Turn(Span(2.0, 4.0), "B", "B", .9)))
        assertNull(result.single().speakerId)
        assertEquals("Целое предложение", result.single().text)
    }

    @Test fun coalescesSameKnownSpeakerAcrossSegmentationGap() {
        val pieces = listOf(
            Utterance(Span(0.0, 1.0), "profile-A", "Александр Кирилин", "Первая фраза.", .9),
            Utterance(Span(1.8, 2.8), "profile-A", "Александр Кирилин", "Вторая фраза.", .9),
            Utterance(Span(3.9, 4.8), "profile-A", "Александр Кирилин", "Третья.", .9),
        )
        val result = TranscriptMerger.coalesce(pieces)
        assertEquals(1, result.size)
        assertEquals("Первая фраза. Вторая фраза. Третья.", result.single().text)
        assertEquals(Span(0.0, 4.8), result.single().span)
    }

    @Test fun doesNotCoalesceDifferentUnknownClustersWithSameDisplayName() {
        val pieces = listOf(
            Utterance(Span(0.0, 1.0), "cluster_1", "Говорящий 1", "Один", .2),
            Utterance(Span(1.1, 2.0), "cluster_2", "Говорящий 1", "Другой", .2),
        )
        assertEquals(2, TranscriptMerger.coalesce(pieces).size)
    }

    @Test fun speakerWindowPlannerSkipsLongSilence() {
        val planned = SpeechWindowPlanner.plan(
            listOf(Span(0.0, 2.2), Span(4.0, 6.4)),
            window = 2.0,
            step = 1.0,
        )
        assertTrue(planned.all { it.start >= 0.0 && it.end <= 6.4 })
        assertTrue(planned.none { it.start < 4.0 && it.end > 2.2 })
        assertTrue(planned.none { it.start >= 2.2 && it.end <= 4.0 })
    }

    @Test fun speakerWindowPlannerIntersectsSegmentationWithVad() {
        val gated = SpeechWindowPlanner.intersect(
            listOf(Span(0.0, 5.0)),
            listOf(Span(0.5, 1.5), Span(3.0, 4.5)),
        )
        assertEquals(listOf(Span(.5, 1.5), Span(3.0, 4.5)), gated)
    }

    @Test fun gigaAmPiecesBecomeOneWordBeforeSpeakerAttribution() {
        val pieces = listOf(
            AsrUnit(Span(0.00, .04), "▁по", tokenId = 31),
            AsrUnit(Span(.04, .08), "ж", tokenId = 48),
            AsrUnit(Span(.08, .12), "а", tokenId = 4),
            AsrUnit(Span(.12, .16), "л", tokenId = 14),
            AsrUnit(Span(.16, .20), "у", tokenId = 12),
            AsrUnit(Span(.20, .24), "й", tokenId = 13),
            AsrUnit(Span(.24, .28), "ста", tokenId = 113),
        )
        val words = SentencePieceReconstructor.gigaAm(pieces)
        assertEquals(1, words.size)
        assertEquals("пожалуйста", words.single().text)
        assertEquals(Span(0.0, .28), words.single().span)
        assertEquals(listOf(31, 48, 4, 14, 12, 13, 113), words.single().tokenIds)
    }

    @Test fun standaloneBoundaryIsNotEmittedAsTextButStartsNextWord() {
        val pieces = listOf(
            AsrUnit(Span(0.00, .04), "▁", tokenId = 1),
            AsrUnit(Span(.04, .08), "З", tokenId = 179),
            AsrUnit(Span(.08, .12), "д", tokenId = 17),
            AsrUnit(Span(.12, .16), "ра", tokenId = 30),
            AsrUnit(Span(.16, .20), "в", tokenId = 16),
            AsrUnit(Span(.20, .24), "ствуйте", tokenId = 55),
        )
        val words = SentencePieceReconstructor.gigaAm(pieces)
        assertEquals(listOf("Здравствуйте"), words.map { it.text })
        assertEquals(0.0, words.single().span.start, 1e-9)
    }

    @Test fun repeatedWordsRemainRepeated() {
        val pieces = listOf(
            AsrUnit(Span(0.0, .1), "▁да", tokenId = 31),
            AsrUnit(Span(.1, .2), "▁да", tokenId = 31),
            AsrUnit(Span(.2, .3), ".", tokenId = 2),
        )
        assertEquals("да да.", SentencePieceReconstructor.gigaAm(pieces).joinToString("") { it.text })
    }
    @Test fun overlapTieStaysUnknown() {
        val result = Diarizer(DiarizationConfig()).run(listOf(VoiceWindow(Span(0.0, 2.0), vector(0.0)), VoiceWindow(Span(1.0, 3.0), vector(1.5))), listOf(profile("A", 0.0), profile("B", 1.5)))
        assertNull(result.find { it.span.start == 1.0 }?.speakerId)
    }
}
