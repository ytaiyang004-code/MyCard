package com.mycard.wallet.ui

import kotlin.math.*

data class HsvColor(val hue: Float, val saturation: Float, val value: Float)
object ColorMath {
    fun fromArgb(argb: Long): HsvColor {
        val r = ((argb shr 16) and 255) / 255f; val g = ((argb shr 8) and 255) / 255f; val b = (argb and 255) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6)
            max == g -> 60f * ((b - r) / delta + 2)
            else -> 60f * ((r - g) / delta + 4)
        }
        return HsvColor((hue + 360) % 360, if (max == 0f) 0f else delta / max, max)
    }
    fun toArgb(color: HsvColor): Long {
        val h = ((color.hue % 360) + 360) % 360 / 60
        val s = color.saturation.coerceIn(0f, 1f); val v = color.value.coerceIn(0f, 1f)
        val c = v * s; val x = c * (1 - abs(h % 2 - 1)); val m = v - c
        val rgb = when (h.toInt()) {
            0 -> floatArrayOf(c, x, 0f); 1 -> floatArrayOf(x, c, 0f)
            2 -> floatArrayOf(0f, c, x); 3 -> floatArrayOf(0f, x, c)
            4 -> floatArrayOf(x, 0f, c); else -> floatArrayOf(c, 0f, x)
        }.map { ((it + m) * 255).roundToInt().coerceIn(0, 255).toLong() }
        return 0xFF000000L or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }
}
