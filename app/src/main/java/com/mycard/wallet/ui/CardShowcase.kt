package com.mycard.wallet.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mycard.wallet.data.*
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.ui.theme.WalletMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** Native lazy paging; only the current page is actionable. All artwork stays front facing. */
@Composable
internal fun CardShowcase(cards: List<WalletCard>, settings: WalletSettings, repository: CardRepository?,
                          currentId: String?, onCurrent: (String) -> Unit, onOpen: (String) -> Unit) {
    val ids = remember(cards) { cards.map { it.id } }
    val pager = rememberPagerState(initialPage = ids.indexOf(currentId).coerceAtLeast(0), pageCount = { cards.size })
    val currentCallback by rememberUpdatedState(onCurrent)
    val scope = rememberCoroutineScope()
    // Stable keys preserve identity when reordering. Explicitly handle filtering/deleting the current card.
    LaunchedEffect(ids) {
        val target = ids.indexOf(currentId).takeIf { it >= 0 } ?: pager.currentPage.coerceIn(0, ids.lastIndex)
        if (target != pager.currentPage) pager.scrollToPage(target)
        currentCallback(ids[target])
    }
    LaunchedEffect(pager, ids) {
        snapshotFlow { if (pager.isScrollInProgress) null else ids.getOrNull(pager.currentPage) }
            .filterNotNull().distinctUntilChanged().collect { currentCallback(it) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = 20.dp
        val width = minOf(maxWidth - side * 2, 560.dp, (maxHeight - 160.dp).coerceAtLeast(135.dp) * CARD_RATIO)
        val inset = (maxWidth - width) / 2
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            HorizontalPager(pager, Modifier.fillMaxWidth().height(width / CARD_RATIO + 40.dp).testTag("wallet-pager"),
                contentPadding = PaddingValues(horizontal = inset), pageSpacing = 12.dp,
                beyondViewportPageCount = 1, key = { index -> cards.getOrNull(index)?.id ?: "wallet-page-$index" }, verticalAlignment = Alignment.CenterVertically,
                flingBehavior = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(1))) { page ->
                val card = cards[page]
                val current = page == pager.currentPage
                Box(Modifier.fillMaxWidth().zIndex(if(current) 1f else 0f)
                    // The tilt only exists while a page is actually moving. Skipping the layer at rest
                    // keeps the centred card - the one the user is looking at - on the direct draw path
                    // instead of an offscreen buffer that is rotated by zero degrees.
                    .then(if (settings.animations && pager.isScrollInProgress) Modifier.graphicsLayer {
                        // Read offsets in the draw phase, avoiding a recomposition on every animation frame.
                        val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f,1f)
                        val distance = offset.absoluteValue
                        scaleX = 1f - distance * .05f
                        scaleY = scaleX
                        rotationY = -offset * 14f
                        cameraDistance = 16f * density
                    } else Modifier)
                    .testTag("wallet-card-${card.id}").semantics { selected = current }
                    .clickable(enabled = current && !pager.isScrollInProgress) { onOpen(card.id) }) {
                    CardFace(card, settings, repository)
                }
            }
            val page = pager.currentPage.coerceIn(0, cards.lastIndex)
            Text(cards[page].title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(Copy.pagerHint, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                fun move(delta: Int) { scope.launch {
                    val target = (pager.currentPage + delta).coerceIn(0,cards.lastIndex)
                    if(settings.animations) pager.animateScrollToPage(target, animationSpec = tween(WalletMotion.page, easing = WalletMotion.easing)) else pager.scrollToPage(target)
                } }
                OutlinedIconButton({ move(-1) }, Modifier.size(WalletDesign.touch), enabled = page > 0 && !pager.isScrollInProgress) {
                    Icon(Icons.Rounded.ChevronLeft, Copy.previousCard)
                }
                Text("${page + 1} / ${cards.size}", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge)
                OutlinedIconButton({ move(1) }, Modifier.size(WalletDesign.touch), enabled = page < cards.lastIndex && !pager.isScrollInProgress) {
                    Icon(Icons.Rounded.ChevronRight, Copy.nextCard)
                }
            }
            Text(if(cards.size == 1) "仅一张卡片" else if(page == 0) Copy.firstCard else if(page == cards.lastIndex) Copy.lastCard else "",
                Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Copy.offline, Modifier.padding(bottom = 24.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
