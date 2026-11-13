package com.mycard.wallet.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/** WCAG AA for card body text. Large card text may go down to [CARD_LARGE_TEXT_CONTRAST]. */
const val CARD_TEXT_CONTRAST = 4.5f
const val CARD_LARGE_TEXT_CONTRAST = 3f

/** Text areas of the artwork, each resolved against the gradient the card actually draws. */
enum class CardTextZone(val preferredAlpha: Float) {
    HEADER(1f), TAGS(.72f), FOOTER(.8f), TAIL(1f)
}

/**
 * Normalised samples across the extent a zone's text covers. Long names and tails cross a visible
 * part of the gradient, so one sample per zone would pick an ink that fails on the other end.
 */
internal fun zoneSamples(zone: CardTextZone): List<Pair<Float, Float>> = when (zone) {
    CardTextZone.HEADER -> listOf(.05f to .11f, .28f to .15f, .55f to .19f)
    CardTextZone.TAGS -> listOf(.06f to .50f, .30f to .50f, .52f to .50f)
    CardTextZone.FOOTER -> listOf(.06f to .78f, .28f to .80f, .50f to .82f)
    CardTextZone.TAIL -> listOf(.60f to .80f, .80f to .84f, .97f to .86f)
}

/**
 * Background under a normalised point. The drawn gradient runs from (0, 0) to (1, 1.3) of the card
 * box, so projecting onto that direction is size independent and matches the shader.
 */
fun cardBackgroundAt(primary: Long, secondary: Long, gradient: Boolean, x: Float, y: Float): Color {
    if (!gradient) return Color(primary)
    val t = ((x + y * 1.3f) / (1f + 1.3f * 1.3f)).coerceIn(0f, 1f)
    return lerp(Color(primary), Color(secondary), t)
}

fun cardZoneBackgrounds(primary: Long, secondary: Long, gradient: Boolean, zone: CardTextZone): List<Color> =
    zoneSamples(zone).map { (x, y) -> cardBackgroundAt(primary, secondary, gradient, x, y) }

fun contrastRatio(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    return (max(a, b) + .05f) / (min(a, b) + .05f)
}

/** [over] drawn at [alpha] on top of [base], matching how alpha text and a scrim both composite. */
fun blend(base: Color, over: Color, alpha: Float) = Color(
    red = base.red * (1 - alpha) + over.red * alpha,
    green = base.green * (1 - alpha) + over.green * alpha,
    blue = base.blue * (1 - alpha) + over.blue * alpha)

fun worstContrast(ink: Color, backgrounds: List<Color>, alpha: Float = 1f): Float =
    backgrounds.minOf { contrastRatio(blend(it, ink, alpha), it) }

/** Black or white, whichever holds up better across the whole zone. */
fun cardInkFor(backgrounds: List<Color>): Color =
    if (worstContrast(CardInkDark, backgrounds) >= worstContrast(CardInkLight, backgrounds)) CardInkDark else CardInkLight

/**
 * The strongest alpha at or below [preferred] that still reaches [target]. Secondary card text keeps
 * its softer look where the background allows it and is strengthened where it does not, so a label
 * can never quietly fall below the contrast floor.
 */
fun inkAlphaFor(backgrounds: List<Color>, ink: Color, preferred: Float,
                target: Float = CARD_TEXT_CONTRAST): Float =
    listOf(preferred, .9f, 1f).distinct().firstOrNull { worstContrast(ink, backgrounds, it) >= target } ?: 1f

/** The scrim colour that pushes a zone away from its ink: dark ink gets a light scrim, and back. */
fun scrimColorFor(ink: Color): Color = if (ink == CardInkDark) Color.White else Color.Black

/**
 * Minimum scrim alpha that lifts the zone to [target]. The scrim is drawn in the opposite colour of
 * the ink, so blending it in always moves the background the right way; zero means no help is needed
 * and the scrim only appears where a zone crosses from one end of the gradient to the other.
 */
fun scrimAlphaFor(backgrounds: List<Color>, ink: Color, target: Float = CARD_TEXT_CONTRAST): Float {
    if (worstContrast(ink, backgrounds) >= target) return 0f
    val scrim = scrimColorFor(ink)
    for (step in 1..12) {
        val alpha = step / 20f
        if (worstContrast(ink, backgrounds.map { blend(it, scrim, alpha) }) >= target) return alpha
    }
    return .6f
}
