package ru.localvoices.core

import kotlin.test.*

class HdbscanReferenceTest {
    @Test fun independentReferenceFixtures() {
        // Independent sklearn EOM; stable MST tie ordering is explicitly shared.
        val cases = javaClass.getResourceAsStream("/reference-cases-stable.tsv")!!.bufferedReader().readLines().map { it.split('\t') }.groupBy { it[0] }
        val differences = mutableListOf<String>()
        for ((id, rows) in cases) {
            val expected = rows.map {it[1].toInt()}
            val actual = Hdbscan(3,2).fit(rows.map {it[2].split(',').map(String::toFloat).toFloatArray()})
            for (i in actual.indices) {
                if ((expected[i]<0)!=(actual[i]<0)) differences += "$id: noise at $i"
                for (j in 0 until i) if ((expected[i]==expected[j])!=(actual[i]==actual[j])) differences += "$id: membership $i/$j"
            }
        }
        assertTrue(differences.isEmpty(),differences.joinToString("; "))
    }
}
