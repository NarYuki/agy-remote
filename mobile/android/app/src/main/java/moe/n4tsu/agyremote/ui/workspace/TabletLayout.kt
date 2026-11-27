package moe.n4tsu.agyremote.ui.workspace

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Layout numbers for wide windows: tablets, unfolded foldables, desktop windows. */
object TabletMetrics {
    /** The pinned sidebar column. */
    val SIDEBAR: Dp = 320.dp
    /** Widest the transcript and composer grow before they centre. */
    val READABLE: Dp = 720.dp
    /**
     * Window width from which the sidebar stays pinned. Expanded starts at 840 dp; the upper half
     * of Medium is included so an unfolded book-style foldable (about 790 dp) gets it too.
     */
    val PIN_FROM: Dp = 720.dp
}

/** True when the sidebar sits beside the chat instead of sliding over it. */
val LocalSidebarPinned = compositionLocalOf { false }

/** Fills the width up to the readable cap; centre it with the parent's alignment. */
fun Modifier.readableWidth(): Modifier = widthIn(max = TabletMetrics.READABLE).fillMaxWidth()
