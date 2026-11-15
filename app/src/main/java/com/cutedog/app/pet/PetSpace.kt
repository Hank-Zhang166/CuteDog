package com.cutedog.app.pet

import kotlin.math.*

/** Configuration-space visibility graph: obstacles include half the pet's dimensions. */
object PetSpace {
    data class Point(val x: Float, val y: Float) { fun distance(p: Point) = hypot(x - p.x, y - p.y) }
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(p: Point) = p.x > left && p.x < right && p.y > top && p.y < bottom
        fun clamp(p: Point) = Point(p.x.coerceIn(left, max(left, right)), p.y.coerceIn(top, max(top, bottom)))
        fun inflate(x: Float, y: Float) = Box(left - x, top - y, right + x, bottom + y)
    }
    fun valid(p: Point, area: Box, obstacles: List<Box>): Boolean = p.x >= area.left && p.x <= area.right && p.y >= area.top && p.y <= area.bottom && obstacles.none { it.contains(p) }

    fun nearestFree(p: Point, area: Box, obstacles: List<Box>): Point? {
        val bounded = area.clamp(p)
        if (valid(bounded, area, obstacles)) return bounded
        val eps = .001f
        val candidates = obstacles.flatMap { b -> listOf(Point(b.left - eps, bounded.y), Point(b.right + eps, bounded.y), Point(bounded.x, b.top - eps), Point(bounded.x, b.bottom + eps)) } + corners(area, 0f) + obstacles.flatMap { corners(it, eps) }
        return candidates.map { area.clamp(it) }.filter { valid(it, area, obstacles) }.minByOrNull { it.distance(bounded) }
    }

    fun route(start: Point, end: Point, area: Box, obstacles: List<Box>): List<Point> {
        if (!valid(start, area, obstacles) || !valid(end, area, obstacles)) return emptyList()
        if (clearSegment(start, end, obstacles)) return listOf(end)
        val nodes = (listOf(start, end) + obstacles.flatMap { corners(it, .001f) }).filter { valid(it, area, obstacles) }.distinct()
        if (nodes.size < 2) return emptyList()
        val costs = FloatArray(nodes.size) { Float.POSITIVE_INFINITY }; costs[0] = 0f
        val previous = IntArray(nodes.size) { -1 }; val visited = BooleanArray(nodes.size)
        repeat(nodes.size) {
            val u = nodes.indices.filter { !visited[it] }.minByOrNull { costs[it] } ?: return@repeat
            if (!costs[u].isFinite()) return@repeat
            visited[u] = true
            for (v in nodes.indices) {
                if (visited[v] || !clearSegment(nodes[u], nodes[v], obstacles)) continue
                val cost = costs[u] + nodes[u].distance(nodes[v])
                if (cost < costs[v]) { costs[v] = cost; previous[v] = u }
            }
        }
        if (!costs[1].isFinite()) return emptyList()
        val path = mutableListOf<Point>(); var cursor = 1
        while (cursor != 0) { path += nodes[cursor]; cursor = previous[cursor]; if (cursor < 0) return emptyList() }
        return path.reversed()
    }
    fun clearSegment(a: Point, b: Point, obstacles: List<Box>): Boolean = obstacles.none { intersects(a, b, it) }
    private fun corners(r: Box, eps: Float) = listOf(Point(r.left - eps, r.top - eps), Point(r.right + eps, r.top - eps), Point(r.right + eps, r.bottom + eps), Point(r.left - eps, r.bottom + eps))
    private fun intersects(a: Point, b: Point, r: Box): Boolean {
        if (r.contains(a) || r.contains(b)) return true
        var low = 0f; var high = 1f
        val dx = b.x - a.x; val dy = b.y - a.y
        fun slab(origin: Float, delta: Float, min: Float, max: Float): Boolean {
            if (abs(delta) < 1e-8f) return origin > min && origin < max
            val t1 = (min - origin) / delta; val t2 = (max - origin) / delta
            low = maxOf(low, minOf(t1, t2)); high = minOf(high, maxOf(t1, t2))
            return low < high
        }
        return slab(a.x, dx, r.left, r.right) && slab(a.y, dy, r.top, r.bottom) && high > 0 && low < 1
    }
}
