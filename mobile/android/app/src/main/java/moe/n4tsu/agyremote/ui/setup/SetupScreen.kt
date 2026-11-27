package moe.n4tsu.agyremote.ui.setup

import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.filled.Warning
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import moe.n4tsu.agyremote.ui.components.ProvideGlass
import moe.n4tsu.agyremote.ui.components.AppToggle
import moe.n4tsu.agyremote.ui.components.LiquidSegmented
import moe.n4tsu.agyremote.ui.components.liquidSquish
import moe.n4tsu.agyremote.ui.components.glassSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.state.DeviceStatus
import moe.n4tsu.agyremote.state.Haptics
import moe.n4tsu.agyremote.state.WorkspaceViewModel
import moe.n4tsu.agyremote.ui.components.BrandMark
import moe.n4tsu.agyremote.ui.components.ComputerRing
import moe.n4tsu.agyremote.ui.components.ThinkingIndicator
import moe.n4tsu.agyremote.ui.components.glass
import moe.n4tsu.agyremote.ui.components.plainClickable
import moe.n4tsu.agyremote.ui.effects.AmbientBackdrop
import moe.n4tsu.agyremote.ui.sheets.QrScannerSheet
import moe.n4tsu.agyremote.ui.sheets.NotificationsExplainerSheet
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.Brand
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import moe.n4tsu.agyremote.ui.theme.Motion
import moe.n4tsu.agyremote.state.IndicatorStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Setup (spec 3), ported from the iOS LandingView: Welcome, Demo, Connect, and after pairing
 * "You're connected." and "Good to know.". Page dots bottom-left, Next pill bottom-right.
 */
@Composable
fun SetupScreen(vm: WorkspaceViewModel) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paired = vm.isPaired
    val pageCount = if (paired) 5 else 3
    val pager = rememberPagerState { pageCount }
    var showScanner by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var showHowItWorks by remember { mutableStateOf(false) }
    val page = pager.currentPage

    LaunchedEffect(page) { Haptics.selection(context) }
    LaunchedEffect(paired) {
        if (paired) pager.animateScrollToPage(3)
    }
    // The success cue marks the first real connection, not the accepted code.
    LaunchedEffect(vm.setupConnection) {
        if (paired && vm.setupConnection == WorkspaceViewModel.SetupConnection.CONNECTED) Haptics.success(context)
    }

    var failure by remember { mutableStateOf<String?>(null) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun pair(raw: String) {
        val code = raw.trim()
        if (code.isEmpty() || pairing) return
        focusManager.clearFocus()
        failure = null
        pairing = true
        scope.launch {
            vm.pair(code); pairing = false
            // Keep the code on failure so it can be fixed instead of pasted again (iOS).
            val e = vm.pairingError
            if (e != null) failure = e else pasteText = ""
        }
    }

    ProvideGlass(null) { Box(Modifier.fillMaxSize().background(c.surface)) {
        AmbientBackdrop(Modifier.fillMaxSize().glassSource(), swellKey = Unit)

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().clipToBounds()) { p ->
                when (p) {
                    0 -> WelcomePage()
                    1 -> DemoPage(active = page == 1, indicator = vm.indicatorStyle)
                    2 -> ConnectPage(active = page == 2) { showHowItWorks = true }
                    3 -> ReadyPage(vm)
                    else -> LimitsPage()
                }
            }

            // Bottom bar
            Column(
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = 520.dp).fillMaxWidth()
                    .padding(horizontal = 24.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AnimatedVisibility(page == 2 && showPaste, enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
                    PasteField(pasteText, { pasteText = it; failure = null }, onPair = { pair(pasteText) }, onPaste = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        pasteText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    })
                }
                AnimatedVisibility(page == 2 && failure != null, enter = fadeIn(), exit = fadeOut()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Warning, null, Modifier.padding(top = 1.dp).size(15.dp), tint = c.errorRed)
                        Text(failure.orEmpty(), style = AppType.sans(13.5f, FontWeight.Medium, c.errorRed))
                    }
                }
                val showsNext = page < 2 || page == 3
                // iOS animates the bar with a spring on page changes: dots and Next fade, the
                // Scan / Start pills cross-fade in over them. Nothing pops.
                val barSpec = Motion.spring<Float>(0.36f, 0.88f)
                val nextAlpha by animateFloatAsState(if (showsNext) 1f else 0f, barSpec, label = "nextAlpha")
                var lastNextLabel by remember { mutableStateOf(0) }
                if (showsNext) lastNextLabel = if (page == 0) 0 else 1
                Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxWidth().graphicsLayer { alpha = nextAlpha }, verticalAlignment = Alignment.CenterVertically) {
                        PageDots(page, pageCount)
                        Spacer(Modifier.weight(1f))
                        Pill(if (lastNextLabel == 0) stringResource(R.string.get_started) else stringResource(R.string.next), height = 50, size = 16.5f, fill = false) {
                            if (showsNext) { Haptics.press(context); scope.launch { pager.animateScrollToPage(page + 1) } }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(page == 2, enter = fadeIn(tween(260)) + scaleIn(tween(260), 0.96f), exit = fadeOut(tween(200)) + scaleOut(tween(200), 0.96f)) {
                        Pill(
                            if (pairing) stringResource(R.string.connecting) else stringResource(R.string.scan_qr_code),
                            height = 54, size = 17f, fill = true, arrow = false,
                            leading = { if (pairing) CircularProgressIndicator(Modifier.size(18.dp), color = c.surface, strokeWidth = 2.dp) else Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp)) },
                        ) { if (!pairing) { Haptics.press(context); showScanner = true } }
                    }
                    androidx.compose.animation.AnimatedVisibility(page == 4, enter = fadeIn(tween(260)) + scaleIn(tween(260), 0.96f), exit = fadeOut(tween(200)) + scaleOut(tween(200), 0.96f)) {
                        Pill(stringResource(R.string.start), height = 54, size = 17f, fill = true) {
                            Haptics.press(context); vm.completeOnboarding()
                        }
                    }
                }
                val linkAlpha by animateFloatAsState(if (page == 2) 1f else 0f, barSpec, label = "linkAlpha")
                val privacyAlpha by animateFloatAsState(if (page == 0) 1f else 0f, barSpec, label = "privacyAlpha")
                val links = androidx.compose.ui.platform.LocalUriHandler.current
                Box {
                // The welcome page uses the same quiet slot for the privacy policy.
                Text(
                    stringResource(R.string.privacy_policy),
                    style = AppType.sans(13.5f, FontWeight.Medium, c.onSurfaceMuted),
                    modifier = Modifier.fillMaxWidth().height(32.dp).graphicsLayer { alpha = privacyAlpha }
                        .then(if (page == 0) Modifier.plainClickable { runCatching { links.openUri(moe.n4tsu.agyremote.ui.sheets.PRIVACY_URL) } } else Modifier)
                        .wrapContentHeight(Alignment.CenterVertically),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    stringResource(if (showPaste) R.string.hide_pairing_code else R.string.enter_a_pairing_code_instead),
                    style = AppType.sans(14.5f, FontWeight.Medium, c.onSurfaceVariant),
                    modifier = Modifier.fillMaxWidth().height(32.dp).graphicsLayer { alpha = linkAlpha }
                        .then(if (page == 2) Modifier.plainClickable { showPaste = !showPaste } else Modifier)
                        .wrapContentHeight(Alignment.CenterVertically),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                }
            }
        }
    } }

    if (showScanner) QrScannerSheet(onDismiss = { showScanner = false }) { showScanner = false; pair(it) }
    if (showHowItWorks) ConnectionExplainerSheet { showHowItWorks = false }
}

@Composable
private fun PageDots(page: Int, count: Int) {
    val c = LocalAppColors.current
    val desc = stringResource(R.string.page_x_of_x, page + 1, count)
    Row(Modifier.semantics { contentDescription = desc }, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == page) 18.dp else 6.dp, Motion.spring(0.3f, 0.8f), label = "dot")
            Box(Modifier.size(w, 6.dp).background(if (i == page) c.onSurface else c.onSurface.copy(alpha = 0.18f), CircleShape))
        }
    }
}

/** Next / Start / Scan pill: onSurface capsule with surface text. [fill] = full width. */
@Composable
private fun Pill(
    text: String, height: Int, size: Float, fill: Boolean, arrow: Boolean = true,
    leading: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    val c = LocalAppColors.current
    Row(
        (if (fill) Modifier.fillMaxWidth() else Modifier).height(height.dp)
            .liquidSquish().background(c.onSurface, CircleShape).plainClickable(onClick = onClick).padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(if (leading != null) 10.dp else 8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.surface) {
            leading?.invoke()
            Text(text, style = AppType.sans(size, FontWeight.SemiBold, c.surface))
            if (arrow) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(15.dp), tint = c.surface)
        }
    }
}

@Composable
private fun PasteField(value: String, onChange: (String) -> Unit, onPair: () -> Unit, onPaste: () -> Unit) {
    val c = LocalAppColors.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().height(52.dp).glass(CircleShape).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("agy-rem://pair?…", style = AppType.mono(14f, color = c.onSurfaceVariant), maxLines = 1)
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                textStyle = AppType.mono(14f, color = c.onSurface), cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onPair() }),
            )
        }
        Text(
            stringResource(if (value.isEmpty()) R.string.paste else R.string.pair),
            style = AppType.sans(14f, FontWeight.SemiBold, c.primary),
            modifier = Modifier.plainClickable { if (value.isEmpty()) onPaste() else onPair() },
        )
    }
}

// MARK: - Pages

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().fillMaxSize().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

private val TitleStyle @Composable get() = AppType.sans(38f, FontWeight.Bold, LocalAppColors.current.onSurface).copy(letterSpacing = (-0.8).sp, lineHeight = 46.sp)

@Composable
private fun WelcomePage() {
    val c = LocalAppColors.current
    var shown by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { delay(200); shown = true; delay(180); Haptics.light(context) }
    val a by animateFloatAsState(if (shown) 1f else 0f, Motion.spring(0.8f, 0.9f), label = "a")
    var cursor by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(530); cursor = !cursor } }
    PageColumn {
        BrandMark(58.dp, cursor, Modifier.padding(bottom = 30.dp).alpha(a).offset(y = (10 * (1 - a)).dp))
        Text(
            stringResource(R.string.agy_from_anywhere),
            style = AppType.sans(40f, FontWeight.Bold, c.onSurface).copy(letterSpacing = (-0.8).sp, lineHeight = 46.sp),
            maxLines = 2, modifier = Modifier.alpha(a).offset(y = (14 * (1 - a)).dp),
        )
        val links = androidx.compose.ui.platform.LocalUriHandler.current
        val repoLabel = stringResource(R.string.open_on_github)
        Row(Modifier.padding(top = 5.dp).alpha(a), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.agy_remote), style = AppType.mono(15f, FontWeight.Medium, c.onSurfaceVariant))
            Box(
                Modifier.size(44.dp).semantics { contentDescription = repoLabel; role = androidx.compose.ui.semantics.Role.Button }
                    .plainClickable { Haptics.light(context); runCatching { links.openUri(moe.n4tsu.agyremote.ui.sheets.REPO_URL) } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_github_mark), null, Modifier.size(11.dp), tint = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DemoPage(active: Boolean, indicator: IndicatorStyle) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var first = true
        val holds = longArrayOf(700, 1300, 1100, 1600, 1000, 2600)
        while (true) {
            for (s in 0..5) {
                step = s
                if (first) when (s) {
                    1 -> Haptics.light(context); 3 -> Haptics.attention(context)
                    4 -> Haptics.press(context); 5 -> Haptics.success(context)
                }
                delay(holds[s])
            }
            first = false
        }
    }
    PageColumn {
        Text(stringResource(R.string.it_runs_there_you_review_here), style = AppType.sans(40f, FontWeight.Bold, c.onSurface).copy(letterSpacing = (-0.8).sp, lineHeight = 44.sp), modifier = Modifier.padding(bottom = 10.dp))
        Text(stringResource(R.string.send_a_task_follow_along_as_it_runs_and_approve), style = AppType.sans(16f, color = c.onSurfaceVariant), modifier = Modifier.padding(bottom = 28.dp))
        Column(Modifier.height(250.dp).fillMaxWidth().animateContentSize(Motion.spring(0.45f, 0.86f)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedVisibility(step >= 1, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f).widthIn(min = 60.dp))
                    Text(stringResource(R.string.add_a_dark_mode_to_the_settings_screen), style = AppType.sans(15f, color = c.onSurface),
                        modifier = Modifier.background(c.userBubble, RoundedCornerShape(18.dp)).padding(horizontal = 15.dp, vertical = 10.dp))
                }
            }
            AnimatedVisibility(step == 2, enter = fadeIn(), exit = fadeOut()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ThinkingIndicator(IndicatorStyle.RING)
                    Text(stringResource(R.string.thinking), style = AppType.sans(13.5f, FontWeight.Medium, c.onSurfaceVariant))
                }
            }
            AnimatedVisibility(step >= 3, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                ToolLine(Icons.Outlined.Edit, "Edited  settings/theme.css", "+38 −6", c.onSurfaceVariant)
            }
            AnimatedVisibility(step == 3 || step == 4, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                DemoApproval(approved = step == 4)
            }
            AnimatedVisibility(step >= 5, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolLine(Icons.Outlined.Terminal, "npm run build", "Build succeeded", c.connectedGreen)
                    Text(stringResource(R.string.done_settings_has_a_dark_mode_now_and_the_build), style = AppType.sans(15f, color = c.onSurface))
                }
            }
        }
    }
}

@Composable
private fun ToolLine(icon: ImageVector, text: String, detail: String, detailColor: Color) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().background(c.surfaceContainer.copy(alpha = 0.85f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = c.onSurfaceVariant)
        Text(text, style = AppType.mono(13.5f, color = c.onSurface), modifier = Modifier.weight(1f), maxLines = 1)
        Text(detail, style = AppType.mono(12.5f, FontWeight.Medium, detailColor))
    }
}

@Composable
private fun DemoApproval(approved: Boolean) {
    val c = LocalAppColors.current
    val scale by animateFloatAsState(if (approved) 0.96f else 1f, label = "s")
    Row(
        Modifier.fillMaxWidth().border(1.dp, c.outlineVariant, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.run_command), style = AppType.sans(12.5f, FontWeight.Medium, c.onSurfaceVariant))
            Text("npm run build", style = AppType.mono(14f, FontWeight.Medium, c.onSurface))
        }
        Text(stringResource(R.string.deny), style = AppType.sans(13.5f, FontWeight.SemiBold, c.onSurfaceVariant), modifier = Modifier.padding(horizontal = 12.dp))
        Row(
            Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.height(32.dp).background(if (approved) c.connectedGreen else c.onSurface, CircleShape).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (approved) Icon(Icons.Filled.Check, null, Modifier.size(13.dp), tint = c.surface)
            Text(stringResource(if (approved) R.string.allowed else R.string.allow), style = AppType.sans(13.5f, FontWeight.SemiBold, c.surface))
        }
    }
}


private enum class HostPlatform(val label: String, val prompt: String, val install: String, val installed: String) {
    // One installer for macOS and Linux; it picks the right build for the machine.
    UNIX("macOS / Linux", "$ ", "curl -fsSL https://n4t.su/agy/ | sh", "✓ agy-connector installed to ~/.local/bin"),
    WINDOWS("Windows", "PS> ", "irm https://n4t.su/agy/ps | iex", "✓ agy-connector installed"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectPage(active: Boolean, onHowItWorks: () -> Unit) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    val start = "agy-connector pair"
    var platform by remember { mutableStateOf(HostPlatform.UNIX) }
    var typedInstall by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf(false) }
    var typedStart by remember { mutableIntStateOf(0) }
    var printed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var playKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(active, platform, playKey) {
        if (!active || (printed && playKey == 0 && typedInstall > 0)) return@LaunchedEffect
        typedInstall = 0; installed = false; typedStart = 0; printed = false
        delay(350)
        while (typedInstall < platform.install.length) { typedInstall++; Haptics.key(context); delay(Random.nextLong(28, 71)) }
        delay(450); installed = true; Haptics.light(context)
        delay(350)
        while (typedStart < start.length) { typedStart++; Haptics.key(context); delay(Random.nextLong(40, 91)) }
        delay(350); printed = true; Haptics.success(context)
    }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }

    PageColumn {
        Text(stringResource(R.string.two_commands_on_your_computer), style = TitleStyle, maxLines = 2, modifier = Modifier.padding(bottom = 10.dp))
        Text(stringResource(R.string.install_the_connector_start_it_then_scan_the_cod), style = AppType.sans(15.5f, color = c.onSurfaceVariant), modifier = Modifier.padding(bottom = 8.dp))
        Row(Modifier.plainClickable { Haptics.press(context); onHowItWorks() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.how_it_works), style = AppType.sans(15f, FontWeight.Medium, c.primary))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = c.primary)
        }
        Spacer(Modifier.height(12.dp))
        val platforms = HostPlatform.entries
        LiquidSegmented(
            count = platforms.size, selected = platforms.indexOf(platform),
            onSelect = { i -> val p = platforms[i]; if (platform != p) { platform = p; Haptics.selection(context); playKey++ } },
            modifier = Modifier.align(Alignment.CenterHorizontally).width(260.dp), height = 34.dp, inset = 2.dp,
        ) { i, _ -> Text(platforms[i].label, style = AppType.sans(13f, FontWeight.Medium, c.onSurface)) }
        Spacer(Modifier.height(12.dp))
        // Terminal: always dark, always left-to-right.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 236.dp)
                    .background(Color(0xFF0F1214), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(Color(0xFFFF5F57), Color(0xFFFFBD2E), Color(0xFF28C940)).forEach { Box(Modifier.size(10.dp).background(it, CircleShape)) }
                    Spacer(Modifier.weight(1f))
                    val copyDesc = stringResource(R.string.copy_install_and_start_commands)
                    Row(
                        Modifier.semantics { contentDescription = copyDesc }.plainClickable {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm?.setPrimaryClip(ClipData.newPlainText("agy-connector", platform.install + "\n" + start))
                            Haptics.success(context); copied = true
                        },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, null, Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.6f))
                        Text(stringResource(if (copied) R.string.copied else R.string.copy), style = AppType.sans(12f, FontWeight.Medium, Color.White.copy(alpha = 0.6f)))
                    }
                }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (platform == HostPlatform.WINDOWS) Text(stringResource(R.string.run_in_powershell), style = AppType.mono(13.5f, color = Color.White.copy(alpha = 0.55f)))
                    PromptLine(platform.prompt, platform.install, typedInstall, !installed)
                    AnimatedVisibility(installed, enter = fadeIn(tween(200))) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(platform.installed, style = AppType.mono(13.5f, color = Color.White.copy(alpha = 0.55f)))
                            Box(Modifier.padding(top = 4.dp)) { PromptLine(platform.prompt, start, typedStart, !printed) }
                        }
                    }
                    AnimatedVisibility(printed, enter = fadeIn() + expandVertically()) {
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            PairingQrGlyph(Modifier.size(84.dp))
                            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(stringResource(R.string.connector_ready), style = AppType.mono(13.5f, color = Brand.mint))
                                Text(stringResource(R.string.scan_to_pair), style = AppType.mono(13.5f, color = Color.White.copy(alpha = 0.7f)))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(170.dp))
    }
}

@Composable
private fun PromptLine(prompt: String, text: String, typed: Int, cursor: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(prompt, style = AppType.mono(13.5f, color = Brand.mint))
        Text(text.take(typed), style = AppType.mono(13.5f, color = Color.White), maxLines = 1, overflow = TextOverflow.Clip)
        if (cursor) Box(Modifier.size(8.dp, 16.dp).background(Color.White.copy(alpha = 0.85f)))
    }
}

/** Decorative QR-like glyph; encodes nothing. */
@Composable
private fun PairingQrGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val n = 21
        val cell = size.minDimension / n
        var seed = -0x61c8864680b583ebL
        fun next(): Long { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17); return seed }
        fun inFinder(x: Int, y: Int) = (x < 8 && y < 8) || (x >= n - 8 && y < 8) || (x < 8 && y >= n - 8)
        for (y in 0 until n) for (x in 0 until n) {
            if (inFinder(x, y)) continue
            if (java.lang.Long.remainderUnsigned(next(), 100) < 47) drawRect(Color.White, Offset(x * cell, y * cell), Size(cell, cell))
        }
        for ((fx, fy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            val o = Offset(fx * cell, fy * cell)
            drawRoundRect(Color.White, o, Size(cell * 7, cell * 7), CornerRadius(cell * 1.4f))
            drawRoundRect(Color(0xFF0F1214), o + Offset(cell, cell), Size(cell * 5, cell * 5), CornerRadius(cell))
            drawRoundRect(Color.White, o + Offset(cell * 2, cell * 2), Size(cell * 3, cell * 3), CornerRadius(cell * 0.8f))
        }
    }
}

@Composable
private fun ReadyPage(vm: WorkspaceViewModel) {
    val c = LocalAppColors.current
    val device = vm.selectedDevice
    val conn = vm.setupConnection
    val online = conn == WorkspaceViewModel.SetupConnection.CONNECTED
    val failed = conn == WorkspaceViewModel.SetupConnection.FAILED
    val context = LocalContext.current
    var explainer by remember { mutableStateOf(false) }
    if (explainer || vm.showNotificationExplainer) NotificationsExplainerSheet(onDismiss = { explainer = false; vm.showNotificationExplainer = false }, onAllowed = {}, alreadyOn = vm.notificationsEnabled, onConfirm = { explainer = false; vm.confirmNotificationsTapped() })
    PageColumn {
        androidx.compose.animation.AnimatedContent(conn, label = "readyTitle") { s ->
            Text(when (s) {
                WorkspaceViewModel.SetupConnection.CONNECTED -> stringResource(R.string.you_re_connected)
                WorkspaceViewModel.SetupConnection.FAILED -> stringResource(R.string.can_t_reach_x, device?.name.orEmpty())
                WorkspaceViewModel.SetupConnection.CONNECTING -> stringResource(R.string.connecting)
            }, style = TitleStyle, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(stringResource(R.string.your_phone_talks_to_this_computer_directly_turn), style = AppType.sans(15.5f, color = c.onSurfaceVariant), modifier = Modifier.padding(bottom = 24.dp))
        Column(
            Modifier.fillMaxWidth().background(c.surfaceContainerLow.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
                .border(1.dp, c.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
        ) {
            if (device != null) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val ring by androidx.compose.animation.animateColorAsState(
                        when (conn) { WorkspaceViewModel.SetupConnection.CONNECTED -> c.connectedGreen; WorkspaceViewModel.SetupConnection.FAILED -> c.errorRed; else -> c.warningAmber },
                        tween(450), label = "ring")
                    ComputerRing(device.hardwareModel, online, 46.dp, ringWidth = 0.dp, iconSize = 20.dp, modifier = Modifier.border(1.8.dp, ring, CircleShape))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(device.name, style = AppType.sans(17f, FontWeight.SemiBold, c.onSurface))
                        val parts = buildList {
                            if (device.hardwareModel.isNotEmpty()) add(device.hardwareModel)
                            vm.info?.agyVersion?.takeIf { it.isNotEmpty() }?.let { add("agy $it") }
                            if (!failed) add(if (online) { if (vm.latencyMs > 0) stringResource(R.string.online_x_ms, vm.latencyMs) else stringResource(R.string.online) } else stringResource(R.string.connecting))
                        }
                        Text(parts.joinToString(" · "), style = AppType.sans(13.5f, color = c.onSurfaceVariant))
                        if (failed) {
                            Text(vm.setupError.orEmpty(), style = AppType.sans(13.5f, color = c.errorRed))
                            Text(stringResource(R.string.retry), style = AppType.sans(15f, FontWeight.SemiBold, c.primary),
                                modifier = Modifier.padding(top = 4.dp).plainClickable { Haptics.press(context); vm.retrySetupConnection() })
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant.copy(alpha = 0.6f)))
            }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.notifications), style = AppType.sans(16f, FontWeight.Medium, c.onSurface))
                    Text(stringResource(R.string.only_your_phone_can_read_them_you_can_change_thi), style = AppType.sans(13f, color = c.onSurfaceVariant))
                }
                AppToggle(vm.notificationsEnabled, { on -> vm.requestNotifications(on) })
            }
        }
    }
}

@Composable
private fun LimitsPage() {
    val c = LocalAppColors.current
    PageColumn {
        Text(stringResource(R.string.good_to_know), style = TitleStyle, modifier = Modifier.padding(bottom = 24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            LimitRow(Icons.Outlined.Terminal, R.string.chats_open_in_the_agy_cli_are_read_only_here, R.string.they_update_live_close_the_chat_in_the_cli_to_re)
            LimitRow(Icons.Outlined.WarningAmber, R.string.use_a_chat_in_one_place_at_a_time, R.string.if_the_cli_and_this_app_write_to_the_same_chat_a)
            LimitRow(Icons.Outlined.Folder, R.string.cli_chats_need_a_folder, R.string.the_first_time_you_reply_to_a_chat_started_in_th)
            LimitRow(Icons.Outlined.WebAsset, R.string.antigravity_app_chats_can_t_be_opened, R.string.only_chats_from_the_agy_cli_can_be_shown_and_con)
            LimitRow(Icons.Outlined.PowerSettingsNew, R.string.keep_your_computer_on, R.string.agy_connector_must_be_running_if_a_direct_connec)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun LimitRow(icon: ImageVector, title: Int, detail: Int) {
    val c = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.padding(top = 1.dp).size(20.dp).width(24.dp), tint = c.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = AppType.sans(15.5f, FontWeight.SemiBold, c.onSurface))
            Text(stringResource(detail), style = AppType.sans(14f, color = c.onSurfaceVariant))
        }
    }
}

// MARK: - How it works

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionExplainerSheet(onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // "Got It" slides the sheet down before removing it (iOS dismiss), instead of a pop.
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismiss, sheetState = state, containerColor = c.surface) {
        Column(Modifier.padding(horizontal = 28.dp).padding(bottom = 12.dp).navigationBarsPadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Icon(Icons.Outlined.Hub, null, Modifier.padding(top = 28.dp, bottom = 18.dp).size(34.dp), tint = c.onSurface)
                Text(stringResource(R.string.how_it_works), style = AppType.sans(32f, FontWeight.Bold, c.onSurface).copy(letterSpacing = (-0.6).sp), modifier = Modifier.padding(bottom = 8.dp))
                Text(stringResource(R.string.agy_remote_has_no_server_of_its_own_and_no_accou), style = AppType.sans(16f, color = c.onSurfaceVariant), modifier = Modifier.padding(bottom = 30.dp))
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    ExplainStep("1", R.string.the_connector_makes_an_identity, R.string.on_first_run_agy_connector_creates_a_key_pair_th)
                    ExplainStep("2", R.string.the_qr_code_carries_the_address_and_a_secret, R.string.scanning_it_gives_your_phone_that_address_plus_a)
                    ExplainStep("3", R.string.your_phone_finds_the_computer, R.string.the_phone_looks_up_where_the_computer_currently)
                    ExplainStep("4", R.string.encrypted_and_checked_both_ways, R.string.the_connection_uses_quic_with_tls_1_3_and_only_c)
                    ExplainStep("5", R.string.if_a_direct_path_isn_t_possible, R.string.agy_remote_asks_before_it_passes_the_connection)
                }
                Text(stringResource(R.string.agy_runs_on_your_computer_as_usual_the_connector), style = AppType.sans(14f, color = c.onSurfaceVariant), modifier = Modifier.padding(top = 28.dp, bottom = 20.dp))
            }
            Box(Modifier.fillMaxWidth().height(54.dp).liquidSquish().background(c.accentFill, CircleShape).plainClickable(onClick = close), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.got_it), style = AppType.sans(17f, FontWeight.SemiBold, c.onAccentFill))
            }
        }
    }
}

@Composable
private fun ExplainStep(n: String, title: Int, detail: Int) {
    val c = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(26.dp).border(1.dp, c.primary.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
            Text(n, style = AppType.mono(13f, FontWeight.SemiBold, c.primary))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = AppType.sans(16f, FontWeight.SemiBold, c.onSurface))
            Text(stringResource(detail), style = AppType.sans(14.5f, color = c.onSurfaceVariant))
        }
    }
}
