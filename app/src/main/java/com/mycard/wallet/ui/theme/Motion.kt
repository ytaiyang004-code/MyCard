package com.mycard.wallet.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Shared motion tokens for app chrome.
 *
 * The durations are the exact values the 1.2.0 build wrote inline, so switching call sites to
 * tokens changes no timing. [easing] is the easing [androidx.compose.animation.core.tween] already
 * defaulted to; it is stated here once instead of being implicit at each call site.
 *
 * The spring presets are intentionally unused in this build. They are reserved for the
 * gesture/expand pass so that pass does not introduce a second set of numbers.
 */
object WalletMotion {
    const val quick = 160
    const val standard = 180
    const val popEnter = 200
    const val enter = 220
    const val page = 260
    const val expand = 300
    val easing: Easing = FastOutSlowInEasing

    const val dampingCriticallyDamped = 1f
    const val dampingMomentumPass = .8f
    const val stiffnessStiff = 500f
    const val stiffnessGentle = 200f

    /** No overshoot; the default for anything that is not carrying user momentum. */
    fun <T> criticallyDamped(stiffness: Float = stiffnessStiff, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = dampingCriticallyDamped, stiffness = stiffness, visibilityThreshold = visibilityThreshold)

    /** Slight bounce; reserved for gestures that hand over release velocity. */
    fun <T> momentum(stiffness: Float = stiffnessGentle, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = dampingMomentumPass, stiffness = stiffness, visibilityThreshold = visibilityThreshold)
}
