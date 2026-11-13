package com.mycard.wallet.ui.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Card corner curvature.
 *
 * A corner is a quarter of a superellipse (Lamé curve) `|x/r|^n + |y/r|^n = 1` with `n = 4`,
 * sampled into a path. A superellipse reaches zero curvature where it meets the straight edge, so
 * the outline reads as one continuous bend; a circular corner keeps a constant radius and the seam
 * stays visible. This is a visual approximation of Apple's continuous corners, not the identical
 * spline, and it is deliberately not a rounded rectangle: [continuousCornerSample] is asserted in
 * tests against the superellipse formula so the difference from an arc stays verifiable.
 */
internal const val CONTINUOUS_CORNER_EXPONENT = 4f

/** Sampled points per corner. 14 keeps the curve smooth at card sizes without a long path. */
private const val CORNER_SEGMENTS = 14

fun continuousCornerShape(radius: Dp, exponent: Float = CONTINUOUS_CORNER_EXPONENT): Shape =
    ContinuousCornerShape(radius, exponent)

private class ContinuousCornerShape(private val radius: Dp, private val exponent: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(cardOutlinePath(size, with(density) { radius.toPx() }, exponent))
}

/**
 * The single outline behind the card clip, the edge light and the shadow, so all three can never
 * drift apart. Straight edges are lines; each corner walks the superellipse from one edge to the
 * next, which is what makes the transition continuous instead of an inset arc.
 */
fun cardOutlinePath(size: Size, radiusPx: Float, exponent: Float = CONTINUOUS_CORNER_EXPONENT,
                    segments: Int = CORNER_SEGMENTS): Path {
    val w = size.width
    val h = size.height
    val path = Path()
    val r = min(radiusPx, min(w, h) / 2f)
    if (r <= 0f) { path.addRect(Rect(0f, 0f, w, h)); return path }
    fun along(value: Float) = abs(value).pow(2f / exponent)
    val quarter = PI.toFloat() / 2f
    path.moveTo(r, 0f)
    path.lineTo(w - r, 0f)
    for (i in 1..segments) { val t = quarter * i / segments
        path.lineTo(w - r + r * along(sin(t)), r - r * along(cos(t))) }
    path.lineTo(w, h - r)
    for (i in 1..segments) { val t = quarter * i / segments
        path.lineTo(w - r + r * along(cos(t)), h - r + r * along(sin(t))) }
    path.lineTo(r, h)
    for (i in 1..segments) { val t = quarter * i / segments
        path.lineTo(r - r * along(sin(t)), h - r + r * along(cos(t))) }
    path.lineTo(0f, r)
    for (i in 1..segments) { val t = quarter * i / segments
        path.lineTo(r - r * along(cos(t)), r - r * along(sin(t))) }
    path.close()
    return path
}

/**
 * Offset from a corner centre for parameter [t] on a continuous corner: the superellipse coordinate
 * pair, exposed so tests can compare it with the circle arc instead of trusting the drawing.
 */
internal fun continuousCornerSample(t: Float, radius: Float,
                                    exponent: Float = CONTINUOUS_CORNER_EXPONENT): Pair<Float, Float> =
    (radius * abs(cos(t)).pow(2f / exponent)) to (radius * abs(sin(t)).pow(2f / exponent))
