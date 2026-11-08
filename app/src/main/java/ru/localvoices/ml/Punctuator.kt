package ru.localvoices.ml

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.localvoices.core.PunctuationGuard
import ru.localvoices.core.Utterance
import ru.localvoices.llm.LocalLlm

data class PunctuationResult(val rows: List<Utterance>, val accepted: Int, val rejected: Int)
class Punctuator(private val modelStore: ModelStore, private val threads: Int) {
    suspend fun run(rows: List<Utterance>, active: (LocalLlm?) -> Unit): PunctuationResult {
        var accepted=0;var rejected=0
        LocalLlm(modelStore.requireFile("punctuation.gguf").absolutePath,threads).use { llm ->
            active(llm)
            try {
                val result=rows.map { row ->
                    currentCoroutineContext().ensureActive()
                    val chunks=row.text.trim().split(Regex("\\s+")).chunked(35).map { it.joinToString(" ") }
                    val rendered=chunks.map { text ->
                        val proposed=try {llm.complete(text).trim()} catch(e:Exception){currentCoroutineContext().ensureActive();""}
                        if(PunctuationGuard.accepts(text,proposed)){accepted++;proposed}else{rejected++;text}
                    }.joinToString(" ")
                    row.copy(text=rendered)
                }
                return PunctuationResult(result,accepted,rejected)
            } finally {active(null)}
        }
    }
}
