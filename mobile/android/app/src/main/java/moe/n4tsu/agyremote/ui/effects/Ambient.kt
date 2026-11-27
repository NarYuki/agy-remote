package moe.n4tsu.agyremote.ui.effects

import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Spec section 9: the backdrop curtains + entrance swell (9.1) and the header "crown" glow
// (9.2). AGSL RuntimeShaders on Android 13+, plain gradients on Android 12.
//
// Performance: each effect owns a small frame driver that runs only while the effect is in
// composition, the lifecycle is RESUMED, and there is something to draw. The per-frame values
// live in plain (non-snapshot) fields; a single frame counter is read only inside the Canvas
// draw lambda, so a frame invalidates the draw of that one layer, never a recomposition. When
// nothing moves fast the driver drops to ~30 Hz.

private val hasAgsl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

// MARK: - AGSL

private const val COMMON = """
float rand(float2 cell) {
    float3 q = fract(float3(cell.x, cell.y, cell.x) * 0.1031);
    q += dot(q, q.yzx + 19.19);
    return fract((q.x + q.y) * q.z);
}
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = p - i;
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = rand(i); float b = rand(i + float2(1, 0));
    float c = rand(i + float2(0, 1)); float d = rand(i + float2(1, 1));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float layered(float2 p) {
    float total = 0.0; float weight = 0.5;
    for (int o = 0; o < 4; o++) {
        total += weight * noise(p);
        p = float2(0.87758 * p.x - 0.47943 * p.y, 0.47943 * p.x + 0.87758 * p.y) * 2.03 + 3.1;
        weight *= 0.5;
    }
    return clamp((total - 0.47) * 1.25 + 0.5, 0.0, 1.0);
}
float3 stop(float k) {
    if (k < 0.5) return float3(0.102, 0.820, 0.722);
    if (k < 1.5) return float3(0.220, 0.659, 0.980);
    if (k < 2.5) return float3(0.561, 0.420, 1.000);
    return float3(1.000, 0.439, 0.620);
}
float3 ramp(float x, bool washed) {
    float u = fract(x) * 4.0;
    float k = floor(u);
    float w = 0.5 - 0.5 * cos(fract(u) * 3.14159265);
    float3 c = mix(stop(k), stop(mod(k + 1.0, 4.0)), w);
    return washed ? c + (1.0 - c) * 0.3 : c;
}
float seg(float2 q, float2 s, float2 e) {
    float2 d = e - s;
    float h = clamp(dot(q - s, d) / dot(d, d), 0.0, 1.0);
    return distance(q, s + d * h);
}
float glyph(float2 p) {
    return min(min(seg(p, float2(-0.30, -0.22), float2(-0.05, 0.0)),
                   seg(p, float2(-0.05, 0.0), float2(-0.30, 0.22))),
               seg(p, float2(0.08, 0.22), float2(0.34, 0.22)));
}
float band(float lo, float hi, float v) { return smoothstep(lo, hi, v); }
"""

/** Curtains of light rising from the bottom, plus the swell and its `>_` glints. Opaque. */
private const val AURORA_SRC = """
uniform float2 res;
uniform float time;
uniform float tide;
uniform float presence;
uniform float stir;
uniform float lift;
uniform float swell;
uniform float dark;
""" + COMMON + """
half4 main(float2 pos) {
    float2 uv = pos / max(res, float2(1.0));
    bool night = dark > 0.5;
    float up = 1.0 - uv.y;
    float x = uv.x * res.x / max(res.y, 1.0);
    float shifted = max(0.0, up - 0.8 * clamp(lift, 0.0, 0.5));
    float headerGap = band(0.09, 0.33, uv.y);
    float level = clamp(tide, 0.0, 1.0);
    float vis = clamp(presence, 0.0, 1.0);
    float energy = clamp(stir, 0.0, 1.0);
    float amount = clamp(swell, 0.0, 1.0);
    if ((vis <= 0.002 || level <= 0.002) && amount <= 0.002) {
        return night ? half4(0, 0, 0, 1) : half4(1, 1, 1, 1);
    }
    float warp = layered(float2(1.5 * x + 0.05 * time, 2.1 * shifted - 0.08 * time));
    float2 w = float2(warp, 1.0 - warp);

    float3 curtain = float3(0.0);
    float cover = 0.0;
    if (vis > 0.002 && level > 0.002) {
        float folds = layered(float2(2.2 * x + 1.4 * w.x, 0.8 * shifted - 0.11 * time + w.y));
        float shiver = 0.05 * energy * sin(8.5 * uv.x + 2.1 * time + 2.8 * w.y);
        float crest = level * (0.56 + 0.46 * folds) + shiver * level;
        float soft = 0.1 + 0.3 * level;
        float body = 1.0 - band(0.25 * crest, crest + soft, shifted);
        body = body * body * headerGap;
        cover = body * pow(vis, 0.8);
        float tone = 0.4 * uv.x + 0.42 * w.x + 0.22 * shifted + 0.03 * time - 0.25;
        float3 hue = ramp(tone, !night);
        float bright = clamp(0.4 + 0.62 * band(0.0, max(crest, 0.05), shifted) + 0.15 * energy, 0.0, 1.0);
        curtain = night ? mix(float3(0.02, 0.07, 0.09), 0.92 * hue, bright) : hue;
    }

    float3 waves = float3(0.0);
    float waveCover = 0.0;
    float highest = 0.0;
    float fadeIn = band(0.0, 0.22, amount);
    if (amount > 0.002) {
        float lively = 0.35 + 0.65 * amount;
        for (int i = 0; i < 3; i++) {
            float d = 2.0 - float(i);
            float speed = 0.56 + 0.21 * d;
            float wobble = 0.046 * sin((3.0 + 1.4 * d) * uv.x + 2.0 * speed * time + 2.0 * d)
                         + 0.021 * cos((6.8 + 2.0 * d) * uv.x - 1.3 * speed * time + 0.6 * d)
                         + 0.09 * (layered(float2(2.1 * uv.x + 4.7 * d, 0.24 * time + d)) - 0.5);
            float top = amount * (0.5 + 0.11 * d) + wobble * lively;
            highest = max(highest, top);
            float under = top - up;
            float feather = 0.15 + 0.06 * d;
            float rise = band(-1.6 * feather, 0.9 * feather, under);
            float fill = rise * rise * (1.0 - 0.62 * band(0.0, 0.6, under));
            float rim = exp(-1.8 * under * under / (feather * feather));
            float a = clamp(fill * (0.46 - 0.11 * d) + rim * (0.12 - 0.03 * d), 0.0, 1.0) * fadeIn;
            float3 c = ramp(0.4 * uv.x + 0.17 * d - 0.22 + 0.3 * w.x + 0.03 * time, !night);
            waves = waves * (1.0 - 0.35 * a) + c * a;
            waveCover += a * (1.0 - waveCover);
        }
        waves *= headerGap;
        waveCover *= headerGap;
    }

    float glint = 0.0;
    float3 glintColor = float3(0.0);
    if (amount > 0.002) {
        float2 g = float2(x, up) * 30.0 + float2(0.0, -0.3 * time);
        float row = floor(g.y);
        g.x += 0.5 * mod(abs(row), 2.0);
        float2 cell = floor(g);
        float pick = rand(cell + 11.0);
        if (pick > 0.86) {
            float2 nudge = float2(rand(cell + 3.7), rand(cell + 8.1)) - 0.5;
            float scale = 0.5 + 0.45 * rand(cell + 1.3);
            float2 local = (fract(g) - 0.5 - 0.3 * nudge) / scale;
            local.y = -local.y;   // g.y grows upward; the glyph is y-down: keep `>_` upright
            float ink = 1.0 - band(0.03, 0.055, glyph(local));
            float flicker = 0.35 + 0.65 * (0.5 + 0.5 * cos(1.9 * time + 6.2831853 * pick));
            float near = (up - highest + 0.1) / 0.15;
            glint = ink * flicker * exp(-near * near) * fadeIn * 0.5 * headerGap;
            float3 c = ramp(0.4 * uv.x - 0.22 + 0.2 * pick, false);
            glintColor = night ? c + (1.0 - c) * 0.55 : 0.72 * c;
        }
    }

    float3 rgb;
    if (night) {
        rgb = curtain * cover * (1.0 - 0.35 * waveCover) + 0.8 * waves + glintColor * glint;
    } else {
        float lower = band(0.3, 0.62, uv.y);
        rgb = mix(float3(1.0), curtain, min(0.6, 0.7 * cover * lower));
        if (waveCover > 0.001) {
            float3 tint = waves / max(0.05, waveCover);
            rgb = mix(rgb, tint, min(0.62, 0.62 * waveCover) * (0.35 + 0.65 * lower));
        }
        rgb = mix(rgb, glintColor, min(0.45, 1.1 * glint * (0.25 + 0.75 * lower)));
    }
    return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);
}
"""

/** Glow hanging from the top edge while the agent works. Premultiplied. */
private const val CROWN_SRC = """
uniform float2 res;
uniform float hue;
uniform float drift;
uniform float level;
uniform float reach;
uniform float dark;
""" + COMMON + """
half4 main(float2 pos) {
    float strength = clamp(level, 0.0, 1.0);
    float ext = clamp(reach, 0.0, 1.0);
    if (strength < 0.001 || ext < 0.001) return half4(0);
    float2 uv = pos / max(res, float2(1.0));
    float limit = 0.18 + 0.44 * ext;
    if (uv.y > limit + 0.3) return half4(0);
    float2 wp = float2(1.8 * uv.x + 0.06 * drift, 2.0 * uv.y + 0.04 * drift);
    float warp = 0.65 * noise(wp) + 0.35 * noise(2.1 * wp + 5.3);
    float3 rgb = float3(0.0);
    float cover = 0.0;
    for (int i = 0; i < 3; i++) {
        float d = 2.0 - float(i);
        float speed = 0.46 + 0.19 * d;
        float hang = limit * (0.62 + 0.19 * d)
                   + 0.034 * sin((2.7 + 1.25 * d) * uv.x + speed * drift + 2.2 * d)
                   + 0.017 * cos((6.1 + 1.6 * d) * uv.x - 0.7 * speed * drift + d)
                   + 0.07 * (warp - 0.5);
        float inside = hang - uv.y;
        float feather = 0.12 + 0.05 * d;
        float rise = band(-1.4 * feather, feather, inside);
        float fill = rise * rise * (0.55 + 0.45 * band(0.0, max(hang, 0.01), inside));
        float a = clamp(fill * (0.42 - 0.1 * d), 0.0, 1.0);
        float3 c = ramp(hue + 0.3 * uv.x + 0.07 * d + 0.18 * warp, false);
        float next = cover + a * (1.0 - cover);
        rgb = mix(rgb, c, a / max(next, 0.001));
        cover = next;
    }
    bool night = dark > 0.5;
    float alpha = clamp(cover * strength * (night ? 0.95 : 0.58), 0.0, 1.0);
    float3 c = night ? rgb : mix(rgb, float3(1.0), 0.08);
    return half4(half3(c * alpha), half(alpha));
}
"""

/**
 * Whether the ambient effects are currently hidden behind something opaque (the open drawer).
 * While it returns true their clocks pause and nothing is redrawn. Read each frame, not observed.
 */
val LocalAmbientCovered = androidx.compose.runtime.staticCompositionLocalOf<() -> Boolean> { { false } }

// MARK: - Half-resolution rendering

/**
 * Runs [draw] on a canvas half the size in each direction, rendered into its own offscreen
 * texture and scaled ×2 when composited: a quarter of the shader work. The effects are soft
 * gradients, so the upscale is not visible. ([draw] sees the half size; the shaders only use
 * normalised coordinates, so the picture is the same.)
 */
@Composable
private fun HalfResCanvas(modifier: Modifier, draw: DrawScope.() -> Unit) {
    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .layout { measurable, constraints ->
                    val w = (constraints.maxWidth + 1) / 2
                    val h = (constraints.maxHeight + 1) / 2
                    val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
                }
                .graphicsLayer {
                    scaleX = 2f; scaleY = 2f
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                },
            onDraw = draw,
        )
    }
}

// MARK: - Motion helpers

/** Damped spring toward a moving target; `response` is about the seconds to settle. */
private class Spring(var value: Float = 0f) {
    private var speed = 0f
    fun step(target: Float, dt: Float, response: Float) {
        val w = 2f * PI.toFloat() / max(0.05f, response)
        val offset = value - target
        val damp = exp(-w * dt)
        val push = (speed + w * offset) * dt
        value = target + (offset + push) * damp
        speed = (speed - w * push) * damp
        if (abs(value - target) < 5e-4f && abs(speed) < 5e-4f) snap(target)
    }
    fun snap(v: Float) { value = v; speed = 0f }
    val v01 get() = value.coerceIn(0f, 1f)
}

/**
 * Runs [tick] once per frame while the lifecycle is RESUMED. [tick] gets dt in seconds and
 * returns the mode for the next frame: [FAST], [CALM] (~30 Hz) or [STOP] (sleep until [wake]
 * changes).
 */
private const val FAST = 0
private const val CALM = 1
private const val STOP = 2

@Composable
private fun FrameDriver(wake: Any?, tick: (Float) -> Int) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val step by rememberUpdatedState(tick)
    val covered by rememberUpdatedState(LocalAmbientCovered.current)
    LaunchedEffect(lifecycle, wake) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = 0L
            var calmUntil = 0L
            while (true) {
                // Fully covered (e.g. by the open drawer): stop ticking and drawing until visible.
                if (covered()) { last = 0L; delay(100); continue }
                val now = withFrameNanos { it }
                // Calm: at most one tick per ~33 ms (30 Hz), measured on frame time so it holds
                // whatever the display rate.
                if (now < calmUntil) continue
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                when (step(dt)) {
                    STOP -> return@repeatOnLifecycle
                    CALM -> { calmUntil = now + 30_000_000L; delay(20) }
                    else -> calmUntil = 0L
                }
            }
        }
    }
}

// MARK: - Backdrop (9.1)

private class BackdropFlow {
    val tide = Spring(); val presence = Spring(); val lift = Spring(); val swell = Spring()
    var stir = 0f; var clock = 0f; var quiet = 0f; var swellLeft = 0f
    var configured = false
    var heightPx = 1f
}

/**
 * The ambient backdrop (spec 9.1). Opaque: it paints the surface colour itself.
 * @param keyboardUp curtains lift above the keyboard and run brighter.
 * @param keyboardHeight the keyboard's overlap, for how far the curtains lift.
 * @param typingTick changes on every keystroke; stirs the flow.
 * @param swellKey a new value plays the one-off entrance swell.
 */
@Composable
fun AmbientBackdrop(
    modifier: Modifier = Modifier,
    keyboardUp: Boolean = false,
    keyboardHeight: Dp = 0.dp,
    typingTick: Int = 0,
    swellKey: Any = Unit,
) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val flow = remember { BackdropFlow() }
    var frame by remember { mutableIntStateOf(0) }
    val kbPx = with(density) { keyboardHeight.toPx() }
    val kb by rememberUpdatedState(keyboardUp)
    val kbOverlap by rememberUpdatedState(kbPx)
    var wake by remember { mutableIntStateOf(0) }

    LaunchedEffect(swellKey) {
        if (!flow.configured) flow.configured = true
        flow.quiet = 0f
        flow.swellLeft = 1.1f
        flow.stir = max(flow.stir, 0.35f)
        wake++
    }
    LaunchedEffect(typingTick) {
        if (typingTick != 0) { flow.stir = min(1f, flow.stir + 0.3f); flow.quiet = 0f; wake++ }
    }
    LaunchedEffect(keyboardUp) { flow.quiet = 0f; wake++ }

    FrameDriver(wake) { dt ->
        val f = flow
        if (f.swellLeft > 0f) f.swellLeft -= dt
        val swelling = f.swellLeft > 0f
        f.swell.step(if (swelling) 1f else 0f, dt, if (swelling) 1.3f else 1.6f)
        f.quiet += dt
        var tTide: Float; var tPres: Float; var tLift = 0f
        if (kb) {
            val share = kbOverlap / max(1f, f.heightPx)
            tLift = if (kbOverlap > 40f * density.density) share.coerceIn(0.24f, 0.44f) else 0.35f
            val rest = f.quiet > 10f
            tTide = if (rest) 0.28f else 0.46f
            tPres = if (rest) 0.55f else 1f
        } else {
            val rest = f.quiet > 5f
            tTide = if (rest) 0.16f else 0.34f
            tPres = if (rest) 0.5f else 0.85f
        }
        if (swelling) { tTide = max(tTide, 0.5f); tPres = 1f }
        val falling = tPres < f.presence.value
        f.tide.step(tTide, dt, if (falling) 1.8f else 0.7f)
        f.presence.step(tPres, dt, if (falling) 1.8f else 0.7f)
        f.lift.step(tLift, dt, 0.4f)
        f.stir = max(0f, f.stir - 0.7f * dt)
        f.clock = (f.clock + (1f + 0.8f * f.stir) * dt) % 4096f
        frame++
        val busy = f.swell.value > 0.001f || f.stir > 0.001f || f.quiet < 0.8f ||
            abs(f.tide.value - tTide) > 0.002f || abs(f.presence.value - tPres) > 0.002f
        if (busy) FAST else CALM
    }

    val dark = colors.isDark
    if (hasAgsl) {
        val shader = remember { RuntimeShader(AURORA_SRC) }
        val brush = remember(shader) { ShaderBrush(shader) }
        HalfResCanvas(modifier) {
            frame // read in draw only: invalidates this layer, not the composition
            flow.heightPx = size.height
            shader.setAurora(size, flow, dark)
            drawRect(brush)
        }
    } else {
        Canvas(modifier.fillMaxSize()) {
            frame
            flow.heightPx = size.height
            drawFallbackCurtains(flow, colors.ramp, dark, colors.surface)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RuntimeShader.setAurora(size: Size, f: BackdropFlow, dark: Boolean) {
    setFloatUniform("res", size.width, size.height)
    setFloatUniform("time", f.clock)
    setFloatUniform("tide", f.tide.v01)
    setFloatUniform("presence", f.presence.v01)
    setFloatUniform("stir", f.stir)
    setFloatUniform("lift", f.lift.v01)
    setFloatUniform("swell", f.swell.v01)
    setFloatUniform("dark", if (dark) 1f else 0f)
}

/** Android 12: the ramp as a gradient rising from the bottom and fading upward. */
private fun DrawScope.drawFallbackCurtains(f: BackdropFlow, ramp: List<Color>, dark: Boolean, surface: Color) {
    drawRect(surface)
    val level = max(f.tide.v01, 0.5f * f.swell.v01)
    val vis = max(f.presence.v01, f.swell.v01)
    if (level < 0.002f || vis < 0.002f) return
    val shift = ((f.clock * 0.03f) % 1f * 4).toInt()
    val cols = (0..4).map { ramp[(it + shift) % 4].copy(alpha = vis * if (dark) 0.85f else 0.45f) }
    val bottom = size.height * (1f - 0.8f * f.lift.v01.coerceAtMost(0.5f))
    val h = size.height * level * 1.4f
    val top = (bottom - h).coerceAtLeast(size.height * 0.2f)
    drawRect(Brush.horizontalGradient(cols), topLeft = Offset(0f, top), size = Size(size.width, size.height - top))
    drawRect(
        Brush.verticalGradient(listOf(surface, surface.copy(alpha = 0f)), startY = top, endY = bottom),
        topLeft = Offset(0f, top), size = Size(size.width, bottom - top),
    )
}

// MARK: - Header glow (9.2)

private class CrownFlow {
    val level = Spring(); val reach = Spring()
    var hue = 0f; var drift = 0f; var breathe = 0f; var kick = 0f; var bloomLeft = 0f
    var lastTarget = 0f
    var dim = 1f
}

/**
 * Header glow (spec 9.2). [strength] is the glow's target: 0 idle, ~0.5 welcome, ~0.7 working,
 * 1 answering, multiplied by any scroll dimming. Going from working back to idle plays a short
 * bloom, as on iOS. The layer is only composed while there is something to show.
 */
@Composable
fun HeaderGlow(strength: Float, modifier: Modifier = Modifier, dim: (() -> Float)? = null) {
    // [dim], when given, is the scroll dimming kept apart from [strength] so the phase stays exact;
    // it's read in a layer, so scrolling doesn't recompose the glow.
    @Suppress("NAME_SHADOWING") val modifier = if (dim != null) modifier.graphicsLayer { alpha = dim() } else modifier
    val colors = LocalAppColors.current
    val flow = remember { CrownFlow() }
    var frame by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    val target by rememberUpdatedState(strength)

    // Classify the incoming value into an iOS-style phase.
    LaunchedEffect(strength > 0.001f) {
        if (strength > 0.001f) visible = true
        else if (flow.lastTarget > 0.55f) { flow.bloomLeft = 0.7f; flow.kick = max(flow.kick, 1.6f) }
    }

    if (visible) {
        FrameDriver(Unit) { dt ->
            val f = flow
            val t = target
            if (t > 0.9f && f.lastTarget in 0.55f..0.9f) f.kick = max(f.kick, 0.96f)
            if (t > 0.001f) f.lastTarget = t
            var tl = 0f; var tr = 0f; var resp = 0.9f
            when {
                f.bloomLeft > 0f -> { f.bloomLeft -= dt; tl = 1f; tr = 1f; resp = 0.45f; if (f.bloomLeft <= 0f) f.lastTarget = 0f }
                t > 0.001f && t < 0.6f && f.lastTarget < 0.6f -> { tl = t; tr = 0.66f; resp = 0.8f }   // welcome
                t > 0.001f -> {
                    f.breathe += dt
                    val b = 0.07f * sin(1.3f * f.breathe)
                    tl = t + b * t / 0.7f; tr = 0.84f + 0.6f * b; resp = 0.6f
                    if (t > 0.9f) tr = 1f
                }
            }
            f.level.step(tl, dt, resp)
            f.reach.step(tr, dt, resp)
            f.kick *= exp(-2.2f * dt); if (f.kick < 0.01f) f.kick = 0f
            if (f.level.value > 0.001f) {
                f.hue = (f.hue + 0.045f * (1f + f.kick) * dt) % 64f
                f.drift = (f.drift + (0.8f + 0.5f * f.kick) * dt) % 4096f
            }
            frame++
            if (t <= 0.001f && f.bloomLeft <= 0f && f.level.value <= 0.001f) {
                f.level.snap(0f); visible = false; STOP
            } else if (f.kick > 0.02f || f.bloomLeft > 0f) FAST else CALM
        }
    }

    if (!visible) { Box(modifier); return }
    val dark = colors.isDark
    if (hasAgsl) {
        val shader = remember { RuntimeShader(CROWN_SRC) }
        val brush = remember(shader) { ShaderBrush(shader) }
        HalfResCanvas(modifier) {
            frame
            shader.setFloatUniform("res", size.width, size.height)
            shader.setFloatUniform("hue", flow.hue)
            shader.setFloatUniform("drift", flow.drift)
            shader.setFloatUniform("level", flow.level.v01)
            shader.setFloatUniform("reach", flow.reach.v01)
            shader.setFloatUniform("dark", if (dark) 1f else 0f)
            drawRect(brush)
        }
    } else {
        Canvas(modifier.fillMaxSize()) {
            frame
            val s = flow.level.v01
            val h = size.height * (0.18f + 0.44f * flow.reach.v01)
            val shift = ((flow.hue % 1f) * 4).toInt()
            val cols = (0..3).map { colors.ramp[(it + shift) % 4].copy(alpha = (if (dark) 0.8f else 0.5f) * s) }
            drawRect(Brush.horizontalGradient(cols), size = size.copy(height = h))
            drawRect(
                Brush.verticalGradient(listOf(colors.surface.copy(alpha = 0f), colors.surface), startY = 0f, endY = h),
                size = size.copy(height = h),
            )
        }
    }
}
