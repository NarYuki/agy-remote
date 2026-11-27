/*
 * Copyright 2025 Kyant (https://github.com/Kyant0/AndroidLiquidGlass)
 * Licensed under the Apache License, Version 2.0; see android/third_party/AndroidLiquidGlass/LICENSE.
 * From the Backdrop Catalog at tag 2.0.1 (65ab177e90e5c1d8c62e70cf7755841982da65f6),
 * Modified for AGY Remote: a drag that starts here is kept (keepGesture) so sheets, the drawer and pagers do not move; tap vs drag split by touch slop (a tap always toggles, a drag settles by position or fling); the whole switch is the hit area; springs back when the owner refuses a change; theme colours (connectedGreen accent, app light/dark instead of the system setting), optional onColor.
 */
package moe.n4tsu.agyremote.ui.components.liquid

import moe.n4tsu.agyremote.ui.components.keepGesture
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun LiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onColor: Color? = null,
) {
    val colors = LocalAppColors.current
    val accentColor = onColor ?: colors.connectedGreen
    val trackColor =
        if (!colors.isDark) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    // AGY Remote: tap vs drag. Movement is accumulated; only beyond the touch slop does the touch
    // become a drag. Anything less is a tap, and a tap always flips the switch.
    val slop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
    var travel by remember { mutableFloatStateOf(0f) }
    var startFraction by remember { mutableFloatStateOf(0f) }
    var fraction by remember { mutableFloatStateOf(if (selected()) 1f else 0f) }
    val latestOnSelect = androidx.compose.runtime.rememberUpdatedState(onSelect)
    // The library velocity is in switch-fractions per second (one full travel = 1), so a flick
    // faster than 1.5 travels/s settles on the side it was flicked toward.
    val flingVelocity = 1.5f
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = { didDrag = false; travel = 0f; startFraction = if (fraction >= 0.5f) 1f else 0f },
            onDragStopped = {
                val before = startFraction
                val target = if (didDrag) {
                    // Settle by velocity (a fling) or by position (past the midpoint).
                    val v = if (isLtr) velocity else -velocity
                    when {
                        v > flingVelocity -> 1f
                        v < -flingVelocity -> 0f
                        else -> if (fraction >= 0.5f) 1f else 0f
                    }
                } else {
                    // Tap (or a long press without movement): always the other side, measured
                    // from the switch's current (possibly not yet confirmed) position.
                    if (fraction >= 0.5f) 0f else 1f
                }
                didDrag = false
                travel = 0f
                fraction = target
                animateToValue(target)
                // Report once per tap or completed drag that ends on the other side.
                if (target != before) latestOnSelect.value(target == 1f)
                // The owner may refuse the change (e.g. notifications wait for the explainer):
                // spring back to the real state once it has had time to update.
                animationScope.launch {
                    kotlinx.coroutines.delay(300)
                    val actual = if (selected()) 1f else 0f
                    if (!didDrag && actual != fraction) { fraction = actual; animateToValue(actual) }
                }
            },
            onDrag = { _, dragAmount ->
                travel += dragAmount.x
                if (!didDrag && kotlin.math.abs(travel) > slop) didDrag = true
                if (didDrag) {
                    val delta = dragAmount.x / dragWidth
                    fraction =
                        if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                        else (fraction - delta).fastCoerceIn(0f, 1f)
                }
            }
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { fraction ->
                dampedDragAnimation.updateValue(fraction)
            }
    }
    LaunchedEffect(selected) {
        snapshotFlow { selected() }
            .collectLatest { isSelected ->
                val target = if (isSelected) 1f else 0f
                if (target != fraction) {
                    fraction = target
                    dampedDragAnimation.animateToValue(target)
                }
            }
    }

    val trackBackdrop = rememberLayerBackdrop()

    // AGY Remote: the whole switch (and its padded hit area) takes taps and drags, not just the
    // thumb, so a tap anywhere on the track toggles it.
    Box(
        modifier.keepGesture().then(dampedDragAnimation.modifier),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    val fraction = dampedDragAnimation.value
                    drawRect(lerp(trackColor, accentColor, fraction))
                }
                .size(64f.dp, 28f.dp)
        )

        Box(
            Modifier
                .graphicsLayer {
                    val fraction = dampedDragAnimation.value
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) lerp(padding, padding + dragWidth, fraction)
                        else lerp(-padding, -(padding + dragWidth), fraction)
                }
                .semantics {
                    role = Role.Switch
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 0.75f, progress)
                            val scaleY = lerp(0f, 0.75f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            5f.dp.toPx() * progress,
                            10f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}
