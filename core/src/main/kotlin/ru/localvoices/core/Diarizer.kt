package ru.localvoices.core

import kotlin.math.ceil

class Diarizer(private val config: DiarizationConfig) {
    fun run(windows: List<VoiceWindow>, profiles: List<VoiceProfile>, knownMatches: List<Identity?>? = null, checkpoint: () -> Unit = {}): List<Turn> {
        if (windows.isEmpty()) return emptyList()
        val matcher = KnownSpeakerMatcher(config)
        require(knownMatches == null || knownMatches.size == windows.size)
        // Cluster all reliable windows before assigning a known identity.  The old
        // order matched individual windows first, which removed a similar impostor
        // from HDBSCAN and made a whole foreign voice look like the known speaker.
        // A supplied match list remains supported for old callers/tests, while the
        // production worker deliberately passes null to use the safer path.
        val directMatches = windows.map { if (it.quality >= .5) matcher.match(it.embedding, profiles) else null }
        val matches: MutableList<Identity?> = if (knownMatches != null) knownMatches.toMutableList() else MutableList(windows.size) { null }
        if (knownMatches == null) {
            // Keep exceptionally clear profile matches as provisional identity
            // hints only.  They remain in the clustering input: an impostor must
            // still be able to form its own dialogue-level cluster.
            for (i in windows.indices) if (windows[i].quality >= .5) {
                val direct = directMatches[i]
                if (direct != null && direct.similarity >= config.knownThreshold + config.knownMargin)
                    matches[i] = direct
            }
        }
        // Every reliable window participates in anonymous clustering, including
        // windows that happen to match a registered profile.  Identity is a
        // later interpretation of the cluster, never a reason to remove data.
        val unknown = windows.indices.filter { windows[it].quality >= .5 }
        fun cluster(indices: List<Int>): IntArray = if (config.clusteringBackend == "ahc")
            Ahc(config.changeThreshold, config.clusterMinSize).fit(indices.map { windows[it].embedding }, checkpoint)
        else Hdbscan(config.clusterMinSize, config.minSamples).fit(indices.map { windows[it].embedding }, checkpoint)
        var labels = cluster(unknown)
        // A very strong known point can legitimately change HDBSCAN density and
        // turn a small unknown group into outliers.  Do not remove the known
        // point from the primary clustering; only recover the unresolved
        // unknown subset when the primary result produced no usable unknown
        // cluster at all.
        val hasUnknownCluster = labels.indices.any { labels[it] >= 0 && matches[unknown[it]] == null }
        if (!hasUnknownCluster) {
            val fallback = unknown.indices.filter { matches[unknown[it]] == null }
            if (fallback.size >= config.clusterMinSize) {
                val fallbackLabels = cluster(fallback.map { unknown[it] })
                val merged = IntArray(unknown.size) { -1 }
                fallback.forEachIndexed { index, original -> merged[original] = fallbackLabels[index] }
                labels = merged
            }
        }
        val groups = labels.indices.filter { labels[it] >= 0 }.groupBy { labels[it] }
        val centroids = groups.mapNotNull { (id, indices) -> Vectors.centroidOrNull(indices.map { windows[unknown[it]].embedding })?.let { id to it } }.toMap()
        // A homogeneous set may contain just one speaker; require pairwise acoustic evidence.
        val unresolved = unknown.filter { matches[it] == null }
        val homogeneous = if (groups.isEmpty() && unresolved.size >= 2) {
            if (unresolved.size <= 30) unresolved.all { a -> unresolved.all { b -> Vectors.cosine(windows[a].embedding, windows[b].embedding) >= config.smallSetThreshold } }
            else {
                val center = Vectors.centroidOrNull(unresolved.map { windows[it].embedding })
                center != null && unresolved.all { checkpoint(); Vectors.cosine(windows[it].embedding, center) >= config.smallSetThreshold }
            }
        } else false
        if (homogeneous) {
            labels.indices.filter { matches[unknown[it]] == null }.forEach { labels[it] = 0 }
        }
        // v4 assigned a high-confidence enrolled voice even when the recording
        // was too short or sparse for HDBSCAN to form a density cluster. Keep
        // that safe open-set fallback, but only after the anonymous clustering
        // pass has had a chance to contradict it.
        for (i in windows.indices) if (matches[i] == null) {
            val direct = directMatches[i] ?: continue
            val local = unknown.indexOf(i)
            val label = if (local >= 0) labels[local] else -1
            val clusterMembers = if (label >= 0) labels.indices.filter { labels[it] == label }.map { unknown[it] } else emptyList()
            val support = clusterMembers.count { directMatches[it]?.id == direct.id }
            val clusterAgreement = clusterMembers.isNotEmpty() && support >= ceil(clusterMembers.size * .7).toInt()
            if (label < 0 || clusterAgreement)
                matches[i] = direct
        }
        // If density clustering rejected every reliable point, use a conservative
        // deterministic agglomerative fallback so a normal single-speaker
        // recording does not become entirely unresolved. The old online
        // prototype fallback depended on input order, which made results look
        // random when nearly identical windows arrived in a different order.
        // HDBSCAN remains the primary algorithm.
        if (labels.none { it >= 0 } && unknown.size >= 2) {
            data class Group(val members: MutableList<Int>)
            val groups = unknown.indices.map { Group(mutableListOf(it)) }.toMutableList()
            fun score(a: Group, b: Group): Double {
                // Average-link similarity is less sensitive to a single noisy
                // window than nearest-neighbour chaining.
                var sum = 0.0; var count = 0
                for (ia in a.members) for (ib in b.members) {
                    sum += Vectors.cosine(windows[unknown[ia]].embedding, windows[unknown[ib]].embedding)
                    count++
                }
                return sum / count
            }
            while (true) {
                var bestA = -1; var bestB = -1; var best = config.changeThreshold
                for (a in 0 until groups.size) for (b in a + 1 until groups.size) {
                    val value = score(groups[a], groups[b])
                    if (value > best || (value == best && (a < bestA || bestA < 0))) {
                        best = value; bestA = a; bestB = b
                    }
                }
                if (bestA < 0) break
                groups[bestA].members += groups[bestB].members
                groups.removeAt(bestB)
            }
            val fallback = IntArray(unknown.size) { -1 }
            groups.filter { it.members.size >= 2 }.forEachIndexed { label, group ->
                group.members.forEach { fallback[it] = label }
            }
            if (fallback.any { it >= 0 }) labels = fallback
        }
        // Recover noise only with an absolute threshold AND a runner-up margin.
        for (i in labels.indices) if (labels[i] < 0) {
            val ranked = centroids.map { it.key to Vectors.cosine(windows[unknown[i]].embedding, it.value) }.sortedByDescending { it.second }
            val first = ranked.firstOrNull()
            if (first != null && first.second >= config.noiseThreshold && first.second - (ranked.getOrNull(1)?.second ?: -1.0) >= config.knownMargin)
                labels[i] = first.first
        }
        // Cluster-level known-speaker matching uses both centroid and member agreement.
        // A known name is assigned only when the cluster as a whole supports it.
        for (label in labels.filter { it >= 0 }.distinct()) {
            val members = labels.indices.filter { labels[it] == label }.map { unknown[it] }
            val center = Vectors.centroidOrNull(members.map { windows[it].embedding }) ?: continue
            val identity = matcher.match(center, profiles)
            val supportingMatcher = KnownSpeakerMatcher(config.copy(knownThreshold = (config.knownThreshold - config.knownMargin.coerceAtLeast(.02)).coerceAtLeast(0.0)))
            if (identity != null && members.size >= 3 && members.count { supportingMatcher.match(windows[it].embedding, profiles)?.id == identity.id } >= ceil(members.size * .7).toInt())
                members.forEach { matches[it] = identity }
        }
        val unknownLabels = unknown.indices.associate { unknown[it] to labels[it] }
        val numbering = linkedMapOf<Int, Int>()
        val turns = windows.mapIndexed { i, w ->
            val identity = matches[i]
            val label = unknownLabels[i] ?: -1
            if (identity != null) Turn(w.span, identity.id, identity.name, identity.similarity)
            else if (label >= 0) {
                val number = numbering.getOrPut(label) { numbering.size + 1 }
                Turn(w.span, "cluster_$number", "Говорящий $number", 0.0)
            } else Turn(w.span, null, "Неизвестный", 0.0)
        }
        // Overlapping embedding windows vote over elementary time intervals.
        val boundaries = turns.flatMap { listOf(it.span.start, it.span.end) }.distinct().sorted()
        val result = mutableListOf<Turn>()
        boundaries.zipWithNext().forEach { (a, b) ->
            val interval = Span(a, b)
            val active = turns.filter { it.span.overlap(interval) > 0 }
            if (active.isEmpty()) return@forEach
            val ranked = active.groupBy { it.speakerId }.entries.sortedByDescending { it.value.size }
            val winner = ranked.first()
            val certain = ranked.size == 1 || winner.value.size > ranked[1].value.size
            val t = if (certain) winner.value.first().copy(span = interval,confidence=winner.value.map {it.confidence}.average()) else Turn(interval, null, "Неизвестный", 0.0)
            val last = result.lastOrNull()
            if (last != null && last.speakerId == t.speakerId && last.span.end == a)
                result[result.lastIndex] = last.copy(span = Span(last.span.start, b),confidence=(last.confidence*last.span.duration+t.confidence*interval.duration)/(last.span.duration+interval.duration)) else result += t
        }
        return result
    }
}

object TranscriptMerger {
    /**
     * Attribute complete sentence-sized units to speakers, then coalesce adjacent
     * sentences by speaker.  This is deliberately sentence-first: assigning every
     * individual GigaAM word independently makes tiny timestamp/turn gaps create a
     * new message for each word.
     */
    fun merge(asr: List<AsrUnit>, turns: List<Turn>, wordLevel: Boolean = false): List<Utterance> {
        if (asr.isEmpty()) return emptyList()
        if (wordLevel) return mergeWordLevel(asr, turns)
        val sentences = segmentSentences(asr)
        val result = sentences.map { sentence ->
            // Do not smooth or force speaker continuity across sentences.  A
            // podcast can legitimately switch speakers at a sentence boundary.
            // Speaker evidence is still aggregated over the small embedding
            // windows that overlap this sentence.
            val turn = speakerFor(sentence.span, turns)
            Utterance(sentence.span, turn?.speakerId, turn?.name ?: "Неизвестный", sentence.text, turn?.confidence ?: 0.0)
        }
        // A speaker may produce several short sentences in one continuous turn;
        // keep those as one user-facing message when the pause is small.
        return coalesce(result, maxGap = 2.0, maxDuration = 20.0, maxChars = 300)
    }

    private fun mergeWordLevel(asr: List<AsrUnit>, turns: List<Turn>): List<Utterance> {
        val chunks = mutableListOf<Utterance>(); var current: Utterance? = null
        fun flush() { current?.let { chunks += it }; current = null }
        for (word in asr) {
            val turn = speakerFor(word.span, turns)
            val next = Utterance(word.span, turn?.speakerId, turn?.name ?: "Неизвестный", word.text, turn?.confidence ?: 0.0)
            val last = current
            val terminal = word.text.trimEnd().lastOrNull() in setOf('.', '!', '?', '…', '。', '！', '？')
            if (last == null) current = next
            else if (last.speakerId != next.speakerId || last.name != next.name || next.span.start - last.span.end > 1.25) { flush(); current = next }
            else {
                val duration = (last.span.duration + next.span.duration).coerceAtLeast(.001)
                current = last.copy(span = Span(last.span.start, next.span.end), text = joinText(last.text, next.text), confidence = (last.confidence * last.span.duration + next.confidence * next.span.duration) / duration)
            }
            if (terminal || (current?.text?.length ?: 0) >= 240 || (current?.span?.duration ?: 0.0) >= 15.0) flush()
        }
        flush(); return coalesce(chunks, maxGap = 2.0, maxDuration = 20.0, maxChars = 300)
    }

    private fun segmentSentences(asr: List<AsrUnit>): List<AsrUnit> {
        val result = mutableListOf<AsrUnit>()
        val current = mutableListOf<AsrUnit>()
        fun flush() {
            if (current.isEmpty()) return
            val first = current.first(); val last = current.last()
            result += AsrUnit(Span(first.span.start, last.span.end), current.joinToString("") { it.text })
            current.clear()
        }
        for (unit in asr) {
            if (current.isNotEmpty() && unit.span.start - current.last().span.end >= 1.25) flush()
            current += unit
            val trimmed = unit.text.trimEnd()
            val terminal = trimmed.lastOrNull() in setOf('.', '!', '?', '…', '。', '！', '？')
            val tooLong = current.last().span.end - current.first().span.start >= 15.0 ||
                current.sumOf { it.text.length } >= 240
            if (terminal || tooLong) flush()
        }
        flush()
        return result
    }

    private fun speakerFor(span: Span, turns: List<Turn>): Turn? {
        if (turns.isEmpty()) return null
        val grouped = turns.groupBy { it.speakerId }.mapNotNull { (id, rows) ->
            if (id == null) return@mapNotNull null
            val overlap = rows.sumOf { it.span.overlap(span) }
            if (overlap <= 0.0) null else {
                val best = rows.maxByOrNull { it.confidence }!!
                best.copy(span = span, confidence = rows.sumOf { it.confidence * it.span.overlap(span) } / overlap) to overlap
            }
        }.sortedByDescending { it.second }
        val best = grouped.firstOrNull() ?: return null
        val runnerUp = grouped.getOrNull(1)?.second ?: 0.0
        return if (best.second >= span.duration * .35 && (runnerUp == 0.0 || best.second > runnerUp * 1.10)) best.first else null
    }

    /**
     * ASR blocks and segmentation boundaries can split one continuous turn into
     * several adjacent utterances. Merge only a positively identified speaker
     * (never two unresolved/noise blocks), allowing a natural short pause.
     */
    fun coalesce(utterances: List<Utterance>, maxGap: Double = 1.25, maxDuration: Double = 20.0, maxChars: Int = 300): List<Utterance> {
        if (utterances.isEmpty()) return emptyList()
        val result = mutableListOf<Utterance>()
        for (next in utterances) {
            val last = result.lastOrNull()
            if (last != null && canCoalesce(last, next, maxGap) &&
                next.span.end - last.span.start <= maxDuration &&
                last.text.length + next.text.length <= maxChars) {
                val duration = (last.span.duration + next.span.duration).coerceAtLeast(.001)
                result[result.lastIndex] = last.copy(
                    span = Span(last.span.start, next.span.end),
                    text = joinText(last.text, next.text),
                    confidence = (last.confidence * last.span.duration + next.confidence * next.span.duration) / duration,
                )
            } else result += next
        }
        return result
    }

    private fun canCoalesce(a: Utterance, b: Utterance, maxGap: Double = 1.25): Boolean {
        val unresolvedPair = a.speakerId == null && b.speakerId == null && a.name == "Неизвестный" && b.name == "Неизвестный"
        if (!unresolvedPair && (a.speakerId == null || b.speakerId == null)) return false
        val sameIdentity = unresolvedPair || a.speakerId == b.speakerId ||
            (a.name == b.name && a.name != "Неизвестный" && !a.name.startsWith("Говорящий "))
        val gap = b.span.start - a.span.end
        return sameIdentity && gap in 0.0..maxGap
    }

    private fun joinText(left: String, right: String): String {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val punctuation = right.firstOrNull()?.let { it in ",.!?:;)]}%»…" } == true
        return if (punctuation || left.last().isWhitespace() || right.first().isWhitespace()) left + right else "$left $right"
    }
}
