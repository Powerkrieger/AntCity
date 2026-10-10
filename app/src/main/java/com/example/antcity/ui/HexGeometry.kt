package com.example.antcity.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.example.antcity.core.hex.Hex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val SQRT3 = sqrt(3f)

/** Pointy-top hex layout fitted into a canvas. */
class HexGeometry(width: Float, height: Float, radius: Int) {
    val size = minOf(width / (SQRT3 * (2 * radius + 1)), height / (3f * radius + 2f))
    private val cx = width / 2
    private val cy = height / 2

    fun center(h: Hex) = Offset(cx + size * SQRT3 * (h.q + h.r / 2f), cy + size * 1.5f * h.r)

    fun hexAt(p: Offset): Hex {
        val x = (p.x - cx) / size
        val y = (p.y - cy) / size
        val qf = SQRT3 / 3 * x - y / 3
        val rf = 2f / 3 * y
        val sf = -qf - rf
        var q = qf.roundToInt()
        var r = rf.roundToInt()
        val s = sf.roundToInt()
        val dq = abs(q - qf)
        val dr = abs(r - rf)
        val ds = abs(s - sf)
        if (dq > dr && dq > ds) q = -r - s else if (dr > ds) r = -q - s
        return Hex(q, r)
    }

    fun corner(h: Hex, angleDeg: Double, scale: Float): Offset {
        val c = center(h)
        val a = angleDeg * PI / 180
        return Offset(c.x + size * scale * cos(a).toFloat(), c.y + size * scale * sin(a).toFloat())
    }

    fun outline(h: Hex, scale: Float) = Path().apply {
        for (i in 0..5) {
            val p = corner(h, 60.0 * i - 30.0, scale)
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
}

fun boardAspect(radius: Int) = SQRT3 * (2 * radius + 1) / (3f * radius + 2f)
