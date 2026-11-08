package ru.localvoices.core

/** Deterministic average-link agglomerative clustering for speaker embeddings. */
class Ahc(private val threshold: Double = .72, private val minClusterSize: Int = 3) {
    init { require(threshold in -1.0..1.0 && minClusterSize >= 2) }
    private data class Group(val members: MutableList<Int>)
    fun fit(vectors: List<FloatArray>, checkpoint: () -> Unit = {}): IntArray {
        val labels = IntArray(vectors.size) { -1 }
        if (vectors.size < 2) return labels
        val groups = vectors.indices.map { Group(mutableListOf(it)) }.toMutableList()
        fun score(a: Group, b: Group): Double {
            var sum = 0.0; var count = 0
            for (ia in a.members) for (ib in b.members) { sum += Vectors.cosine(vectors[ia], vectors[ib]); count++ }
            return sum / count
        }
        while (true) {
            checkpoint(); var bestA = -1; var bestB = -1; var best = threshold
            for (a in 0 until groups.size) for (b in a + 1 until groups.size) {
                val value = score(groups[a], groups[b])
                if (value > best || (value == best && (bestA < 0 || a < bestA || (a == bestA && b < bestB)))) { best = value; bestA = a; bestB = b }
            }
            if (bestA < 0) break
            groups[bestA].members += groups[bestB].members; groups.removeAt(bestB)
        }
        groups.filter { it.members.size >= minClusterSize }.forEachIndexed { label, group -> group.members.forEach { labels[it] = label } }
        return labels
    }
}
