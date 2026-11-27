package moe.n4tsu.agyremote.ui.chat

import moe.n4tsu.agyremote.ui.components.LiquidSegmented
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.n4tsu.agyremote.net.Mode
import kotlin.math.roundToInt
import moe.n4tsu.agyremote.state.WorkspaceViewModel
import moe.n4tsu.agyremote.ui.components.glass
import moe.n4tsu.agyremote.ui.components.plainClickable
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import moe.n4tsu.agyremote.ui.theme.Motion
import moe.n4tsu.agyremote.ui.workspace.modeIcon
import moe.n4tsu.agyremote.ui.workspace.glyph
import moe.n4tsu.agyremote.ui.workspace.stackLabel

/**
 * Empty chat (spec 5.1): the current project and up to four recent chats, just above the
 * composer. Hidden while the draft has text. The ambient backdrop is drawn behind this.
 */
@Composable
fun ZeroStateView(vm: WorkspaceViewModel, draftEmpty: Boolean, onProject: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    // iOS viewModel.currentFolder: the folder entry for the next new chat (falls back to the first one).
    val folder = vm.currentFolderEntry
    val folderPath = folder?.path
    val recent = remember(vm.chats.toList(), folderPath) {
        val inFolder = vm.chats.filter { it.folder == folderPath }
        (if (folderPath != null && inFolder.isNotEmpty()) inFolder else vm.chats).take(4)
    }
    val entered = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entered.animateTo(1f, Motion.spring(0.52f, 0.92f)) }
    AnimatedVisibility(
        draftEmpty, modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 40 },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 40 },
    ) {
        Column(Modifier.padding(start = 34.dp, end = 34.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (folder != null) {
                val name = folder.name.ifEmpty { folder.path.substringAfterLast('/') }
                val kind = folder.kind
                Row(
                    Modifier.graphicsLayer { alpha = entered.value }.plainClickable(onClick = onProject),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(folder.glyph(), null, Modifier.size(14.dp), tint = glyphTint(kind))
                    Text(name, style = AppType.sans(14.5f, FontWeight.Medium, c.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val label = folder.stackLabel
                    if (!label.isNullOrEmpty()) {
                        Text(label, style = AppType.sans(11f, FontWeight.Medium, c.onSurfaceMuted),
                            modifier = Modifier.background(c.surfaceContainerHigh, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            recent.forEach { chat ->
                val interaction = remember { MutableInteractionSource() }
                val down by interaction.collectIsPressedAsState()
                val press by animateFloatAsState(if (down) 1f else 0f, tween(140), label = "press")
                Row(
                    Modifier.graphicsLayer {
                        val e = entered.value
                        alpha = e * (1f - 0.45f * press)
                        val s = (0.96f + 0.04f * e) * (1f - 0.015f * press)
                        scaleX = s; scaleY = s
                        transformOrigin = TransformOrigin(0f, 1f)
                        translationY = (1f - e) * 10.dp.toPx()
                    }.clickable(interaction, null, role = Role.Button) { vm.openChat(chat.id) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(Icons.Outlined.History, null, Modifier.size(20.dp), tint = c.onSurface.copy(alpha = 0.86f))
                    Text(chat.title, style = AppType.sans(16.5f, color = c.onSurface.copy(alpha = 0.92f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Tint for a project's glyph, by the same kind that picks the glyph. */
@Composable
private fun glyphTint(kind: String?): Color {
    val c = LocalAppColors.current
    return when (kind?.lowercase()) {
        "swift" -> Color(0.94f, 0.32f, 0.22f)
        "go" -> moe.n4tsu.agyremote.ui.theme.Brand.violet
        "rust" -> c.warningAmber
        "python" -> c.connectedGreen
        "node" -> moe.n4tsu.agyremote.ui.theme.Brand.sky
        else -> c.primary
    }
}

/**
 * Default / Accept edits / Auto / Plan / Turbo in one glass pill. The selected mode sits on a dark
 * thumb (the same in both themes) that slides between chips and shows the mode's symbol; Turbo's
 * symbol is amber since it skips approvals.
 */
@Composable
fun ModePill(modes: List<Mode>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    // iOS: tap only (no drag, no stretch). The thumb is a matched-geometry slide: it moves from where
    // it was to the selected chip's live bounds on the .pill spring (0.28 s, 0.84), while the chips
    // resize on the same spring. Everything runs in the layout phase, so no per-frame recomposition.
    val selectedIndex = modes.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val progress = remember { Animatable(1f) }
    val from = remember { floatArrayOf(Float.NaN, 0f, 0f, 0f) } // start x, start w, last x, last w
    var shown by remember { mutableStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) {
        if (shown == selectedIndex) return@LaunchedEffect
        from[0] = from[2]; from[1] = from[3]
        shown = selectedIndex
        progress.snapTo(0f)
        progress.animateTo(1f, Motion.segmented())
    }
    val thumbFill = Color.Black.copy(alpha = 0.86f)
    Layout(
        modifier = modifier.glass(CircleShape).padding(3.dp),
        content = {
            Box(Modifier.background(thumbFill, CircleShape))
            modes.forEach { m ->
                val on = m.id == selected
                // Read in layout / draw only, so the slide doesn't recompose the chips every frame.
                val pad = animateDpAsState(if (on) 10.5.dp else 8.5.dp, Motion.segmented(), label = "pad")
                val shownIcon = androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, Motion.segmented(), label = "icon")
                val ink = androidx.compose.animation.animateColorAsState(if (on) Color.White else c.onSurface.copy(alpha = 0.84f), Motion.segmented(), label = "ink")
                Row(
                    Modifier.height(28.dp).plainClickable { if (!on) onSelect(m.id) }
                        .layout { measurable, constraints ->
                            val px = pad.value.roundToPx()
                            val p = measurable.measure(constraints.offset(-2 * px, 0))
                            layout(p.width + 2 * px, p.height) { p.placeRelative(px, 0) }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The symbol shows only on the selected mode, fading in as the chip widens. It stays
                    // composed (alpha 0, zero width) so a switch never composes new content mid-slide.
                    Icon(
                        modeIcon(m.id), null,
                        Modifier
                            .layout { measurable, constraints ->
                                val f = shownIcon.value
                                val p = measurable.measure(constraints)
                                val w = ((p.width + 4.dp.roundToPx()) * f).roundToInt()
                                layout(w, p.height) { p.placeRelative(0, 0) }
                            }
                            .graphicsLayer { alpha = shownIcon.value; clip = false }
                            .size(11.dp),
                        tint = if (m.id == "turbo") c.warningAmber else Color.White,
                    )
                    androidx.compose.foundation.text.BasicText(m.name, style = AppType.sans(12.4f, if (on) FontWeight.Medium else FontWeight.Normal), maxLines = 1, softWrap = false, color = { ink.value })
                }
            }
        },
    ) { measurables, _ ->
        val gap = 1.5.dp.roundToPx()
        val chips = measurables.drop(1).map { it.measure(androidx.compose.ui.unit.Constraints()) }
        val xs = IntArray(chips.size)
        var x = 0
        chips.forEachIndexed { i, p -> xs[i] = x; x += p.width + (if (i < chips.lastIndex) gap else 0) }
        val h = chips.maxOfOrNull { it.height } ?: 0
        val sel = shown.coerceIn(0, (chips.size - 1).coerceAtLeast(0))
        val tx = xs.getOrElse(sel) { 0 }.toFloat(); val tw = chips.getOrNull(sel)?.width?.toFloat() ?: 0f
        val t = progress.value
        val (cx, cw) = if (from[0].isNaN()) tx to tw else (from[0] + (tx - from[0]) * t) to (from[1] + (tw - from[1]) * t)
        from[2] = cx; from[3] = cw
        val thumb = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(cw.toInt().coerceAtLeast(0), h))
        layout(x, h) {
            thumb.placeRelative(cx.toInt(), 0)
            chips.forEachIndexed { i, p -> p.placeRelative(xs[i], 0) }
        }
    }
}
