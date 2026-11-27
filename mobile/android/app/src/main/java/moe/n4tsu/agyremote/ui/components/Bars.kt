package moe.n4tsu.agyremote.ui.components

import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import moe.n4tsu.agyremote.ui.theme.Motion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Writes this element's top or bottom edge (root coordinates, px) into [onEdge]. */
fun Modifier.reportEdge(bottom: Boolean, onEdge: (Float) -> Unit): Modifier =
    onGloballyPositioned { val b = it.boundsInRoot(); onEdge(if (bottom) b.bottom else b.top) }

/**
 * Bar-edge fade (iOS BarEdgeFade): masks scrolling content so it fades to 35 % where the top and
 * bottom bars really are. [topEdge] / [bottomEdge] are measured root y positions in px.
 */
fun Modifier.barEdgeFade(topEdge: Float, bottomEdge: Float?, fade: Dp = 22.dp): Modifier = composed {
    val top = remember { mutableFloatStateOf(0f) }
    this
        .onGloballyPositioned { top.floatValue = it.boundsInRoot().top }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val f = fade.toPx()
            val h = max(1f, size.height)
            val minY = top.floatValue
            fun unit(y: Float) = ((y - minY) / h).coerceIn(0f, 1f)
            val bottom = bottomEdge ?: (minY + h + f)
            val a = unit(topEdge - 0.6f * f)
            val b = max(unit(topEdge + 0.4f * f), a + 0.0001f)
            val c = min(unit(bottom - 0.4f * f), 0.9999f)
            val d = max(unit(bottom + 0.6f * f), c + 0.0001f)
            val floor = Color.Black.copy(alpha = 0.35f)
            val stops = arrayOf(a to floor, min(b, 1f) to Color.Black, max(c, min(b, 1f)) to Color.Black, min(d, 1f) to floor)
            drawRect(Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
        }
}

/**
 * Capsule drawer row (iOS DrawerRowStyle): 16 dp side padding, 46 dp high, selected pill,
 * pressed 6 % fill and 0.985 scale.
 */
@Composable
fun DrawerRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val c = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, Motion.listPress(), label = "rowPress")
    val pressFill by animateFloatAsState(if (pressed) 0.06f else 0f, Motion.listPress(), label = "rowFill")
    val fill = if (highlighted) c.sidebarActivePill else c.onSurface.copy(alpha = pressFill)
    Row(
        modifier.fillMaxWidth().height(46.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(fill, CircleShape)
            .clickable(interaction, null, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Spinning partial ring with a pulsing dot (iOS RunningActivityIcon). Like iOS it ticks at ~30 fps
 * and only while the screen is resumed; the clock is read in draw only, so it never recomposes.
 */
@Composable
fun RunningActivityIcon(size: Dp = 15.dp, tint: Color = LocalAppColors.current.primary, modifier: Modifier = Modifier) {
    var clock by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            val start = androidx.compose.runtime.withFrameNanos { it } - (clock * 1e9f).toLong()
            var nextAt = 0L
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                if (now < nextAt) continue
                nextAt = now + 30_000_000L
                clock = (now - start) / 1e9f
                kotlinx.coroutines.delay(20)
            }
        }
    }
    Canvas(modifier.size(size)) {
        val sec = clock
        val pulse = 0.72f + 0.28f * sin(3.8f * sec)
        val stroke = 1.8.dp.toPx()
        rotate((240f * sec) % 360f) {
            drawArc(
                tint.copy(alpha = pulse), startAngle = 0.1f * 360f - 90f, sweepAngle = 0.62f * 360f, useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        val r = this.size.width * 0.15f * (0.6f + 0.25f * (1 + pulse))
        drawCircle(tint.copy(alpha = 0.9f), r)
    }
}

/** Pencil-in-a-square for "new chat" (iOS ComposeIcon). */
@Composable
fun ComposeIcon(size: Dp = 18.dp, tint: Color = LocalAppColors.current.onSurface) {
    Icon(Icons.Outlined.EditNote, null, Modifier.size(size * 1.12f), tint = tint)
}

/** Two-bar menu glyph (iOS MenuIcon: 17 and 10.5 wide, 1.9 high, 5 apart). */
@Composable
fun MenuIcon(color: Color = LocalAppColors.current.onSurface) {
    Canvas(Modifier.size(17.dp, 9.dp)) {
        val s = 1.9.dp.toPx()
        drawLine(color, Offset(s / 2, s / 2), Offset(17.dp.toPx() - s / 2, s / 2), s, StrokeCap.Round)
        drawLine(color, Offset(s / 2, size.height - s / 2), Offset(10.5.dp.toPx() - s / 2, size.height - s / 2), s, StrokeCap.Round)
    }
}

/** Remaining plan percent (iOS PlanLimitGauge); a gauge glyph while loading. */
@Composable
fun PlanLimitGauge(percent: Int?, onClick: () -> Unit, contentDescription: String? = null) {
    val c = LocalAppColors.current
    Box(Modifier.size(44.dp, 40.dp).plainClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (percent != null) {
            val tint = when { percent < 10 -> c.errorRed; percent < 25 -> c.warningAmber; else -> c.onSurfaceVariant }
            Text("$percent%", style = AppType.sans(13f, FontWeight.SemiBold, tint).copy(fontFeatureSettings = "tnum"))
        } else {
            Icon(Icons.Outlined.Speed, contentDescription, Modifier.size(17.dp), tint = c.onSurfaceVariant)
        }
    }
}
