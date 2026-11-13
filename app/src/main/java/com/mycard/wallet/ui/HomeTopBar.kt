package com.mycard.wallet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mycard.wallet.data.CardFilter
import com.mycard.wallet.data.CardLayout
import com.mycard.wallet.data.SortOrder
import com.mycard.wallet.data.WalletSettings
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.ui.theme.WalletMotion
import kotlin.math.roundToInt

/**
 * Home header for UI V2.2. The title/count badge and search keep their V2.1 positions. The second
 * row now owns three independent actions: sorting, a centered three-way layout switch and reorder.
 *
 * Layout selection still goes through the existing `onLayout` callback, which the ViewModel writes
 * to the existing DataStore layout key; no duplicate selection state exists here. Sorting likewise
 * reuses `settings.sort` and `onSort`. The switch uses selectable semantics (radio role) so
 * TalkBack announces both the arrangement and its selected state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeTopBar(
    activeCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    onSearchToggle: () -> Unit,
    onSearchClose: () -> Unit,
    filter: CardFilter,
    onFilter: (CardFilter) -> Unit,
    settings: WalletSettings,
    reorderEnabled: Boolean,
    onLayout: (CardLayout) -> Unit,
    onSort: (SortOrder) -> Unit,
    onReorder: () -> Unit
) {
    var sortMenu by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = WalletDesign.pageInset).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(top = WalletDesign.homeTopGap)
            .heightIn(min = WalletDesign.touch), verticalAlignment = Alignment.CenterVertically) {
            // The title may shrink and ellipsize, but the badge is measured before it and therefore
            // always stays attached to the title instead of drifting to the right edge.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(Copy.collection, Modifier.weight(1f, fill = false).testTag("home-title"),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                HomeCountBadge(activeCount)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onSearchToggle, Modifier.size(WalletDesign.touch)) {
                Icon(Icons.Rounded.Search, Copy.search)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = WalletDesign.homeToolGap)
            .heightIn(min = WalletDesign.touch), verticalAlignment = Alignment.CenterVertically) {
            // Sorting is a separate entry now; it reuses the same persisted SortOrder values and
            // keeps the existing "current sort" check mark semantics.
            Box {
                FilledTonalIconButton({ sortMenu = true }, Modifier.size(WalletDesign.touch).testTag("home-sort")) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, Copy.sorting)
                }
                if (LocalWalletVisible.current) DropdownMenu(sortMenu, { sortMenu = false }) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(text = { Text(order.label) },
                            trailingIcon = { if (settings.sort == order) Icon(Icons.Rounded.Check, Copy.currentSort) },
                            onClick = { sortMenu = false; onSort(order) })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            HomeLayoutSwitch(settings.layout, onLayout, Modifier.widthIn(max = WalletDesign.homeLayoutSwitchWidth), settings.animations)
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(onReorder, Modifier.size(WalletDesign.touch).testTag("home-reorder"), enabled = reorderEnabled) {
                Icon(Icons.Rounded.SwapVert, Copy.reorder)
            }
        }
        // The category row sits directly under the tools. A 6dp gap above and below the expanded
        // search field keeps it separated from both the tools and the chips without an extra row.
        if (searching) OutlinedTextField(query, onQueryChange,
            Modifier.fillMaxWidth().padding(top = WalletDesign.homeSearchGap, bottom = WalletDesign.homeSearchGap),
            placeholder = { Text(Copy.search, style = MaterialTheme.typography.bodySmall) }, singleLine = true,
            trailingIcon = { IconButton(onSearchClose) { Icon(Icons.Rounded.Close, Copy.cancel) } })
        else Spacer(Modifier.height(WalletDesign.homeChipGap))
        HomeFilterBar(filter, onFilter, settings.animations)
    }
}

/** Keeps the original two-digit text semantics while giving the count its rounded badge. */
@Composable
private fun HomeCountBadge(count: Int) {
    Surface(
        modifier = Modifier.testTag("home-count-badge"),
        shape = RoundedCornerShape(WalletDesign.homeControlRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            count.toString().padStart(2, '0'),
            Modifier.defaultMinSize(minWidth = WalletDesign.homeBadgeMinWidth)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * A compact three-way arrangement switch. Each segment is a 48dp-tall radio target, so the whole
 * control fits between the 48dp sort and reorder buttons even at 320dp. Selection is expressed with
 * both colour and the `selected` semantics property; the icon itself carries no description, so
 * TalkBack reads one label per segment.
 */
@Composable
private fun HomeLayoutSwitch(
    selected: CardLayout,
    onSelect: (CardLayout) -> Unit,
    modifier: Modifier = Modifier,
    animations: Boolean = true
) {
    val targetIndex = CardLayout.entries.indexOf(selected).coerceAtLeast(0)
    val animatable = remember { Animatable(targetIndex.toFloat()) }
    LaunchedEffect(targetIndex, animations) {
        if (!animations) {
            animatable.snapTo(targetIndex.toFloat())
        } else {
            animatable.animateTo(
                targetValue = targetIndex.toFloat(),
                animationSpec = tween(durationMillis = WalletMotion.quick, easing = WalletMotion.easing)
            )
        }
    }
    Surface(
        modifier = modifier.testTag("home-layout-switch"),
        shape = RoundedCornerShape(WalletDesign.homeControlRadius),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(WalletDesign.touch)) {
            val count = CardLayout.entries.size
            val gap = 2.dp
            val segmentWidth = (maxWidth - gap * (count - 1)) / count
            val stepPx = with(LocalDensity.current) { (segmentWidth + gap).toPx() }

            Box(
                Modifier
                    .offset { IntOffset(x = (animatable.value * stepPx).roundToInt(), y = 0) }
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(WalletDesign.homeControlRadius))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .16f))
                    .testTag("home-layout-indicator")
            )

            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                CardLayout.entries.forEach { layout ->
                    val active = selected == layout
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .clip(RoundedCornerShape(WalletDesign.homeControlRadius))
                            // Background now provided by the sliding indicator.
                            .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(layout) })
                            .semantics { contentDescription = "${Copy.arrangement}：${layout.label}" }
                            .testTag("home-layout-${layout.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(layoutIcon(layout), null, Modifier.size(20.dp),
                            tint = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}


private fun layoutIcon(layout: CardLayout): ImageVector = when (layout) {
    CardLayout.STACK -> Icons.Rounded.Layers
    CardLayout.LIST -> Icons.Rounded.ViewAgenda
    CardLayout.ALBUM -> Icons.Rounded.AutoStories
}
