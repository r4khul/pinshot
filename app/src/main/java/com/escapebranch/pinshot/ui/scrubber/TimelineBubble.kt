package com.escapebranch.pinshot.ui.scrubber

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A section-level marker; its text changes only when binary search changes month. */
@Composable
fun TimelineBubble(
    section: TimelineSection?,
    visible: Boolean,
    state: TimelineScrubberState,
    gridState: LazyGridState,
    handleHeightPx: Float,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val textWidth = (width - 24.dp).coerceAtLeast(0.dp)
    AnimatedVisibility(
        visible = visible && section != null,
        enter = fadeIn() + scaleIn(
            initialScale = 0.7f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ),
        exit = fadeOut(),
        modifier = modifier.graphicsLayer {
            // Position is a layer translation. It does not relayout the grid.
            translationY = state.translationY(
                state.renderProgress(gridState.firstVisibleItemIndex),
                handleHeightPx
            )
        }
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            modifier = Modifier.width(width)
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = section?.monthLabel.orEmpty(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(textWidth)
                )
                Text(
                    text = "${section?.yearLabel.orEmpty()} · ${section?.itemCount ?: 0}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(textWidth)
                )
            }
        }
    }
}
