package moe.n4tsu.agyremote.ui.sheets

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import moe.n4tsu.agyremote.ui.components.glassSource
import moe.n4tsu.agyremote.ui.components.ProvideGlass
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.ui.components.GlassCircleButton
import moe.n4tsu.agyremote.ui.components.glass
import moe.n4tsu.agyremote.ui.components.plainClickable
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors

/** Sheet background and grouped-row card colours. */
object SheetColors {
    val background: Color @Composable get() = LocalAppColors.current.let { if (it.isDark) it.surfaceContainerHigh else it.surfaceContainerLow }
    val card: Color @Composable get() = LocalAppColors.current.let { if (it.isDark) it.surfaceContainerHighest else it.surface }
}

/**
 * The system sheet style with a grabber and iOS detents. By default the sheet opens at the
 * .medium detent (half height) and drags up to .large; [large] = true is a .large-only sheet
 * (Settings). The content always lays out at full height so dragging between detents only moves
 * the sheet, and a scroll inside a medium sheet grows it first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(onDismiss: () -> Unit, large: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = large)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val latest = androidx.compose.runtime.rememberUpdatedState(onDismiss)
    // Closing from inside (Done, ×, picking a row) slides the sheet down first, then removes it,
    // instead of making it vanish (iOS dismiss()).
    val close: () -> Unit = remember(state) {
        { scope.launch { state.hide() }.invokeOnCompletion { latest.value() } }
    }
    // Slide down first, then run something else (e.g. present the next sheet).
    val hideThen: (() -> Unit) -> Unit = remember(state) {
        { after -> scope.launch { state.hide() }.invokeOnCompletion { after() } }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalCloseSheet provides close, LocalHideSheetThen provides hideThen) {
    ModalBottomSheet(
        onDismiss, sheetState = state, containerColor = SheetColors.background,
        // Like an iOS large sheet: keep a gap under the status bar.
        modifier = Modifier.statusBarsPadding().padding(top = 10.dp),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        // A slim grabber close to the top edge like iOS; Material's default pads it by 22dp each side.
        dragHandle = {
            Box(Modifier.padding(top = 6.dp, bottom = 2.dp).size(width = 36.dp, height = 5.dp)
                .background(LocalAppColors.current.onSurfaceMuted.copy(alpha = 0.5f), RoundedCornerShape(50)))
        },
    ) {
        // Sheet glass refracts the sheet's own surface: a sibling layer behind the content.
        ProvideGlass(null) {
            // A large sheet keeps its full height (iOS .large detent) so pushing a short sub-page
            // or filtering a list doesn't make the sheet jump.
            Box(Modifier.fillMaxWidth().fillMaxHeight()) {
                Box(Modifier.matchParentSize().glassSource().background(SheetColors.background))
                moe.n4tsu.agyremote.ui.components.AppMenuHost(Modifier.fillMaxWidth().fillMaxHeight(), base = SheetColors.background) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight(), content = content)
                }
            }
        }
    }
    }
}

/** Hides the enclosing [AppSheet] with its slide-down animation, then dismisses it. */
val LocalCloseSheet = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** Hides the enclosing [AppSheet] with its animation, then runs the given block instead of dismissing. */
val LocalHideSheetThen = androidx.compose.runtime.staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }

/** The animated close of the enclosing sheet, or [fallback] outside one. */
@Composable
fun rememberCloseSheet(fallback: () -> Unit): () -> Unit = LocalCloseSheet.current ?: fallback

/**
 * Pages inside a sheet with iOS NavigationStack motion: a page pushed from the main page slides in
 * from the trailing edge while the old one slides partly out and dims; going back reverses it.
 * [isRoot] tells which page is the root (going to it is a pop).
 */
@Composable
fun <T> SheetPages(page: T, isRoot: (T) -> Boolean, content: @Composable ColumnScope.(T) -> Unit) {
    androidx.compose.animation.AnimatedContent(
        page,
        transitionSpec = {
            val pop = isRoot(targetState)
            val spec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(350, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.15f, 1f))
            val fade = androidx.compose.animation.core.tween<Float>(300)
            if (!pop) {
                (androidx.compose.animation.slideInHorizontally(spec) { it } togetherWith
                    (androidx.compose.animation.slideOutHorizontally(spec) { -it / 3 } + androidx.compose.animation.fadeOut(fade)))
            } else {
                ((androidx.compose.animation.slideInHorizontally(spec) { -it / 3 } + androidx.compose.animation.fadeIn(fade)) togetherWith
                    androidx.compose.animation.slideOutHorizontally(spec) { it })
            }.apply { targetContentZIndex = if (pop) 0f else 1f }
        },
        label = "sheetPage",
    ) { p -> Column(Modifier.fillMaxWidth().background(SheetColors.background)) { content(p) } }
}

/** Sheet title bar: optional leading × or back, centred title, optional trailing "Done". */
@Composable
fun SheetBar(title: String, onClose: (() -> Unit)? = null, onBack: (() -> Unit)? = null, onDone: (() -> Unit)? = null) {
    val c = LocalAppColors.current
    val animatedClose = LocalCloseSheet.current
    @Suppress("NAME_SHADOWING") val onClose = onClose?.let { animatedClose ?: it }
    @Suppress("NAME_SHADOWING") val onDone = onDone?.let { animatedClose ?: it }
    Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
        when {
            onBack != null -> GlassCircleButton(40.dp, onBack, Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(26.dp), tint = c.onSurface)
            }
            onClose != null -> GlassCircleButton(40.dp, onClose, Modifier.align(Alignment.CenterStart), contentDescription = stringResource(R.string.close)) {
                Icon(Icons.Filled.Close, null, Modifier.size(19.dp), tint = c.onSurface)
            }
        }
        Text(title, style = AppType.sans(17f, FontWeight.SemiBold, c.onSurface), modifier = Modifier.align(Alignment.Center), maxLines = 1)
        if (onDone != null) {
            moe.n4tsu.agyremote.ui.components.liquid.LiquidButton(onDone, moe.n4tsu.agyremote.ui.components.rememberGlassBackdrop(), Modifier.align(Alignment.CenterEnd),
                height = 40.dp, surfaceColor = if (c.isDark) Color(0xFF1B2023).copy(alpha = 0.34f) else Color.White.copy(alpha = 0.38f)) {
                Text(stringResource(R.string.done), style = AppType.sans(16f, FontWeight.Medium, c.onSurface))
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(text, style = AppType.sans(15f, FontWeight.SemiBold, LocalAppColors.current.onSurfaceVariant), modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
fun SectionFooter(text: String) {
    Text(text, style = AppType.sans(13f, color = LocalAppColors.current.onSurfaceVariant), modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 8.dp))
}

/** An inset grouped card; rows are separated by hairlines. */
@Composable
fun Group(rows: List<@Composable () -> Unit>, inset: Dp = 60.dp) {
    val c = LocalAppColors.current
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(SheetColors.card)) {
        rows.forEachIndexed { i, row ->
            row()
            if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(start = inset, end = 20.dp), color = c.outlineVariant, thickness = 0.5.dp)
        }
    }
}

/** A 29 dp rounded icon tile, as in the Settings rows. */
@Composable
fun IconTile(icon: ImageVector, tint: Color = LocalAppColors.current.onSurface) {
    val c = LocalAppColors.current
    Box(Modifier.size(29.dp).background(c.onSurface.copy(alpha = 0.08f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
    }
}

@Composable
fun SheetRow(
    title: String,
    icon: ImageVector? = null,
    value: String? = null,
    titleColor: Color = LocalAppColors.current.onSurface,
    chevron: Boolean = false,
    minHeight: Dp = 56.dp,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight).then(if (onClick != null) Modifier.plainClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { IconTile(icon, if (titleColor == c.onSurface) c.onSurface else titleColor); Spacer(Modifier.width(12.dp)) }
        Text(title, style = AppType.sans(17f, color = titleColor), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (value != null) Text(value, style = AppType.sans(17f, color = c.onSurfaceVariant), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        trailing?.invoke(this)
        if (chevron) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 4.dp).size(20.dp), tint = c.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

