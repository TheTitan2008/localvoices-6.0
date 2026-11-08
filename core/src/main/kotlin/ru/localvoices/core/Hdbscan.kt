package ru.localvoices.core

import kotlin.math.*

/** Exact dense HDBSCAN: core distances -> mutual reachability MST -> single linkage
 * -> condensed hierarchy -> excess-of-mass cluster selection. O(n²) time/memory.
 * No fixed k, no DBSCAN epsilon, and no forced assignment of noise.
 */
class Hdbscan(private val minClusterSize: Int = 3, private val minSamples: Int = 2) {
    init { require(minClusterSize >= 2 && minSamples >= 1) }
    private data class Edge(val a: Int, val b: Int, val distance: Double)
    private data class Node(val size: Int, val distance: Double, val left: Node? = null, val right: Node? = null, val leaf: Int = -1) {
        fun points(): IntArray {
            val result = ArrayList<Int>(size); val pending = java.util.ArrayDeque<Node>(); pending.add(this)
            while (pending.isNotEmpty()) { val n = pending.removeLast(); if (n.leaf >= 0) result.add(n.leaf) else { pending.add(n.left!!); pending.add(n.right!!) } }
            return result.toIntArray()
        }
    }
    private class Cluster(val birth: Double, val points: IntArray) {
        var stability = 0.0
        val children = mutableListOf<Cluster>()
    }
    fun fit(vectors: List<FloatArray>, checkpoint: () -> Unit = {}): IntArray {
        val n = vectors.size
        val labels = IntArray(n) { -1 }
        if (n < max(minClusterSize, minSamples + 1)) return labels
        require(n <= 3000) { "Не более 3000 окон на запуск HDBSCAN" }
        val x = vectors.map(Vectors::normalized)
        // Store only the lower triangle: 36 MB at the 3000-point limit, not 72 MB.
        val distances = DoubleArray(n * (n - 1) / 2)
        fun offset(i: Int, j: Int): Int { val a = max(i,j); val b = min(i,j); return a * (a - 1) / 2 + b }
        fun distance(i: Int, j: Int) = if (i == j) 0.0 else distances[offset(i,j)]
        for (i in 0 until n) { checkpoint(); for (j in 0 until i) {
            require(x[i].size == x[j].size)
            var squared = 0.0
            for (c in x[i].indices) { val delta = x[i][c].toDouble() - x[j][c]; squared += delta * delta }
            distances[offset(i,j)] = sqrt(squared)
        } }
        val core = DoubleArray(n) { i -> DoubleArray(n - 1) { k -> distance(i, if(k < i) k else k + 1) }.apply { sort() }[min(minSamples, n - 1) - 1] }
        val used = BooleanArray(n)
        val best = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val parent = IntArray(n) { -1 }
        val edges = mutableListOf<Edge>()
        best[0] = 0.0
        repeat(n) {
            checkpoint()
            val u = (0 until n).filter { !used[it] }.minBy { best[it] }
            used[u] = true
            if (parent[u] >= 0) edges += Edge(parent[u], u, best[u])
            for (v in 0 until n) if (!used[v]) {
                val d = max(max(core[u], core[v]), distance(u,v))
                if (d < best[v]) { best[v] = d; parent[v] = u }
            }
        }
        val uf = IntArray(n) { it }
        fun root(v: Int): Int { var r = v; while (uf[r] != r) r = uf[r]; return r }
        val nodes = Array(n) { Node(1, 0.0, leaf = it) }
        // Kotlin's stable sort preserves Prim discovery order for equal MRD weights.
        // Changing this policy can change membership of boundary/noise points.
        for (e in edges.sortedBy { it.distance }) {
            val a = root(e.a); val b = root(e.b)
            nodes[a] = Node(nodes[a].size + nodes[b].size, e.distance, nodes[a], nodes[b]); uf[b] = a
        }
        val top = nodes[root(0)]
        val tree = Cluster(0.0, top.points())
        val pending = java.util.ArrayDeque<Pair<Node,Cluster>>()
        pending.add(top to tree)
        while(pending.isNotEmpty()) {
            checkpoint()
            val (node,cluster)=pending.removeLast()
            val left = node.left ?: continue
            val right = node.right!!
            // Identical vectors have an infinite lambda; cap only for numeric stability.
            val lambda = 1.0 / max(node.distance, 1e-7)
            val a = left.size >= minClusterSize; val b = right.size >= minClusterSize
            if (a && b) {
                cluster.stability += node.size * (lambda - cluster.birth)
                for (child in listOf(left, right)) {
                    val c = Cluster(lambda, child.points()); cluster.children += c; pending.add(child to c)
                }
            } else if (a || b) {
                val large = if (a) left else right; val small = if (a) right else left
                cluster.stability += small.size * (lambda - cluster.birth)
                pending.add(large to cluster)
            } else cluster.stability += node.size * (lambda - cluster.birth)
        }
        val hierarchy=mutableListOf(tree)
        var index=0
        while(index<hierarchy.size){checkpoint();hierarchy+=hierarchy[index++].children}
        val score=java.util.IdentityHashMap<Cluster,Double>()
        val chosen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Cluster,Boolean>())
        for(c in hierarchy.asReversed()) {
            checkpoint()
            val children=c.children.sumOf {score[it] ?: 0.0}
            if(c!==tree && c.stability>=children){chosen.add(c);score[c]=c.stability}else score[c]=children
        }
        val selected=mutableListOf<Cluster>();val visit=java.util.ArrayDeque<Cluster>();visit.add(tree)
        while(visit.isNotEmpty()){checkpoint();val c=visit.removeLast();if(c in chosen)selected+=c else visit.addAll(c.children)}
        selected.sortedBy { it.points.min() }.forEachIndexed { id, c -> c.points.forEach { labels[it] = id } }
        return labels
    }
}
