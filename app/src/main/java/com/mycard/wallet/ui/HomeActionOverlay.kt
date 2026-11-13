package com.mycard.wallet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.ui.theme.WalletMotion

/**
 * Bottom action chrome for UI V2.1. It is an overlay, not part of the Scaffold content, so the
 * LazyColumn viewport, card width and stack step used by the T5 fix are untouched.
 *
 * The add action is intentionally fixed in this phase. Dragging, edge snapping and position
 * persistence arrive in later phases; the left-bottom settings action will need a collision rule
 * with a left-snapped add button at that point.
 *
 * WalletApp draws its global SnackbarHost above this overlay. A visible undo/result snackbar can
 * therefore cover the settings action for its short lifetime; moving that host belongs to the
 * navigation/snackbar work, not this layout phase. The 144dp add offset and the settings offset also
 * mean both actions may sit over the lower corner of the last expanded card, which device acceptance
 * must confirm.
 */
@Composable
internal fun HomeActionOverlay(onAdd: () -> Unit, onSettings: () -> Unit, animations: Boolean = true) {
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        HomeCircleAction(
            description = Copy.add,
            tag = "home-add-fab",
            onClick = onAdd,
            visualSize = WalletDesign.homeFabVisual,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            animations = animations,
            modifier = Modifier.align(Alignment.BottomEnd).padding(
                end = WalletDesign.homeFabEndInset,
                bottom = WalletDesign.homeFabBottomInset
            )
        ) { Icon(Icons.Rounded.Add, null, Modifier.size(22.dp)) }

        HomeCircleAction(
            description = Copy.settings,
            tag = "home-settings",
            onClick = onSettings,
            visualSize = WalletDesign.homeSettingsVisual,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            animations = animations,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
            modifier = Modifier.align(Alignment.BottomStart).padding(
                start = WalletDesign.homeFabEndInset,
                bottom = WalletDesign.homeBottomActionInset
            )
        ) { Icon(Icons.Rounded.Tune, null, Modifier.size(20.dp)) }
    }
}

/**
 * A 48dp touch target around a smaller visual circle. Keeping the touch node at 48dp is what the
 * accessibility guidelines and the existing action-size regression tests require, while the
 * visible chrome stays close to the 37-40dp prototype.
 */
@Composable
private fun HomeCircleAction(
    description: String,
    tag: String,
    onClick: () -> Unit,
    visualSize: Dp,
    containerColor: Color,
    contentColor: Color,
    animations: Boolean,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val targetScale = if (pressed) .96f else 1f
    val scale = remember { Animatable(if (pressed) .96f else 1f) }
    val lastAnimations = remember { mutableStateOf(animations) }
    LaunchedEffect(targetScale, animations) {
        if (animations) {
            // Resuming from non-animated mode must not replay an old tween midpoint. The target
            // is authoritative, so synchronise first and then animate the (usually identical) target.
            if (!lastAnimations.value) scale.snapTo(targetScale)
            lastAnimations.value = true
            scale.animateTo(targetScale, tween(durationMillis = WalletMotion.quick, easing = WalletMotion.easing))
        } else {
            lastAnimations.value = false
            scale.snapTo(targetScale)
        }
    }
    Box(
        modifier = modifier.size(WalletDesign.touch).clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick
            )
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(visualSize).graphicsLayer {
                // Scale only the visible circle; the 48dp click target is unchanged.
                val scaleValue = if (animations) scale.value else targetScale
                scaleX = scaleValue
                scaleY = scaleValue
            }.testTag("$tag-visual"),
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            border = border,
            shadowElevation = 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}
