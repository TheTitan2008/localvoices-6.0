package ru.localvoices.core
import kotlin.test.*

class ExportAndPunctuationTest {
    @Test fun competitionContractUsesMillisecondsAndStringUnknownIds() {
        val text=TranscriptExport.json(listOf(Utterance(Span(1.25,4.38),null,"Неизвестный","Добрый день!",0.0)))
        assertTrue(text.startsWith("{\"transcript\":["))
        assertTrue(text.contains("\"start_ms\":1250"));assertTrue(text.contains("\"end_ms\":4380"))
        assertTrue(text.contains("\"speaker_id\":\"unresolved_1\""));assertFalse(text.contains("null"))
    }
    @Test fun jsonEscapesControlCharactersAndQuotes() {
        val text=TranscriptExport.json(listOf(Utterance(Span(0.0,1.0),"a","A","Цитата: \"да\"\nСледующая строка",1.0)))
        assertTrue(text.contains("\\\"да\\\"\\n"))
    }
    @Test fun punctuationMayChangeCaseButNotContent() {
        assertTrue(PunctuationGuard.accepts("привет как дела","Привет, как дела?"))
        assertFalse(PunctuationGuard.accepts("меня зовут Антон","Меня зовут Андрей."))
        assertFalse(PunctuationGuard.accepts("платёж 1500 рублей","Платёж 1600 рублей."))
        assertFalse(PunctuationGuard.accepts("температура -5","Температура 5."))
        assertFalse(PunctuationGuard.accepts("привет","Исправленный текст: Привет!"))
    }
}
