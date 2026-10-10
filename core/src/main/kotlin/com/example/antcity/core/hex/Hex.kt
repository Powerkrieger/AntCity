package com.example.antcity.core.hex

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Axial hex coordinate (pointy-top layout). The third cube coordinate is `s = -q - r`.
 *
 * All geometry here is exact integer math, including angles, so that scoring gives identical
 * results on every device and on the server.
 */
@Serializable
data class Hex(val q: Int, val r: Int) {
    val s get() = -q - r

    operator fun plus(o: Hex) = Hex(q + o.q, r + o.r)
    operator fun minus(o: Hex) = Hex(q - o.q, r - o.r)

    /** Steps from the origin. */
    val length get() = (abs(q) + abs(r) + abs(s)) / 2

    fun distanceTo(o: Hex) = (this - o).length

    fun neighbors() = DIRECTIONS.map { this + it }

    /** On one of the six straight lines through [o]. */
    fun isAlignedWith(o: Hex): Boolean {
        val d = this - o
        return d != ORIGIN && (d.q == 0 || d.r == 0 || d.s == 0)
    }

    companion object {
        val ORIGIN = Hex(0, 0)

        /**
         * The six neighbor directions, counter-clockwise on screen starting east:
         * 0 = E, 1 = NE, 2 = NW, 3 = W, 4 = SW, 5 = SE. A building's facing is an index into this list.
         */
        val DIRECTIONS = listOf(Hex(1, 0), Hex(1, -1), Hex(0, -1), Hex(-1, 0), Hex(-1, 1), Hex(0, 1))

        /** All hexes within [radius] of the origin. */
        fun disk(radius: Int): List<Hex> = buildList {
            for (q in -radius..radius) {
                for (r in maxOf(-radius, -q - radius)..minOf(radius, -q + radius)) add(Hex(q, r))
            }
        }

        // Screen position of axial (q, r) is (√3/2·u, 3/2·v) with u = 2q + r, v = r. Dot products of
        // such vectors are (3/4)·(u1·u2 + 3·v1·v2), so angle comparisons reduce to integer math.
        private fun dot(a: Hex, b: Hex): Long =
            (2L * a.q + a.r) * (2L * b.q + b.r) + 3L * a.r * b.r

        /**
         * Whether [target] is within ±[halfAngle] degrees of direction [facing] as seen from [from].
         * Boundaries are inclusive, so a 60° half-angle covers three of the six neighbors.
         */
        fun inCone(from: Hex, facing: Int, target: Hex, halfAngle: HalfAngle): Boolean {
            val d = target - from
            if (d == ORIGIN) return false
            val f = DIRECTIONS[facing]
            val dp = dot(d, f)
            if (dp <= 0) return false
            // cos(angle) >= cos(half)  <=>  dp² >= cos²(half) · |d|² · |f|²
            return 4 * dp * dp >= halfAngle.cosSquaredTimes4 * dot(d, d) * dot(f, f)
        }
    }
}

enum class HalfAngle(val cosSquaredTimes4: Long) {
    /** A narrow 60° cone. */
    DEG_30(3),
    /** A wide 120° cone. */
    DEG_60(1),
}

/** The six mirror lines through the origin, as coordinate reflections. */
object Mirrors {
    val ALL: List<(Hex) -> Hex> = listOf(
        { h -> Hex(h.r, h.q) },
        { h -> Hex(h.s, h.r) },
        { h -> Hex(h.q, h.s) },
        { h -> Hex(-h.r, -h.q) },
        { h -> Hex(-h.s, -h.r) },
        { h -> Hex(-h.q, -h.s) },
    )

    /** A hex on mirror line [axis] other than the origin, e.g. for drawing the line. */
    fun pointOnAxis(axis: Int): Hex = Hex.disk(2).first { it != Hex.ORIGIN && ALL[axis](it) == it }
}
