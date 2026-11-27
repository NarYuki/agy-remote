package moe.n4tsu.agyremote.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import moe.n4tsu.agyremote.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import moe.n4tsu.agyremote.ui.components.liquid.LiquidButton
import moe.n4tsu.agyremote.ui.components.liquid.LiquidBottomTab
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The haze source of the screen behind floating glass controls (the chat area). Fallback path. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * The Liquid Glass backdrop (io.github.kyant0:backdrop) recorded from the content behind the
 * floating controls. Install it with [ProvideGlass] and mark the content with [glassSource].
 * Null means "use the fallback look".
 */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Liquid glass needs RuntimeShader (API 33) for the lens. */
private val liquidGlassSupported = com.kyant.backdrop.isRuntimeShaderSupported() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Provides both the haze state (fallback) and a Liquid Glass backdrop to [content]. */
@Composable
fun ProvideGlass(haze: HazeState?, content: @Composable () -> Unit) {
    val backdrop = if (liquidGlassSupported) rememberLayerBackdrop() else null
    CompositionLocalProvider(LocalHazeState provides haze, LocalGlassBackdrop provides backdrop, content = content)
}

/** Records this content as the backdrop glass refracts. Put it on the layer behind the controls. */
@Composable
fun Modifier.glassSource(): Modifier {
    val b = LocalGlassBackdrop.current ?: return this
    return this.layerBackdrop(b)
}

/**
 * Glass material (spec "Glass"). On API 33+ with a [LocalGlassBackdrop]: Liquid Glass with lens
 * refraction, blur, a rim highlight, an inner and outer shadow and, given [interaction], an iOS
 * interactive press (grows a little and brightens). Otherwise a blurred translucent surface with a
 * hairline border (haze when a [LocalHazeState] is present).
 * Press state is read only inside draw/layer lambdas, so pressing never recomposes the content.
 */
@Composable
fun Modifier.glass(shape: Shape, elevation: Dp = 10.dp, interaction: MutableInteractionSource? = null): Modifier {
    val c = LocalAppColors.current
    val backdrop = LocalGlassBackdrop.current
    if (backdrop != null && liquidGlassSupported && shape is CornerBasedShape) {
        return liquidGlass(backdrop, shape, c.isDark, interaction)
    }
    val haze = LocalHazeState.current
    val tint = if (c.isDark) Color(0xFF1B2023).copy(alpha = 0.62f) else Color.White.copy(alpha = 0.66f)
    val border = if (c.isDark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.06f)
    var m = this
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = if (c.isDark) 0.5f else 0.10f),
            spotColor = Color.Black.copy(alpha = if (c.isDark) 0.5f else 0.12f))
        .clip(shape)
    m = if (haze != null) {
        m.hazeEffect(haze, HazeStyle(backgroundColor = c.surface, tints = listOf(HazeTint(tint)), blurRadius = 22.dp, noiseFactor = 0f))
    } else {
        m.background(if (c.isDark) Color(0xFF1B2023).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.94f))
    }
    return m.border(0.75.dp, border, shape)
}

@Composable
private fun Modifier.liquidGlass(backdrop: LayerBackdrop, shape: Shape, dark: Boolean, interaction: MutableInteractionSource?): Modifier {
    val press = remember { Animatable(0f) }
    if (interaction != null) {
        LaunchedEffect(interaction) {
            var count = 0
            interaction.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> count++
                    is PressInteraction.Release, is PressInteraction.Cancel -> count = (count - 1).coerceAtLeast(0)
                }
                launch { press.animateTo(if (count > 0) 1f else 0f, Motion.listPress()) }
            }
        }
    }
    val tint = if (dark) Color(0xFF1B2023).copy(alpha = 0.34f) else Color.White.copy(alpha = 0.38f)
    val pressTint = if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.22f)
    val shadow = Shadow(radius = 14.dp, color = Color.Black.copy(alpha = if (dark) 0.42f else 0.10f))
    val inner = InnerShadow(radius = 6.dp, color = Color.Black.copy(alpha = if (dark) 0.10f else 0.05f))
    val highlight = Highlight(width = 0.75.dp, alpha = if (dark) 0.55f else 0.85f, style = HighlightStyle.Default)
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(5.dp.toPx())
            lens(refractionHeight = 10.dp.toPx(), refractionAmount = 16.dp.toPx(), depthEffect = true)
        },
        highlight = { highlight.copy(alpha = highlight.alpha * (1f + 0.4f * press.value).coerceAtMost(1f)) },
        shadow = { shadow },
        innerShadow = { inner },
        layerBlock = {
            val s = 1f + 0.06f * press.value
            scaleX = s; scaleY = s
        },
        onDrawSurface = {
            drawRect(tint)
            val p = press.value
            if (p > 0.001f) drawRect(pressTint, alpha = p)
        },
    )
}

/** The glass backdrop to hand to the liquid components: the installed source, or an empty one. */
@Composable
fun rememberGlassBackdrop(): Backdrop = LocalGlassBackdrop.current ?: remember { emptyBackdrop() }

/** The app's switch: the catalog [moe.n4tsu.agyremote.ui.components.liquid.LiquidToggle] (thumb stretches while dragged). */
@Composable
fun AppToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val current by androidx.compose.runtime.rememberUpdatedState(checked)
    moe.n4tsu.agyremote.ui.components.liquid.LiquidToggle(
        selected = { current }, onSelect = { onCheckedChange(it) },
        backdrop = rememberGlassBackdrop(), modifier = modifier.padding(start = 12.dp).size(64.dp, 44.dp), // 44 dp tall hit area
    )
}

/**
 * A round glass button (header buttons are 42, sidebar buttons 44): the catalog [LiquidButton]
 * with its press swell and jelly drag-stretch.
 */
@Composable
fun GlassCircleButton(
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val c = LocalAppColors.current
    LiquidButton(
        onClick = onClick,
        backdrop = rememberGlassBackdrop(),
        modifier = modifier,
        shape = CircleShape,
        width = size, height = size, horizontalPadding = 0.dp,
        surfaceColor = if (c.isDark) Color(0xFF1B2023).copy(alpha = 0.34f) else Color.White.copy(alpha = 0.38f),
        contentDescription = contentDescription,
    ) { content() }
}

private val SmoothEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/**
 * Melt (spec Motion): opacity 0 → 1, blur 8 → 0 and scale 0.94 → 1 over 0.5 s on a smooth curve.
 * Nothing small ever pops in or out.
 */
@Composable
fun Melt(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(Motion.MELT_MS, easing = SmoothEasing)) + scaleIn(tween(Motion.MELT_MS, easing = SmoothEasing), 0.94f),
        exit = fadeOut(tween(Motion.MELT_MS, easing = SmoothEasing)) + scaleOut(tween(Motion.MELT_MS, easing = SmoothEasing), 0.94f),
    ) {
        val blur by transition.animateFloat({ tween(Motion.MELT_MS, easing = SmoothEasing) }, label = "meltBlur") {
            if (it == EnterExitState.Visible) 0f else 8f
        }
        Box(Modifier.graphicsLayer {
            val px = with(density) { blur.dp.toPx() }
            renderEffect = if (px > 0.5f) BlurEffect(px, px, TileMode.Decal) else null
        }) { content() }
    }
}

/** Plain clickable without ripple (the app's controls press by scaling or dimming instead). */
@Composable
fun Modifier.plainClickable(role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interaction, indication = null, role = role, onClick = onClick)
}

/**
 * The catalog LiquidButton's press and jelly-drag transform for elements that are not glass
 * themselves (icons inside the composer, filled pills). Read only in the layer block.
 */
@Composable
fun Modifier.liquidSquish(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val h = remember(scope) { moe.n4tsu.agyremote.ui.components.liquid.InteractiveHighlight(scope) }
    return this
        .graphicsLayer {
            val w = size.width; val ht = size.height
            if (w <= 0f || ht <= 0f) return@graphicsLayer
            val scale = 1f + (4.dp.toPx() / ht) * h.pressProgress
            val maxOffset = size.minDimension
            val o = h.offset
            translationX = maxOffset * kotlin.math.tanh(0.05f * o.x / maxOffset)
            translationY = maxOffset * kotlin.math.tanh(0.05f * o.y / maxOffset)
            val maxDrag = 4.dp.toPx() / ht
            val a = kotlin.math.atan2(o.y, o.x)
            scaleX = scale + maxDrag * kotlin.math.abs(kotlin.math.cos(a) * o.x / size.maxDimension) * (w / ht).coerceAtMost(1f)
            scaleY = scale + maxDrag * kotlin.math.abs(kotlin.math.sin(a) * o.y / size.maxDimension) * (ht / w).coerceAtMost(1f)
        }
        .keepGesture()
        .then(h.gestureModifier)
}

/**
 * Segmented control / mode pill built on the catalog LiquidBottomTabs: equal-width items, a
 * glass thumb you can drag (it swells into a lens and stretches with its speed) or tap to move.
 */
@Composable
fun LiquidSegmented(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    inset: Dp = 3.dp,
    item: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    if (count <= 0) return
    val c = LocalAppColors.current
    val current by androidx.compose.runtime.rememberUpdatedState(selected.coerceIn(0, count - 1))
    val pick by androidx.compose.runtime.rememberUpdatedState(onSelect)
    moe.n4tsu.agyremote.ui.components.liquid.LiquidBottomTabs(
        selectedTabIndex = { current },
        onTabSelected = { if (it != current) pick(it) },
        backdrop = rememberGlassBackdrop(),
        tabsCount = count,
        modifier = modifier,
        height = height,
        inset = inset,
        accentColor = c.onSurface,
        thumbColor = c.onSurface.copy(alpha = if (c.isDark) 0.16f else 0.10f),
    ) {
        for (i in 0 until count) {
            LiquidBottomTab(onClick = { if (i != current) pick(i) }) { item(i, i == current) }
        }
    }
}

/**
 * The mode pill / chips: a glass capsule whose items hug their labels, with a solid thumb drawn
 * *under* the labels (so it can be opaque, like iOS). The thumb is driven by the catalog's
 * [moe.n4tsu.agyremote.ui.components.liquid.DampedDragAnimation]: drag anywhere on the pill to slide
 * it (it swells and stretches with its speed) and it snaps to the nearest item; tap an item to
 * pick it. All motion is read in draw, so nothing recomposes per frame.
 */
@Composable
fun LiquidPill(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    thumbColor: Color,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 28.dp,
    inset: Dp = 3.dp,
    spacing: Dp = 0.dp,
    item: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    if (count <= 0) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val xs = remember(count) { FloatArray(count) }
    val ws = remember(count) { FloatArray(count) }
    val measured = remember(count) { androidx.compose.runtime.mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, count - 1)
    val current by androidx.compose.runtime.rememberUpdatedState(sel)
    val pick by androidx.compose.runtime.rememberUpdatedState(onSelect)
    val dragged = remember { booleanArrayOf(false) }
    fun widthAt(v: Float): Float {
        val i = v.toInt().coerceIn(0, count - 1); val j = (i + 1).coerceAtMost(count - 1); val t = v - i
        return ws[i] + (ws[j] - ws[i]) * t
    }
    val anim = remember(scope, count) {
        moe.n4tsu.agyremote.ui.components.liquid.DampedDragAnimation(
            animationScope = scope,
            initialValue = sel.toFloat(),
            valueRange = 0f..(count - 1).toFloat().coerceAtLeast(0.0001f),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.12f,
            onDragStarted = { dragged[0] = false },
            onDragStopped = {
                if (dragged[0]) {
                    val n = targetValue.roundToIntSafe().coerceIn(0, count - 1)
                    animateToValue(n.toFloat())
                    if (n != current) pick(n)
                }
                dragged[0] = false
            },
            onDrag = { _, d ->
                if (d.x != 0f) dragged[0] = true
                val w = widthAt(targetValue).coerceAtLeast(1f)
                updateValue((targetValue + d.x / w).coerceIn(0f, (count - 1).toFloat()))
            },
        )
    }
    LaunchedEffect(sel, anim) { if (anim.targetValue != sel.toFloat()) anim.animateToValue(sel.toFloat()) }
    val density = LocalDensity.current
    Box(modifier.glass(CircleShape).padding(inset)) {
        Row(
            Modifier
                .keepGesture()
                .then(anim.modifier)
                .drawBehind {
                    measured.intValue // re-draw once items are placed
                    val v = anim.value
                    val i = v.toInt().coerceIn(0, count - 1); val j = (i + 1).coerceAtMost(count - 1); val t = v - i
                    val x = xs[i] + (xs[j] - xs[i]) * t
                    val w = ws[i] + (ws[j] - ws[i]) * t
                    if (w <= 0f) return@drawBehind
                    val stretch = (anim.velocity / 40f).coerceIn(-0.25f, 0.25f)
                    val sx = anim.scaleX / (1f - kotlin.math.abs(stretch) * 0.75f)
                    val sy = anim.scaleY * (1f - kotlin.math.abs(stretch) * 0.25f)
                    val cx = x + w / 2; val cy = size.height / 2
                    val tw = w * sx; val th = size.height * sy
                    drawRoundRect(thumbColor, androidx.compose.ui.geometry.Offset(cx - tw / 2, cy - th / 2),
                        androidx.compose.ui.geometry.Size(tw, th), androidx.compose.ui.geometry.CornerRadius(th / 2))
                },
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing),
        ) {
            for (i in 0 until count) {
                Box(
                    Modifier.height(itemHeight)
                        .onGloballyPositioned { co ->
                            val px = co.parentLayoutCoordinates?.localPositionOf(co, androidx.compose.ui.geometry.Offset.Zero)?.x ?: 0f
                            if (xs[i] != px || ws[i] != co.size.width.toFloat()) { xs[i] = px; ws[i] = co.size.width.toFloat(); measured.intValue++ }
                        }
                        .plainClickable(role = Role.Tab) { if (i != current) pick(i) },
                    contentAlignment = Alignment.Center,
                ) { item(i, i == sel) }
            }
        }
    }
}

private fun Float.roundToIntSafe(): Int = kotlin.math.round(this).toInt()

/**
 * Keeps a drag that starts on this element for itself: once the finger moves past the touch slop,
 * every movement is consumed (after this element's own, inner, gesture handlers have seen it), so
 * parents that close or page on swipe (bottom sheets, the drawer, pagers) don't move as well.
 * Taps are untouched and still arrive as clicks.
 */
fun Modifier.keepGesture(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var total = Offset.Zero
        var dragging = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            total += change.position - change.previousPosition
            if (!dragging && total.getDistance() > viewConfiguration.touchSlop) dragging = true
            if (dragging) event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
        }
    }
}
