package com.escapebranch.pinshot.ui.scrubber

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp

@Composable
fun rememberTimelineScrubberState(): TimelineScrubberState = remember { TimelineScrubberState() }

/**
 * A 48dp edge target with a render-layer-only thumb. Dataset updates create
 * [TimelineSection]s once; pointer motion resolves sections in O(log N).
 */
@Composable
fun BallisticFastScrubber(
    gridState: LazyGridState,
    sections: List<TimelineSection>,
    modifier: Modifier = Modifier,
    state: TimelineScrubberState = rememberTimelineScrubberState()
) {
    if (sections.size < 2) return

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val handleHeightPx = with(density) { 64.dp.toPx() }
    LaunchedEffect(sections) { state.updateSections(sections) }
    LaunchedEffect(state, gridState) {
        state.awaitScrollTargets { index -> gridState.scrollToItem(index) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(48.dp)
                .fillMaxHeight()
                .onSizeChanged { state.updateTrackHeight(it.height) }
                .pointerInput(state, sections) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val velocityTracker = VelocityTracker()
                        velocityTracker.resetTracking()
                        velocityTracker.addPosition(down.uptimeMillis, Offset(0f, down.position.y))
                        state.begin(down.position.y, gridState.firstVisibleItemIndex)
                        state.animateThumbWidth(scope, grabbed = true)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            velocityTracker.addPosition(change.uptimeMillis, Offset(0f, change.position.y))
                            if (state.drag(change.position.y)) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            change.consume()
                        }
                        state.release(scope, velocityTracker.calculateVelocity().y)
                        state.animateThumbWidth(scope, grabbed = false)
                    }
                },
            contentAlignment = Alignment.TopEnd
        ) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(64.dp)
                .graphicsLayer {
                    // Reads happen in the layer block, so passive scroll updates
                    // invalidate only rendering, not the grid composition.
                    val progress = state.renderProgress(gridState.firstVisibleItemIndex)
                    translationY = state.translationY(progress, handleHeightPx)
                    // The value is read in graphicsLayer, avoiding animation
                    // driven recomposition of the grid and bubble.
                    scaleX = state.thumbWidthScale.value
                    // Height stays constant. The only grab affordance is width,
                    // avoiding the visually noisy multi-state thumb transition.
                    scaleY = 0.75f
                    // The trailing edge remains fixed. Expansion happens
                    // inward, so the thumb never appears to slide or clip.
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                }
                .background(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(
                        alpha = if (state.isScrubbing) 1f else 0.82f
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
                )
        )
        }

        val bubbleWidth = minOf(116.dp, (maxWidth - 60.dp).coerceAtLeast(0.dp))
        TimelineBubble(
            section = state.activeSection,
            visible = state.isScrubbing,
            state = state,
            gridState = gridState,
            handleHeightPx = handleHeightPx,
            width = bubbleWidth,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 18.dp)
        )
    }
}
