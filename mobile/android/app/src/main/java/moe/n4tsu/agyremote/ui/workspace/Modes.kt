package moe.n4tsu.agyremote.ui.workspace

import moe.n4tsu.agyremote.ui.components.LiquidPill
import moe.n4tsu.agyremote.ui.components.LiquidSegmented
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.net.Mode
import moe.n4tsu.agyremote.ui.components.glass
import moe.n4tsu.agyremote.ui.components.plainClickable
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import moe.n4tsu.agyremote.ui.theme.Motion

/** Mode glyphs, matching iOS AgyMode.symbol. */
fun modeIcon(id: String): ImageVector = when (id.lowercase()) {
    "turbo" -> Icons.Filled.GppMaybe
    "default" -> Icons.Filled.VerifiedUser
    "accept_edits", "acceptedits", "accept-edits" -> Icons.Filled.Edit
    "auto" -> Icons.Filled.Settings
    "plan" -> Icons.Filled.Checklist
    else -> Icons.Filled.Tune
}

/** The five modes, used until the connector reports its own. */
@Composable
fun defaultModes(): List<Mode> {
    val ctx = LocalContext.current
    return listOf(
        Mode("default", ctx.getString(R.string.mode_default), null),
        Mode("accept_edits", ctx.getString(R.string.mode_accept_edits), null),
        Mode("auto", ctx.getString(R.string.mode_auto), null),
        Mode("plan", ctx.getString(R.string.mode_plan), null),
        Mode("turbo", ctx.getString(R.string.mode_turbo), null),
    )
}

/**
 * Mode chips (spec 5.1): a glass capsule; the selected chip is a filled pill (accentFill, with a
 * shield-check glyph for Default) that slides between chips with the segmented spring.
 */
@Composable
fun ModeChips(modes: List<Mode>, selected: String, onSelect: (String) -> Unit) {
    val c = LocalAppColors.current
    LiquidPill(
        count = modes.size, selected = modes.indexOfFirst { it.id == selected }.coerceAtLeast(0),
        onSelect = { onSelect(modes[it].id) }, thumbColor = c.accentFill, itemHeight = 30.dp, inset = 3.dp,
    ) { i, on ->
        val m = modes[i]
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (on && m.id == "default") Icon(Icons.Filled.VerifiedUser, null, Modifier.size(12.dp), tint = c.onAccentFill)
            Text(m.name, style = AppType.sans(12.5f, if (on) FontWeight.SemiBold else FontWeight.Medium, if (on) c.onAccentFill else c.onSurface), maxLines = 1)
        }
    }
}

private fun androidx.compose.ui.layout.LayoutCoordinates.positionInParent() =
    parentLayoutCoordinates?.localPositionOf(this, androidx.compose.ui.geometry.Offset.Zero) ?: androidx.compose.ui.geometry.Offset.Zero
