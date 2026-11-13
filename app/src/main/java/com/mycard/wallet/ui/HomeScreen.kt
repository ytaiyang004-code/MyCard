package com.mycard.wallet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.ui.theme.WalletMotion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.mycard.wallet.WalletViewModel
import com.mycard.wallet.data.*

/** A placement callback from one concrete collection geometry, not just a remembered pixel height. */
private data class PlacedAdvance(
    val pass: Int,
    val advance: Int,
    val layout: CardLayout,
    val cardHeight: Dp,
    val stackStep: Dp
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: WalletViewModel, cards: List<WalletCard>, settings: WalletSettings,
               onAdd: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit, onReorder: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(CardFilter.ALL) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var pagerCard by rememberSaveable { mutableStateOf<String?>(null) }
    val activeCount = remember(cards) { cards.count { !it.archived } }
    val visible = remember(cards, query, filter, settings.sort) { CardRules.select(cards, query, filter, settings.sort) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val layoutAlpha = remember { Animatable(1f) }
    // Read at the moment a delayed visibility request is issued, not when its effect started.
    val currentSelected by rememberUpdatedState(selected)
    val currentLayout by rememberUpdatedState(settings.layout)
    // A fade belongs to an arrangement change made while this screen is composed. Entering the
    // screen (cold start, or a return from detail/editor/settings/reorder) shows the list fully
    // opaque, so its own fade never doubles the navigation transition.
    var lastLayout by remember { mutableStateOf(settings.layout) }
    LaunchedEffect(settings.layout, settings.animations) {
        val switched = lastLayout != settings.layout
        lastLayout = settings.layout
        if(switched && settings.animations) { layoutAlpha.snapTo(.65f); layoutAlpha.animateTo(1f, tween(WalletMotion.standard, easing = WalletMotion.easing)) }
        else layoutAlpha.snapTo(1f)
    }
    Box(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            HomeTopBar(
                activeCount = activeCount,
                query = query,
                onQueryChange = { query = it.take(160) },
                searching = searching,
                onSearchToggle = { searching = !searching; if (!searching) query = "" },
                onSearchClose = { query = ""; searching = false },
                filter = filter,
                onFilter = { filter = it; selected = null },
                settings = settings,
                reorderEnabled = cards.size > 1,
                onLayout = vm::layout,
                onSort = vm::sort,
                onReorder = onReorder
            )
        }) { padding ->
        if (visible.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AccountBalanceWallet, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(28.dp))
                if (cards.isEmpty()) Text(Copy.tagline, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(if (cards.isEmpty()) Copy.emptyTitle else Copy.noResults, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text(if (cards.isEmpty()) Copy.emptyBody else Copy.noResultsBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onAdd, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp)) { Text(Copy.add) }
            }
        } else if(settings.layout == CardLayout.ALBUM) {
            Box(Modifier.fillMaxSize().padding(padding).graphicsLayer { alpha = layoutAlpha.value }) {
                CardShowcase(visible, settings, vm.repository, pagerCard, { pagerCard = it }, onOpen)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding).graphicsLayer { alpha = layoutAlpha.value }) {
                val cardHeight = (maxWidth - 40.dp) / CARD_RATIO
                // Stacked faces advance by a step derived from the space actually left for the collection:
                // a long stack then needs at most one short scroll instead of a full page of collapsed
                // edges, while the step never drops below the 48dp touch minimum.
                val stackStep = if (settings.layout == CardLayout.STACK && visible.size > 1)
                    ((maxHeight - cardHeight - 80.dp) / (visible.size - 1)).coerceIn(WalletDesign.touch, 82.dp) else 82.dp
                // This pass is reset whenever the geometry that positions the cards changes. It is a
                // collection-level fallback for the one case an item cannot handle: the selected card
                // is recycled after scrolling the expanded LIST, so it has no effect to request
                // visibility after returning to STACK.
                val collectionPass = remember(settings.layout, cardHeight, stackStep) { mutableIntStateOf(0) }
                LaunchedEffect(settings.layout, cardHeight, stackStep, selected) {
                    val selectedId = selected ?: return@LaunchedEffect
                    if (settings.layout != CardLayout.STACK) return@LaunchedEffect
                    val target = visible.indexOfFirst { it.id == selectedId }
                    if (target < 0) return@LaunchedEffect
                    // Wait for a placement of this STACK geometry, never for the previous LIST one.
                    snapshotFlow { collectionPass.intValue }.first { it > 0 }
                    if (listState.layoutInfo.visibleItemsInfo.none { it.key == selectedId })
                        listState.scrollToItem(target)
                }
                LazyColumn(Modifier.fillMaxSize().testTag("wallet-list")
                    .onGloballyPositioned { collectionPass.intValue++ },
                    state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if ((settings.layout == CardLayout.STACK)) 0.dp else 18.dp)) {
                    itemsIndexed(visible, key = { _, c -> c.id }, contentType = { _, _ -> "card" }) { index, card ->
                        // Selection alone determines stack interaction, including the final/single card.
                        val expanded = !(settings.layout == CardLayout.STACK) || selected == card.id
                        val visibility = remember { BringIntoViewRequester() }
                        // One animatable drives both the layout advance and the painting, and it is read
                        // in the layout and draw phases only. The previous animateDpAsState read its
                        // value during composition, so every frame of an expand recomposed the artwork.
                        val fullAdvance = expanded || index == visible.lastIndex
                        val progress = remember { Animatable(if (fullAdvance) 1f else 0f) }
                        // Evidence published by a completed placement of the current STACK geometry.
                        // Recreating the state for a new layout mode, card size or stack step makes a
                        // previous full-height value unusable: no placement for the new geometry has
                        // happened yet, so null is the only value until onGloballyPositioned runs.
                        val placedAdvance = remember(settings.layout, cardHeight, stackStep) {
                            mutableStateOf<PlacedAdvance?>(null)
                        }
                        LaunchedEffect(fullAdvance, expanded, settings.animations, settings.layout, cardHeight, stackStep) {
                            val target = if (fullAdvance) 1f else 0f
                            if (!settings.animations) {
                                progress.snapTo(target)
                            } else {
                                // Critically damped: no overshoot, and a new target continues from the
                                // current on-screen value instead of restarting the animation.
                                progress.animateTo(target, WalletMotion.criticallyDamped(visibilityThreshold = .001f))
                            }
                            if (expanded && currentLayout == CardLayout.STACK) {
                                // The item's own advance is the height bringIntoView will measure. Wait
                                // for the placement pass that belongs to this composition geometry; a
                                // remembered full height from an earlier layout is deliberately gone.
                                val expandedAdvance = with(density) { cardHeight.roundToPx() }
                                snapshotFlow { placedAdvance.value }.first {
                                    it != null && it.pass > 0 &&
                                        it.layout == CardLayout.STACK &&
                                        it.cardHeight == cardHeight && it.stackStep == stackStep &&
                                        it.advance == expandedAdvance
                                }
                                // A newer selection/layout may have won while we waited.
                                if (currentSelected == card.id && currentLayout == CardLayout.STACK)
                                    visibility.bringIntoView()
                            }
                        }
                        // Paint the entire rounded card beyond the item's advance. Later cards cover it
                        // naturally. Only this item's visible advance accepts clicks; art has no pointer handler.
                        Box(Modifier.fillMaxWidth().bringIntoViewRequester(visibility)
                            .layout { measurable, constraints ->
                                // The layout height is exactly the painted advance, so the hit area can
                                // never drift away from what the user sees during the animation.
                                val advance = lerp(stackStep, cardHeight, progress.value).roundToPx()
                                val placeable = measurable.measure(constraints.copy(minHeight = advance, maxHeight = advance))
                                layout(placeable.width, advance) { placeable.place(0, 0) }
                            }
                            .onGloballyPositioned {
                                val previous = placedAdvance.value
                                placedAdvance.value = PlacedAdvance(
                                    pass = (previous?.pass ?: 0) + 1,
                                    advance = it.size.height,
                                    layout = settings.layout,
                                    cardHeight = cardHeight,
                                    stackStep = stackStep
                                )
                            }
                            .drawWithContent {
                                // Lower cards fully cover the preceding face after their top rounded corners.
                                // Retain that reveal, but avoid painting hidden artwork under the whole stack.
                                if ((settings.layout == CardLayout.STACK) && !expanded && index != visible.lastIndex)
                                    clipRect(bottom = size.height + (WalletDesign.cardRadius * ((maxWidth - 40.dp) / 353.dp)).toPx()) { this@drawWithContent.drawContent() }
                                else drawContent()
                            }
                            .testTag("wallet-card-${card.id}").semantics { this.selected = expanded }.clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (expanded) onOpen(card.id) else selected = card.id
                        }) {
                            Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(cardHeight)) {
                                CardFace(card, settings, vm.repository,
                                    elevation = if (expanded) WalletDesign.cardElevationSelected else WalletDesign.cardElevationRest)
                            }
                        }
                    }
                    item(key = "privacy-footer", contentType = "footer") {
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(Copy.offline, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        }
        HomeActionOverlay(onAdd = onAdd, onSettings = onSettings, animations = settings.animations)
    }
}
