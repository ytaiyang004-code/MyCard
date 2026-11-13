package com.mycard.wallet.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mycard.wallet.WalletViewModel
import com.mycard.wallet.data.*
import com.mycard.wallet.ui.theme.WalletMotion
import kotlinx.coroutines.launch

/**
 * A stalled frame (GC, backgrounding, a slow test run) must not turn into a visible jump, so the
 * elapsed time is capped at roughly two 60Hz frames.
 */
internal const val AUTO_SCROLL_MAX_FRAME_NANOS = 32_000_000L

/**
 * Distance to scroll for one frame: speed in pixels per second times the real elapsed time, signed by
 * [edgePull]. Using real time instead of a fixed per-frame amount keeps the drag speed the same on a
 * 60Hz and a 120Hz display.
 */
internal fun autoScrollStep(edgePull: Float, speedPerSecond: Float, elapsedNanos: Long): Float {
    if (edgePull == 0f || speedPerSecond <= 0f || elapsedNanos <= 0L) return 0f
    val seconds = elapsedNanos.coerceAtMost(AUTO_SCROLL_MAX_FRAME_NANOS) / 1_000_000_000f
    return edgePull * speedPerSecond * seconds
}

@Composable
fun ReorderScreen(vm: WalletViewModel, all: List<WalletCard>, onBack: () -> Unit) {
    var cards by remember(all) { mutableStateOf(CardRules.select(all, "", CardFilter.ALL, SortOrder.CUSTOM)) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    // Signed pull towards a viewport edge, -1..1. The auto-scroll rate follows how far the dragged
    // row has been pushed past the edge instead of running at one fixed speed.
    var edgePull by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val rowHeight = with(density) { 80.dp.toPx() }
    // Auto-scroll speed is dp per second, not dp per frame, so the same drag feels the same on a
    // 60Hz and a 120Hz screen. 240dp/s is the gentle end, 1440dp/s the fastest.
    val minScrollSpeed = with(density) { 240.dp.toPx() }
    val maxScrollSpeed = with(density) { 1440.dp.toPx() }
    val list = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestCards by rememberUpdatedState(cards)
    val settle = WalletMotion.criticallyDamped<Float>(stiffness = WalletMotion.stiffnessGentle, visibilityThreshold = .5f)
    LaunchedEffect(dragging, edgePull) {
        if (dragging == null || edgePull == 0f) return@LaunchedEffect
        val intensity = kotlin.math.abs(edgePull)
        val speedPerSecond = minScrollSpeed + (maxScrollSpeed - minScrollSpeed) * intensity
        var previous = 0L
        while (true) {
            val frame = withFrameNanos { it }
            val elapsed = if (previous == 0L) 0L else frame - previous
            previous = frame
            list.scrollBy(autoScrollStep(edgePull, speedPerSecond, elapsed))
        }
    }
    Scaffold(topBar = { PageTopBar(Copy.reorder, onBack) },
        bottomBar = {
            Button({
                vm.reorder(cards.map { it.id }, onBack)
            }, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("保存顺序") }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Text(Copy.reorderHint, Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(cards, key = { _, card -> card.id }) { _, card ->
                    val active = dragging == card.id
                    Surface(Modifier.fillMaxWidth().height(80.dp).animateItem()
                        .zIndex(if (active) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (active) offset else 0f
                            // A slight lift, no tilt and no heavy shadow: the row reads as picked up
                            // without becoming a floating 3D object.
                            val lift = if (active) 1.02f else 1f
                            scaleX = lift
                            scaleY = lift
                        }.padding(vertical = 4.dp),
                        shape = MaterialTheme.shapes.medium, tonalElevation = if (active) 8.dp else 2.dp) {
                        Row(Modifier.fillMaxSize().padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(card.title, maxLines = 1)
                                Text((if (card.pinned) "置顶 · " else "") + card.network.label + " · " + card.lastFour,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.DragHandle, Copy.reorder,
                                Modifier.size(56.dp).padding(12.dp).pointerInput(card.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragging = card.id; offset = 0f; edgePull = 0f; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                        onDragCancel = { dragging = null; offset = 0f; edgePull = 0f },
                                        onDragEnd = {
                                            // Let the row travel into its slot instead of teleporting there.
                                            scope.launch {
                                                animate(offset, 0f, animationSpec = settle) { value, _ -> offset = value }
                                                offset = 0f; dragging = null; edgePull = 0f
                                            }
                                        },
                                        onDrag = { change, delta ->
                                            change.consume(); offset += delta.y
                                            val current = latestCards
                                            val from = current.indexOfFirst { it.id == card.id }
                                            val step = if (offset > rowHeight / 2) 1 else if (offset < -rowHeight / 2) -1 else 0
                                            val to = from + step
                                            if (step != 0 && to in current.indices && current[to].pinned == card.pinned) {
                                                cards = current.toMutableList().apply { add(to, removeAt(from)) }
                                                offset -= step * rowHeight
                                                // One restrained tick per swap; never per frame.
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            val info = list.layoutInfo.visibleItemsInfo.find { it.key == card.id }
                                            val y = (info?.offset ?: 0) + offset
                                            val edge = rowHeight * 1.5f
                                            val viewportEnd = list.layoutInfo.viewportEndOffset
                                            edgePull = when {
                                                y < edge -> -((edge - y) / edge)
                                                y > viewportEnd - edge -> (y - (viewportEnd - edge)) / edge
                                                else -> 0f
                                            }.coerceIn(-1f, 1f)
                                        })
                                })
                        }
                    }
                }
            }
        }
    }
}
