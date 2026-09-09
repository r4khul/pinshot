package com.escapebranch.pinshot.ui.scrubber

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs

data class TimelineSection(
    val firstItemIndex: Int,
    val monthYearLabel: String,
    val itemCount: Int,
    val normalizedPosition: Float,
    val monthLabel: String = monthYearLabel,
    val yearLabel: String = ""
)

/**
 * Gesture and navigation state for [BallisticFastScrubber]. Pixel motion lives
 * in [thumbProgress], which is read from graphics layers instead of composition.
 */
@Stable
class TimelineScrubberState internal constructor() {
    private val scrollTargets = Channel<Int>(Channel.CONFLATED)
    private var sections: List<TimelineSection> = emptyList()
    private var trackHeightPx = 1f
    private var grabOffsetPx = 0f
    private var lastRequestedIndex = -1
    private var settlingJob: Job? = null
    private var widthAnimationJob: Job? = null
    private var dragProgress by mutableFloatStateOf(0f)

    val thumbProgress = Animatable(0f)
    val thumbWidthScale = Animatable(0.4f)

    var isScrubbing by mutableStateOf(false)
        private set
    var isDragging by mutableStateOf(false)
        private set
    var activeSectionIndex by mutableIntStateOf(0)
        private set

    val activeSection: TimelineSection?
        get() = sections.getOrNull(activeSectionIndex)

    fun updateSections(newSections: List<TimelineSection>) {
        sections = newSections
        activeSectionIndex = activeSectionIndex.coerceIn(0, (newSections.lastIndex).coerceAtLeast(0))
        lastRequestedIndex = -1
    }

    fun updateTrackHeight(heightPx: Int) {
        trackHeightPx = heightPx.coerceAtLeast(1).toFloat()
    }

    /**
     * Locks the visual handle under the finger on touch-down. Mapping the
     * absolute pointer position here would cause a visible jump before drag.
     */
    fun begin(pointerY: Float, firstVisibleItemIndex: Int): Boolean {
        if (sections.isEmpty()) return false
        settlingJob?.cancel()
        isScrubbing = true
        isDragging = true
        activeSectionIndex = resolveSectionForItem(firstVisibleItemIndex)
        dragProgress = passiveProgress(firstVisibleItemIndex)
        grabOffsetPx = pointerY - dragProgress * trackHeightPx
        lastRequestedIndex = activeSection?.firstItemIndex ?: -1
        return false
    }

    /** Returns true only when the selected timeline section changes. */
    fun drag(pointerY: Float): Boolean = updateFromPointer(pointerY)

    fun requestPassiveIndex(firstVisibleItemIndex: Int) {
        if (isScrubbing || sections.isEmpty()) return
        val sectionIndex = resolveSectionForItem(firstVisibleItemIndex)
        activeSectionIndex = sectionIndex
    }

    suspend fun awaitScrollTargets(onTarget: suspend (Int) -> Unit) {
        while (true) {
            var newestTarget = scrollTargets.receive()
            // Coalesce every section boundary crossed in this frame. This is
            // critical on 120Hz displays where pointer samples outnumber the
            // grid's safe measure/layout budget.
            withFrameNanos { }
            while (true) {
                newestTarget = scrollTargets.tryReceive().getOrNull() ?: break
            }
            onTarget(newestTarget)
        }
    }

    fun release(scope: CoroutineScope, velocityY: Float) {
        if (!isScrubbing) return
        val velocity = (velocityY / trackHeightPx).coerceIn(-3f, 3f)
        settlingJob?.cancel()
        isDragging = false
        settlingJob = scope.launch {
            thumbProgress.snapTo(dragProgress)
            // A bounded velocity projection prevents an edge fling from
            // overshooting while retaining the release direction and weight.
            selectProgress((dragProgress + velocity * 0.12f).coerceIn(0f, 1f))
            val targetSection = activeSection ?: return@launch
            thumbProgress.animateTo(
                targetValue = targetSection.normalizedPosition,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            isScrubbing = false
        }
    }

    fun animateThumbWidth(scope: CoroutineScope, grabbed: Boolean) {
        widthAnimationJob?.cancel()
        widthAnimationJob = scope.launch {
            thumbWidthScale.animateTo(
                targetValue = if (grabbed) 0.82f else 0.4f,
                animationSpec = tween(
                    durationMillis = 140,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    /** Layer-only passive rendering position; does not write Compose state. */
    fun passiveProgress(firstVisibleItemIndex: Int): Float {
        if (sections.isEmpty()) return 0f
        val current = resolveSectionForItem(firstVisibleItemIndex)
        val next = sections.getOrNull(current + 1)
        val section = sections[current]
        if (next == null || next.firstItemIndex == section.firstItemIndex) return section.normalizedPosition
        val fraction = ((firstVisibleItemIndex - section.firstItemIndex).toFloat() /
            (next.firstItemIndex - section.firstItemIndex)).coerceIn(0f, 1f)
        return section.normalizedPosition + (next.normalizedPosition - section.normalizedPosition) * fraction
    }

    fun renderProgress(firstVisibleItemIndex: Int): Float = when {
        isDragging -> dragProgress
        isScrubbing -> thumbProgress.value
        else -> passiveProgress(firstVisibleItemIndex)
    }

    fun translationY(progress: Float, handleHeightPx: Float): Float =
        progress * (trackHeightPx - handleHeightPx).coerceAtLeast(0f)

    private fun updateFromPointer(pointerY: Float): Boolean {
        val progress = ((pointerY - grabOffsetPx) / trackHeightPx).coerceIn(0f, 1f)
        dragProgress = progress
        return selectProgress(progress)
    }

    private fun selectProgress(progress: Float): Boolean {
        val previous = activeSectionIndex
        activeSectionIndex = resolveSectionForProgress(progress)
        val section = activeSection ?: return false
        if (section.firstItemIndex != lastRequestedIndex) {
            lastRequestedIndex = section.firstItemIndex
            scrollTargets.trySend(section.firstItemIndex)
        }
        return previous != activeSectionIndex
    }

    private fun resolveSectionForItem(itemIndex: Int): Int {
        var low = 0
        var high = sections.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (sections[mid].firstItemIndex <= itemIndex) low = mid + 1 else high = mid - 1
        }
        return high.coerceIn(0, sections.lastIndex)
    }

    /** Nearest-anchor binary search. No grid or layout inspection occurs here. */
    private fun resolveSectionForProgress(progress: Float): Int {
        var low = 0
        var high = sections.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (sections[mid].normalizedPosition < progress) low = mid + 1 else high = mid - 1
        }
        val upper = low.coerceIn(0, sections.lastIndex)
        val lower = (low - 1).coerceIn(0, sections.lastIndex)
        return if (abs(sections[upper].normalizedPosition - progress) < abs(sections[lower].normalizedPosition - progress)) {
            upper
        } else {
            lower
        }
    }
}
