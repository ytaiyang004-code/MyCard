package com.mycard.wallet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mycard.wallet.data.CardFilter
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.ui.theme.WalletMotion

/**
 * Home-only filter bar. `Choices` is shared by the editor, so its global FilterChip look is left
 * untouched and this compact frosted capsule is used only on the home screen.
 *
 * The bar is exactly 40% of the available top-bar width (with a small narrow-screen floor), and is
 * centered by its parent. It is intentionally not a Material `Surface`: the previous Surface shadow
 * layer produced a 1px light seam through the semi-transparent capsule on some OEM renderers. A
 * single clipped background plus an outer border keeps the glass look without any interior drawing.
 * There is no RenderEffect blur, divider, drawLine or text decoration inside the row.
 */
@Composable
internal fun HomeFilterBar(
    selected: CardFilter,
    onSelect: (CardFilter) -> Unit,
    animations: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state = rememberLazyListState()
    val target = CardFilter.entries.indexOf(selected)
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val measurer = rememberTextMeasurer()
    // Measure the widest label with the selected weight at the current density and font scale.
    // The floor is derived from real content, not an arbitrary font-scale breakpoint; on a normal
    // 393dp screen the 40% width still wins, so the accepted visual remains unchanged.
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val contentWidth = remember(measurer, labelStyle, density) {
        val widest = CardFilter.entries.maxOf { filter ->
            measurer.measure(filter.label, style = labelStyle, maxLines = 1,
                softWrap = false, overflow = TextOverflow.Clip).size.width
        }
        with(density) { widest.toDp() } +
            WalletDesign.homeFilterPillInset * 2 + WalletDesign.homeFilterContentInset * 2
    }
    // Keep the restored or newly selected category visible. The effect runs after the row has
    // measured; animate only when the item is actually (partly) outside the viewport.
    LaunchedEffect(selected) {
        if (target < 0) return@LaunchedEffect
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target }
        val visible = item != null && item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (!visible) state.animateScrollToItem(target)
    }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val floor = if (fontScale >= 1.3f) WalletDesign.homeFilterMinWidthLargeText
        else WalletDesign.homeFilterMinWidth
        // The content floor can widen a narrow/large-text container, but never beyond the parent.
        val barWidth = (maxWidth * WalletDesign.homeFilterWidthFraction)
            .coerceAtLeast(maxOf(floor, contentWidth)).coerceAtMost(maxWidth)
        val shape = RoundedCornerShape(WalletDesign.homeFilterRadius)
        Box(
            modifier = Modifier.width(barWidth).heightIn(min = WalletDesign.homeFilterHeight)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = WalletDesign.homeFilterAlpha))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = WalletDesign.homeFilterBorderAlpha), shape)
                .testTag("home-filter-bar")
        ) {
            LazyRow(
                state = state,
                modifier = Modifier.fillMaxWidth().heightIn(min = WalletDesign.touch).testTag("home-filter-list"),
                contentPadding = PaddingValues(horizontal = WalletDesign.homeFilterContentInset, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(WalletDesign.homeFilterItemGap)
            ) {
                items(CardFilter.entries, key = { it.name }) { filter ->
                    FilterPill(filter, selected == filter, animations) { onSelect(filter) }
                }
            }
        }
    }
}

@Composable
private fun FilterPill(filter: CardFilter, selected: Boolean, animations: Boolean, onClick: () -> Unit) {
    val colors = rememberFilterPillColors(selected, animations)
    Box(
        modifier = Modifier.heightIn(min = WalletDesign.touch)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag("home-filter-${filter.name}"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.heightIn(min = WalletDesign.homeFilterPillHeight)
                .clip(CircleShape)
                .background(colors.background)
                .padding(horizontal = WalletDesign.homeFilterPillInset, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                filter.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

internal data class FilterPillColors(
    val background: Color,
    val content: Color,
    val targetBackground: Color,
    val targetContent: Color
)

/**
 * Explicit colour lifecycle for a filter pill. A single remembered progress drives both the
 * background and text colours, so they cannot desynchronise. Unlike `animateColorAsState`, the
 * effect is keyed by the animation switch as well as the selection: toggling animations off cancels
 * the in-flight tween and snaps the progress to the current target. Background and content therefore
 * settle immediately without waiting for the remaining 160ms.
 */
@Composable
internal fun rememberFilterPillColors(selected: Boolean, animations: Boolean): FilterPillColors {
    val selectedBackground = MaterialTheme.colorScheme.primary.copy(alpha = .14f)
    val unselectedBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0f)
    val selectedContent = MaterialTheme.colorScheme.primary
    val unselectedContent = MaterialTheme.colorScheme.onSurfaceVariant
    val targetBackground = if (selected) selectedBackground else unselectedBackground
    val targetContent = if (selected) selectedContent else unselectedContent
    val progress = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected, animations) {
        val target = if (selected) 1f else 0f
        if (animations) {
            progress.animateTo(target, tween(durationMillis = WalletMotion.quick, easing = WalletMotion.easing))
        } else {
            progress.snapTo(target)
        }
    }
    val fraction = if (animations) progress.value else if (selected) 1f else 0f
    return FilterPillColors(
        background = MaterialTheme.colorScheme.primary.copy(alpha = .14f * fraction),
        content = lerp(unselectedContent, selectedContent, fraction),
        targetBackground = targetBackground,
        targetContent = targetContent
    )
}
