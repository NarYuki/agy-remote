package moe.n4tsu.agyremote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DesktopMac
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.n4tsu.agyremote.state.IndicatorStyle
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Thinking indicator (spec 9.3). Every shape shares a faint track and a "comet": a bright mint
 * head fading through sky into a violet tail. [size] is the drawing; it sits in a frame 1.2x
 * larger (24 for 20). Random picks a shape per appearance. The clock runs at ~30 fps only while
 * the screen is resumed and is read in the draw phase only, so it never recomposes.
 */
@Composable
fun ThinkingIndicator(style: IndicatorStyle, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    val pick = remember { CONCRETE.random() }
    val shape = if (style == IndicatorStyle.RANDOM) pick else style
    var clock by remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val start = withFrameNanos { it } - (clock * 1e9f).toLong()
            while (true) {
                val now = withFrameNanos { it }
                clock = (now - start) / 1e9f
                delay(26)
            }
        }
    }
    val pen = remember { Pen() }
    val track = c.onSurface.copy(alpha = if (c.isDark) 0.14f else 0.08f)
    val mint = c.ramp[0]
    Canvas(modifier.size(size * 1.2f)) {
        val s = size.toPx()
        val r = Rect(Offset((this.size.width - s) / 2, (this.size.height - s) / 2), Size(s, s))
        pen.width = maxOf(2.dp.toPx(), 0.15f * s)
        pen.track = track
        val t = clock
        with(pen) {
            when (shape) {
                IndicatorStyle.PROMPT -> prompt(r, t, mint)
                IndicatorStyle.BRAILLE -> braille(r, t)
                IndicatorStyle.INFINITY -> infinity(r, t)
                IndicatorStyle.BRACKETS -> brackets(r, t)
                IndicatorStyle.MOBIUS -> mobius(r, t, track)
                IndicatorStyle.DOTS -> dots(r, t)
                else -> ring(r, t)
            }
        }
    }
}

private val CONCRETE = listOf(IndicatorStyle.RING, IndicatorStyle.PROMPT, IndicatorStyle.BRAILLE, IndicatorStyle.INFINITY, IndicatorStyle.BRACKETS, IndicatorStyle.MOBIUS, IndicatorStyle.DOTS)

/** The comet ramp (0 tail … 1 head), sampled once at 49 steps. */
private val COMET: List<Color> = (0..48).map { n ->
    val u = n / 48f
    val tail = floatArrayOf(0.56f, 0.42f, 1f); val mid = floatArrayOf(0.22f, 0.66f, 0.98f); val head = floatArrayOf(0.10f, 0.82f, 0.72f)
    val ch = FloatArray(3) { i -> if (u <= 0.6f) tail[i] + (mid[i] - tail[i]) * (u / 0.6f) else mid[i] + (head[i] - mid[i]) * ((u - 0.6f) / 0.4f) }
    Color(ch[0], ch[1], ch[2], minOf(1f, 0.08f + 1.15f * u))
}

private fun comet(u: Float): Color = COMET[(u.coerceIn(0f, 1f) * 48).roundToInt()]

/** Drawing helpers; caches paths and their measures per rectangle. */
private class Pen {
    var width = 2f
    var track = Color.Gray
    private var cachedFor: Rect? = null
    private val cache = HashMap<String, Pair<Path, PathMeasure>>()
    private val piece = Path()

    private fun cached(key: String, r: Rect, build: (Path) -> Unit): Pair<Path, PathMeasure> {
        if (cachedFor != r) { cache.clear(); cachedFor = r }
        return cache.getOrPut(key) {
            val p = Path().also(build)
            p to PathMeasure().also { it.setPath(p, false) }
        }
    }

    private fun DrawScope.round() = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

    /** The comet along a path in 24 pieces, head at fraction [head], tail [span] long. */
    private fun DrawScope.comet(m: PathMeasure, head: Float, span: Float, loop: Boolean) {
        val len = m.length
        val stroke = round()
        for (n in 1..24) {
            var from = head - span * (1 - (n - 1) / 24f)
            var to = head - span * (1 - n / 24f)
            if (loop) {
                from -= floor(from); to -= floor(to)
                if (to < from) to = 1f
            } else {
                from = maxOf(0f, from); to = minOf(1f, to)
                if (to <= from) continue
            }
            piece.reset()
            if (m.getSegment(from * len, to * len, piece, true)) drawPath(piece, comet(n / 24f), style = stroke)
        }
    }

    fun DrawScope.ring(r: Rect, t: Float) {
        val (circle, m) = cached("ring", r) { it.addOval(r.deflate(width / 2), androidx.compose.ui.graphics.Path.Direction.Clockwise) }
        drawPath(circle, track, style = Stroke(width))
        val length = 0.475f + 0.225f * sin(2.4f * t)
        val lead = 0.83f * t + 0.11f * sin(2.4f * t - 0.8f)
        comet(m, lead - floor(lead), length, true)
    }

    fun DrawScope.prompt(r: Rect, t: Float, mint: Color) {
        val s = r.width
        val (chevron, m) = cached("chev", r) {
            it.moveTo(r.left + 0.08f * s, r.top + 0.2f * s); it.lineTo(r.left + 0.44f * s, r.top + 0.5f * s); it.lineTo(r.left + 0.08f * s, r.top + 0.8f * s)
        }
        val a = Offset(r.left + 0.56f * s, r.top + 0.8f * s); val b = Offset(r.left + 0.94f * s, r.top + 0.8f * s)
        drawPath(chevron, track, style = round())
        drawLine(track, a, b, width, StrokeCap.Round)
        val phase = (t % 1.6f) / 1.6f
        val x = minOf(1f, phase / 0.55f)
        comet(m, 1.6f * x * x, 0.6f, false)
        val blink = if (phase < 0.5f) 0f else 0.5f - 0.5f * cos((phase - 0.5f) * 2 * PI.toFloat() * 4)
        drawLine(mint.copy(alpha = 0.25f + 0.75f * blink), a, b, width, StrokeCap.Round)
    }

    fun DrawScope.braille(r: Rect, t: Float) {
        val dx = 0.34f * r.width; val dy = 0.27f * r.height
        val left = r.center.x - dx / 2; val top = r.center.y - 1.5f * dy
        val lead = 9f * t
        BRAILLE_RING.forEachIndexed { k, (cx, cy) ->
            var lag = (lead - k) % 8f
            if (lag < 0) lag += 8f
            val glow = maxOf(0f, 1f - lag / 4.5f)
            val radius = 0.1f * r.width * (0.75f + 0.35f * glow)
            val centre = Offset(left + cx * dx, top + cy * dy)
            drawCircle(track, radius, centre)
            if (glow > 0.001f) drawCircle(comet(glow), radius, centre)
        }
    }

    fun DrawScope.infinity(r: Rect, t: Float) {
        val (curve, m) = cached("inf", r) { p ->
            val ax = r.width / 2 - width / 2; val ay = 0.372f * r.height
            for (n in 0..72) {
                val a = n / 72f * 2 * PI.toFloat()
                val x = r.center.x + ax * sin(a); val y = r.center.y + ay * sin(a) * cos(a)
                if (n == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
        drawPath(curve, track, style = round())
        val lead = 0.55f * t
        comet(m, lead - floor(lead), 0.42f + 0.08f * sin(1.7f * t), true)
    }

    fun DrawScope.brackets(r: Rect, t: Float) {
        val s = r.width
        val open = s * (0.03f + 0.05f * (0.5f + 0.5f * sin(3 * t)))
        val stroke = round()
        for (side in intArrayOf(-1, 1)) {
            val foot = r.center.x + side * (0.5f * s - 0.3f * s + open)
            val tip = r.center.x + side * (0.5f * s - 0.04f * s + open)
            piece.reset()
            piece.moveTo(foot, r.top + 0.18f * s); piece.lineTo(tip, r.center.y); piece.lineTo(foot, r.bottom - 0.18f * s)
            drawPath(piece, comet(if (side < 0) 0.45f else 0.85f), style = stroke)
        }
        for (k in 0 until 3) {
            val wave = 0.5f + 0.5f * sin(6 * t - 0.9f * k)
            val radius = 0.06f * s * (0.7f + 0.5f * wave)
            drawCircle(comet(0.35f + 0.65f * wave), radius, Offset(r.center.x + (k - 1) * 0.17f * s, r.center.y - 0.06f * s * wave))
        }
    }
}

private val BRAILLE_RING = listOf(0f to 0f, 1f to 0f, 1f to 1f, 1f to 2f, 1f to 3f, 0f to 3f, 0f to 2f, 0f to 1f)

/** The `>_` brand mark, painted mint to violet unless [color] is given. */
@Composable
fun BrandMark(size: Dp, cursorOn: Boolean, modifier: Modifier = Modifier, color: Color? = null, cursorAlpha: Float = 1f) {
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        val paint = color?.let { androidx.compose.ui.graphics.SolidColor(it) }
            ?: Brush.horizontalGradient(listOf(Color(0xFF1AD1B8), Color(0xFF8F6BFF)))
        val line = Stroke(maxOf(1.7.dp.toPx(), 0.14f * w), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val chevron = Path().apply { moveTo(w * 0.12f, w * 0.24f); lineTo(w * 0.44f, w * 0.5f); lineTo(w * 0.12f, w * 0.76f) }
        drawPath(chevron, paint, style = line)
        if (cursorOn) {
            val bar = Path().apply { moveTo(w * 0.56f, w * 0.76f); lineTo(w * 0.90f, w * 0.76f) }
            drawPath(bar, paint, style = line, alpha = cursorAlpha)
        }
    }
}

/** The computer's glyph (Mac Pro / Studio / mini / laptop / desktop) from its hardware model. */
fun deviceIcon(hardwareModel: String): ImageVector {
    val m = hardwareModel.lowercase()
    return when {
        m.startsWith("macpro") -> Icons.Outlined.Dns
        m.startsWith("mac1") || m.contains("studio") -> Icons.Outlined.Tv
        m.startsWith("macmini") -> Icons.Outlined.Computer
        m.startsWith("macbook") || m.contains("laptop") -> Icons.Outlined.LaptopMac
        m.startsWith("imac") -> Icons.Outlined.DesktopMac
        else -> Icons.Outlined.DesktopWindows
    }
}

/** Computer glyph in a ring: green when online, accentRing otherwise. */
@Composable
fun ComputerRing(hardwareModel: String, online: Boolean, size: Dp, ringWidth: Dp = 1.8.dp, iconSize: Dp = size * 0.45f, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    Box(
        modifier.size(size).border(ringWidth, if (online) c.connectedGreen else c.accentRing, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(deviceIcon(hardwareModel), null, tint = c.onSurface, modifier = Modifier.size(iconSize))
    }
}

private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
private fun wrap(x: Float) = x - floor(x)

/**
 * A Möbius strip turning in space: a lit band with one half twist, seen in perspective and drawn
 * back to front, a bright stretch running along it. Facets are shaded by how they face the light
 * (both sides lit), with a glint where they face it head on (iOS Pen.mobius).
 */
private fun DrawScope.mobius(r: Rect, t: Float, track: Color) {
    val n = 96
    val spin = t * 0.6f
    val tilt = 1.0f + 0.16f * sin(t * 0.7f)
    val yaw = 0.28f * sin(t * 0.45f)
    val radius = 0.30f; val w = 0.16f; val focal = 1.7f
    val s = r.width
    fun point(u: Float, v: Float): FloatArray {
        val half = u / 2
        val x = (radius + v * cos(half)) * cos(u + spin)
        val y = (radius + v * cos(half)) * sin(u + spin)
        val z = v * sin(half)
        val y1 = y * cos(tilt) - z * sin(tilt); val z1 = y * sin(tilt) + z * cos(tilt)
        return floatArrayOf(x * cos(yaw) + z1 * sin(yaw), y1, -x * sin(yaw) + z1 * cos(yaw))
    }
    fun screen(p: FloatArray): Offset {
        val k = focal / (focal - p[2])
        return Offset(r.left + (0.5f + p[0] * k * 1.25f) * s, r.top + (0.5f + p[1] * k * 1.25f) * s)
    }
    val lx = -0.45f; val ly = -0.6f; val lz = 0.75f
    val ll = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz)
    data class Quad(val c: List<FloatArray>, val u: Float) { val depth = c.sumOf { it[2].toDouble() }.toFloat() / 4 }
    val quads = (0 until n).map { i ->
        val u0 = i.toFloat() / n * 2 * PI.toFloat(); val u1 = (i + 1).toFloat() / n * 2 * PI.toFloat()
        Quad(listOf(point(u0, -w), point(u1, -w), point(u1, w), point(u0, w)), i.toFloat() / n)
    }.sortedBy { it.depth }
    val head = wrap(t * 0.35f)
    val path = Path()
    val rim = maxOf(0.5f, s * 0.012f)
    for (q in quads) {
        val pts = q.c.map(::screen)
        path.reset()
        pts.forEachIndexed { k, p -> if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        path.close()
        val a = q.c[0]; val b = q.c[1]; val d = q.c[3]
        val e1x = b[0] - a[0]; val e1y = b[1] - a[1]; val e1z = b[2] - a[2]
        val e2x = d[0] - a[0]; val e2y = d[1] - a[1]; val e2z = d[2] - a[2]
        val nx = e1y * e2z - e1z * e2y; val ny = e1z * e2x - e1x * e2z; val nz = e1x * e2y - e1y * e2x
        val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        val facing = kotlin.math.abs((nx * lx + ny * ly + nz * lz) / (nl * ll))
        val near = ((q.depth + 0.35f) / 0.7f).coerceIn(0f, 1f)
        val lit = 0.22f + 0.78f * facing
        val glow = maxOf(0f, 1 - wrap(head - q.u) / 0.55f)
        drawPath(path, track)
        drawPath(path, comet(0.3f + 0.7f * glow * (0.5f + 0.5f * near)))
        drawPath(path, Color.Black.copy(alpha = ((1 - lit) * 0.62f + (1 - near) * 0.18f).coerceIn(0f, 1f)))
        val glint = facing.toDouble().pow(14.0).toFloat() * (0.35f + 0.4f * near)
        if (glint > 0.02f) drawPath(path, Color.White.copy(alpha = glint.coerceIn(0f, 1f)))
        val rimColor = Color.White.copy(alpha = (0.10f + 0.22f * near * facing).coerceIn(0f, 1f))
        drawLine(rimColor, pts[0], pts[1], rim)
        drawLine(rimColor, pts[2], pts[3], rim)
    }
}

/** A figure the dots form: offsets from the centre in units of the square. */
private class DotFigure(val count: Int, val radius: Float = 0.07f, val pulse: ((Int, Float) -> Float)? = null, val place: (Int, Int, Float) -> Offset) {
    fun size(i: Int, t: Float) = radius * (pulse?.invoke(i, t) ?: 1f)
}

private fun polar(r: Float, a: Float) = Offset(cos(a) * r, sin(a) * r)
private const val TAU = (2 * PI).toFloat()

private val FIGURES = listOf(
    DotFigure(6) { i, n, t -> polar(0.32f, TAU * i / n + t * 1.6f) },
    DotFigure(3, 0.09f) { i, _, t -> Offset((i - 1) * 0.28f, 0.08f - 0.26f * kotlin.math.abs(sin(t * 4 - i * 0.7f))) },
    DotFigure(9, 0.058f) { i, _, t ->
        val c = Offset((i % 3 - 1).toFloat(), (i / 3 - 1).toFloat())
        c * (0.27f + 0.05f * sin(t * 4.5f - c.getDistance() * 2.2f))
    },
    DotFigure(8, 0.05f, { i, _ -> 0.7f + 0.06f * i }) { i, _, t -> polar(0.06f + 0.04f * i, i * 0.85f - t * 2.4f) },
    DotFigure(4, 0.075f) { i, _, t ->
        val side = if (i % 2 == 0) 1f else -1f
        polar(0.2f, t * 1.3f + (i / 2) * PI.toFloat()) + polar(0.12f * side, t * 4.2f)
    },
    DotFigure(1, 0.16f, { _, t ->
        val beat = (t * 1.4f) % 1f
        0.75f + 0.3f * kotlin.math.exp(-((beat - 0.1f) * 14).let { it * it }) + 0.18f * kotlin.math.exp(-((beat - 0.32f) * 14).let { it * it })
    }) { _, _, _ -> Offset.Zero },
    DotFigure(7, 0.055f) { i, _, t -> Offset((i - 3) * 0.12f, 0.2f * sin(t * 4.2f - i * 0.8f)) },
    DotFigure(6, 0.058f) { i, _, t ->
        val corner = (i / 2) * TAU / 3 + t * 1.1f
        if (i % 2 == 0) polar(0.34f, corner) else (polar(0.34f, corner) + polar(0.34f, corner + TAU / 3)) * 0.5f
    },
    DotFigure(5, 0.06f, { i, _ -> 1 - 0.13f * i }) { i, _, t -> val a = t * 1.8f - i * 0.42f; Offset(0.36f * sin(a), 0.3f * sin(a) * cos(a)) },
    DotFigure(5, 0.065f) { i, _, t -> if (i == 0) Offset.Zero else polar(0.16f + 0.14f * (0.5f + 0.5f * sin(t * 3)), i * PI.toFloat() / 2 + PI.toFloat() / 4) },
    DotFigure(4, 0.075f) { i, _, t ->
        val turn = smooth(((t * 0.9f) % 1f) * 1.6f)
        polar(0.3f, (i + floor(t * 0.9f) + turn) * PI.toFloat() / 2 + PI.toFloat() / 4)
    },
)

/**
 * Dots re-forming through eleven figures (about 46 s for the run). The count changes only between
 * figures: a new dot splits off a neighbour and grows, a leaving one shrinks into a neighbour.
 */
private fun DrawScope.dots(r: Rect, t: Float) {
    val s = r.width
    val hold = 4.2f; val morph = 1.3f
    val step = t / hold
    val index = floor(step).toInt()
    val blend = smooth(((step - floor(step)) * hold - (hold - morph)) / morph)
    val from = FIGURES[index % FIGURES.size]; val to = FIGURES[(index + 1) % FIGURES.size]
    for (i in 0 until maxOf(from.count, to.count)) {
        val a = from.place(i % from.count, from.count, t)
        val b = to.place(i % to.count, to.count, t)
        val sa = if (i < from.count) from.size(i, t) else 0f
        val sb = if (i < to.count) to.size(i, t) else 0f
        val size = sa + (sb - sa) * blend
        if (size < 0.02f) continue
        val p = a + (b - a) * blend
        val tone = 0.5f + 0.5f * sin(t * 2.6f - i * 0.75f)
        drawCircle(comet(0.35f + 0.65f * tone), s * size, Offset(r.left + (0.5f + p.x) * s, r.top + (0.5f + p.y) * s))
    }
}
