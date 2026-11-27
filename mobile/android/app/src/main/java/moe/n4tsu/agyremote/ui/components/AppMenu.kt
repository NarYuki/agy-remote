package moe.n4tsu.agyremote.ui.components

import androidx.compose.ui.graphics.isSpecified
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import moe.n4tsu.agyremote.state.Haptics
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors

/** Builds an [AppMenu]'s rows. Not composable: the menu renders them itself. */
class AppMenuScope internal constructor() {
    internal sealed interface Entry
    internal class Item(
        val title: String, val icon: ImageVector?, val checked: Boolean?, val destructive: Boolean,
        val enabled: Boolean, val onClick: () -> Unit,
    ) : Entry
    internal class Section(val title: String?) : Entry

    internal val entries = mutableListOf<Entry>()

    /**
     * A row: title on the left, [icon] on the right. [checked] non-null makes it a checkable row
     * (a check mark in the leading column when true, as UIMenu does).
     */
    fun item(
        title: String, icon: ImageVector? = null, checked: Boolean? = null, destructive: Boolean = false,
        enabled: Boolean = true, onClick: () -> Unit,
    ) { entries += Item(title, icon, checked, destructive, enabled, onClick) }

    /** Starts a new section: a thin inset hairline, with an optional small grey header. */
    fun section(title: String? = null) { entries += Section(title) }
}

/** Where the platter sits relative to its anchor (the composable the menu is placed in). */
enum class AppMenuAlign { START, END }

/** One open menu, as handed to its [AppMenuHost]. */
internal class MenuRequest(
    val visible: MutableTransitionState<Boolean>,
    val anchor: Rect,
    val align: AppMenuAlign,
    val width: Dp,
    val entries: List<AppMenuScope.Entry>,
    val alignChecks: Boolean,
    val onDismiss: () -> Unit,
)

/** Holds the menu an [AppMenuHost] is showing. */
class AppMenuHostState internal constructor() {
    internal var request by mutableStateOf<MenuRequest?>(null)
    internal var origin by mutableStateOf(Offset.Zero)
}

val LocalAppMenuHost = staticCompositionLocalOf<AppMenuHostState?> { null }

/**
 * Draws [AppMenu]s in this tree, over [content], so the platter can be real glass: it refracts and
 * blurs the [LocalGlassBackdrop] installed above (put the host inside a `ProvideGlass`). Outside
 * taps and the back key dismiss the open menu.
 */
@Composable
fun AppMenuHost(modifier: Modifier = Modifier, base: Color = Color.Unspecified, content: @Composable () -> Unit) {
    val host = remember { AppMenuHostState() }
    // What lies under the recorded content (window / sheet background). The content itself is
    // mostly transparent, so the blurred copy needs this opaque base under it; otherwise the
    // sharp content shows straight through the platter.
    val menuBase = if (base.isSpecified) base else LocalAppColors.current.surface
    val outer = LocalGlassBackdrop.current
    // The menu gets its own backdrop: the host's content, recorded only while a menu is up, so the
    // platter refracts and blurs what is really behind it (the outer backdrop may be a plain surface).
    ProvideGlass(LocalHazeState.current) {
    val source = if (host.request != null) Modifier.glassSource() else Modifier
    Box(modifier.onGloballyPositioned { host.origin = it.positionInRoot() }) {
        Box(Modifier.matchParentSize().then(source)) {
            CompositionLocalProvider(LocalAppMenuHost provides host, LocalGlassBackdrop provides outer, content = content)
        }
        CompositionLocalProvider(LocalMenuBase provides menuBase) {
        val r = host.request
        if (r != null) {
            val showing = r.visible.targetState
            BackHandler(showing) { r.onDismiss() }
            if (showing) {
                // Transparent catcher: any touch outside the platter closes the menu and is eaten.
                Box(Modifier.matchParentSize().pointerInput(r) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        r.onDismiss()
                        do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                    }
                })
            }
            val local = r.anchor.translate(-host.origin)
            val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            var placement by remember(r) { mutableStateOf(Placement(above = false, fromEnd = true)) }
            Layout(content = { MenuPlatter(r, placement) }, modifier = Modifier.matchParentSize()) { measurables, constraints ->
                val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
                val end = (r.align == AppMenuAlign.END) != rtl
                var x = if (end) local.right.toInt() - p.width else local.left.toInt()
                x = x.coerceIn(margin, (constraints.maxWidth - p.width - margin).coerceAtLeast(margin))
                // iOS: the platter opens over its anchor row (top edges together); when it doesn't fit
                // below, it grows upward ending at the anchor's bottom.
                val down = local.top.toInt() - gap
                val above = down + p.height > constraints.maxHeight - margin && local.bottom.toInt() + gap - p.height >= margin
                val y = (if (above) local.bottom.toInt() + gap - p.height else down)
                    .coerceIn(margin, (constraints.maxHeight - p.height - margin).coerceAtLeast(margin))
                val pl = Placement(above, end)
                if (pl != placement) placement = pl
                layout(constraints.maxWidth, constraints.maxHeight) { p.place(x, y) }
            }
            // Drop the request once it has finished animating out.
            if (!r.visible.targetState && r.visible.isIdle && !r.visible.currentState) {
                LaunchedEffect(r) { if (host.request === r) host.request = null }
            }
        }
        }
    }
}
}

private val LocalMenuBase = staticCompositionLocalOf { Color.Black }

private data class Placement(val above: Boolean, val fromEnd: Boolean)

/**
 * An iOS UIMenu-style menu: a glass platter about 250 dp wide, 44 dp rows with 17 sp text, icons on
 * the right, check marks on the left, thick gaps between sections, red destructive rows. Springs in
 * (scale + fade) from the corner nearest its anchor, highlights on press and plays a haptic.
 *
 * Put it inside a `Box` together with the anchor, like a Material `DropdownMenu`. It renders in the
 * nearest [AppMenuHost] (as glass); without one it falls back to a popup.
 */
@Composable
fun AppMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    align: AppMenuAlign = AppMenuAlign.END,
    width: Dp = 250.dp,
    alignChecks: Boolean = false,
    content: AppMenuScope.() -> Unit,
) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded
    val context = LocalContext.current
    LaunchedEffect(expanded) { if (expanded) Haptics.light(context) }
    val host = LocalAppMenuHost.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    // Zero-size probe that reports its parent's bounds (the anchor Box).
    Box(Modifier.onGloballyPositioned { c -> (c.parentLayoutCoordinates ?: c).let { anchor = it.boundsInRoot() } })
    val latestDismiss by rememberUpdatedState(onDismiss)
    val entries = AppMenuScope().apply(content).entries

    if (host != null) {
        if (expanded) {
            val req = MenuRequest(visible, anchor, align, width, entries, alignChecks) { latestDismiss() }
            SideEffect { host.request = req }
        }
        return
    }
    if (!visible.currentState && !visible.targetState && visible.isIdle) return
    val r = MenuRequest(visible, anchor, align, width, entries, alignChecks) { latestDismiss() }
    val density = LocalDensity.current
    val position = remember(align, density) { MenuPosition(align, with(density) { 8.dp.roundToPx() }, with(density) { 6.dp.roundToPx() }) }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        MenuPlatter(r, Placement(position.above, position.fromEnd))
    }
}

@Composable
private fun MenuPlatter(r: MenuRequest, placement: Placement) {
    val t = rememberTransition(r.visible, label = "menu")
    val scale by t.animateFloat({ if (targetState) spring(0.78f, 520f) else tween(140) }, label = "scale") { if (it) 1f else 0.6f }
    val alpha by t.animateFloat({ if (targetState) tween(160) else tween(140) }, label = "alpha") { if (it) 1f else 0f }
    val c = LocalAppColors.current
    // iOS 26 menu: large corners, bright translucent glass, thin light rim, big soft shadow.
    val shape = RoundedCornerShape(26.dp)
    // Bright translucent material over a real blur (iOS): a light tint inside the glass plus a
    // frosted veil, together about 60 % opaque, so what's behind reads as soft colour only.
    val tint = if (c.isDark) Color(0xFF2A2B2E).copy(alpha = 0.30f) else Color.White.copy(alpha = 0.30f)
    val veil = if (c.isDark) Color(0xFF2A2B2E).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.45f)
    val rim = if (c.isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    val hairline = c.onSurface.copy(alpha = 0.12f)
    Column(
        Modifier
            .graphicsLayer {
                scaleX = scale; scaleY = scale; this.alpha = alpha
                transformOrigin = TransformOrigin(if (placement.fromEnd) 1f else 0f, if (placement.above) 1f else 0f)
            }
            .width(r.width).heightIn(max = 560.dp)
            .shadow(40.dp, shape, ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = if (c.isDark) 0.45f else 0.16f))
            .menuGlass(shape, tint)
            .background(veil, shape)
            .border(0.75.dp, rim, shape)
            .clip(shape)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        r.entries.forEachIndexed { i, e ->
            when (e) {
                is AppMenuScope.Section -> {
                    if (i > 0) Box(Modifier.padding(horizontal = 24.dp, vertical = 6.dp).fillMaxWidth().height(0.5.dp).background(hairline))
                    e.title?.let {
                        Text(it, style = AppType.sans(13f, color = c.onSurfaceMuted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 28.dp, end = 20.dp, top = 8.dp, bottom = 4.dp))
                    }
                }
                is AppMenuScope.Item -> MenuRow(e, r.alignChecks) { r.onDismiss(); e.onClick() }
            }
        }
    }
}

@Composable
private fun MenuRow(item: AppMenuScope.Item, alignChecks: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = when {
        !item.enabled -> c.onSurfaceMuted
        item.destructive -> c.errorRed
        else -> c.onSurface
    }
    Row(
        Modifier.fillMaxWidth().height(42.dp)
            .background(if (pressed) c.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(interaction, null, enabled = item.enabled, role = Role.Button, onClick = onClick)
            .padding(start = 28.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The check sits left of the selected label. A picker-style menu ([alignChecks]) keeps every
        // label in the checked label's column; otherwise only the checked row is pushed over.
        if (item.checked == true || (alignChecks && item.checked != null)) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                if (item.checked == true) Icon(Icons.Filled.Check, null, Modifier.size(19.dp), tint = fg)
            }
        }
        Text(item.title, style = AppType.sans(17f, color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (item.icon != null) Icon(item.icon, null, Modifier.padding(start = 12.dp).size(20.dp), tint = fg)
    }
}

/** Below the anchor (above when there's no room), aligned to its start or end edge, kept on screen. */
private class MenuPosition(val align: AppMenuAlign, private val margin: Int, private val gap: Int) : PopupPositionProvider {
    var above = false; private set
    var fromEnd = align == AppMenuAlign.END; private set

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val end = (align == AppMenuAlign.END) != (layoutDirection == LayoutDirection.Rtl)
        fromEnd = end
        var x = if (end) anchorBounds.right - popupContentSize.width else anchorBounds.left
        x = x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + gap
        above = below + popupContentSize.height > windowSize.height - margin && anchorBounds.top - gap - popupContentSize.height >= margin
        val y = if (above) anchorBounds.top - gap - popupContentSize.height
        else below.coerceAtMost((windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        return IntOffset(x, y)
    }
}

/**
 * iOS menu material: a heavy blur of what's behind (iOS menus blur far more than glass buttons),
 * a little vibrancy and lens at the rim, then [tint]. Falls back to [glass] where Liquid Glass
 * isn't available.
 */
@Composable
private fun Modifier.menuGlass(shape: RoundedCornerShape, tint: Color): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val c = LocalAppColors.current
    if (backdrop == null || !com.kyant.backdrop.isRuntimeShaderSupported() || android.os.Build.VERSION.SDK_INT < 33) {
        return this.glass(shape, elevation = 0.dp).background(tint, shape)
    }
    val highlight = com.kyant.backdrop.highlight.Highlight(width = 0.75.dp, alpha = if (c.isDark) 0.45f else 0.8f,
        style = com.kyant.backdrop.highlight.HighlightStyle.Default)
    val base = LocalMenuBase.current
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(26.dp.toPx())
        },
        highlight = { highlight },
        // The opaque base goes under the recorded content before the blur, like the real window.
        onDrawBackdrop = { drawBackdrop -> drawRect(base); drawBackdrop() },
        onDrawSurface = { drawRect(tint) },
    )
}
