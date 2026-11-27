package moe.n4tsu.agyremote.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.net.ChatState
import moe.n4tsu.agyremote.net.Message
import moe.n4tsu.agyremote.state.DeviceStatus
import moe.n4tsu.agyremote.state.GlowState
import moe.n4tsu.agyremote.state.Haptics
import moe.n4tsu.agyremote.state.WorkspaceViewModel
import moe.n4tsu.agyremote.ui.chat.AvailableAgainBanner
import moe.n4tsu.agyremote.ui.chat.CenterStatus
import androidx.compose.ui.zIndex
import moe.n4tsu.agyremote.ui.chat.ConnectionStatus
import moe.n4tsu.agyremote.ui.chat.MessageActions
import moe.n4tsu.agyremote.ui.chat.PermissionCard
import moe.n4tsu.agyremote.ui.chat.PlanCard
import moe.n4tsu.agyremote.ui.chat.StreamingText
import moe.n4tsu.agyremote.ui.chat.UnavailableHistory
import moe.n4tsu.agyremote.ui.components.ComputerRing
import moe.n4tsu.agyremote.ui.components.barEdgeFade
import moe.n4tsu.agyremote.ui.components.reportEdge
import moe.n4tsu.agyremote.ui.components.GlassCircleButton
import moe.n4tsu.agyremote.ui.components.LocalHazeState
import moe.n4tsu.agyremote.ui.components.ProvideGlass
import moe.n4tsu.agyremote.ui.components.glassSource
import moe.n4tsu.agyremote.ui.components.Melt
import moe.n4tsu.agyremote.ui.components.glass
import moe.n4tsu.agyremote.ui.components.plainClickable
import moe.n4tsu.agyremote.ui.effects.AmbientBackdrop
import moe.n4tsu.agyremote.ui.effects.HeaderGlow
import moe.n4tsu.agyremote.ui.sheets.AddSheet
import moe.n4tsu.agyremote.ui.sheets.ComputerPickerSheet
import moe.n4tsu.agyremote.ui.sheets.FolderPickerSheet
import moe.n4tsu.agyremote.ui.sheets.ModelPickerSheet
import moe.n4tsu.agyremote.ui.sheets.NotificationsExplainerSheet
import moe.n4tsu.agyremote.ui.sheets.QrScannerSheet
import moe.n4tsu.agyremote.ui.sheets.SearchSheet
import moe.n4tsu.agyremote.ui.sheets.SettingsSheet
import moe.n4tsu.agyremote.ui.sheets.UsageSheet
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import moe.n4tsu.agyremote.ui.theme.Metrics
import moe.n4tsu.agyremote.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import kotlin.math.abs
import moe.n4tsu.agyremote.ui.chat.ChatTranscript
import moe.n4tsu.agyremote.ui.chat.ZeroStateView
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.unit.Dp
import moe.n4tsu.agyremote.net.Model
import kotlin.math.max

enum class Sheet { MODEL, USAGE, ADD, SETTINGS, COMPUTERS, SEARCH, SCANNER, NOTIFICATIONS }

@Composable
fun WorkspaceScreen(vm: WorkspaceViewModel) {
    val c = LocalAppColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haze = remember { HazeState() }
    val composer = remember { ComposerState() }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val drawer = remember { Animatable(0f) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dir = if (rtl) -1f else 1f
    val focus = LocalFocusManager.current

    // The view model owns whether the sidebar is open; the drawer animates to follow it.
    BackHandler(vm.sidebarOpen) { vm.hideSidebar() }
    fun openDrawer() { focus.clearFocus(); vm.showSidebar() }
    fun closeDrawer() { vm.hideSidebar() }
    /** End of a drag: ask the view model, or spring back when its state doesn't change. */
    fun settle(open: Boolean) {
        when {
            open && !vm.sidebarOpen -> vm.showSidebar()
            !open && vm.sidebarOpen -> vm.hideSidebar()
            else -> scope.launch { if (open) drawer.animateTo(1f, Motion.drawerOpen()) else drawer.animateTo(0f, Motion.drawerClose()) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.surface)) {
        if (maxWidth >= TabletMetrics.PIN_FROM) {
            LaunchedEffect(Unit) { if (vm.sidebarOpen) vm.hideSidebar() }
            PinnedWorkspace(vm, composer, haze, onSheet = { sheet = it })
            return@BoxWithConstraints
        }
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        // Only these booleans are read in composition; the drawer's position is read in layers.
        val sidebarShown by remember { derivedStateOf { drawer.value > 0.001f || drawer.targetValue > 0f } }
        val sidebarOpen by remember { derivedStateOf { drawer.targetValue >= 1f && drawer.value > 0.5f } }
        LaunchedEffect(Unit) {
            snapshotFlow { vm.sidebarOpen }.collect { want ->
                if (want) { focus.clearFocus(); drawer.animateTo(1f, Motion.drawerOpen()) }
                else drawer.animateTo(0f, Motion.drawerClose())
            }
        }

        Box(Modifier.fillMaxSize()) {
            // Content: shifts 28 % and dims to 48 % under the drawer; takes no input while it's open.
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = drawer.value * 0.28f * widthPx * dir }) {
                ProvideGlass(haze) {
                    // The open drawer covers the chat area completely: pause the ambient effects.
                    androidx.compose.runtime.CompositionLocalProvider(moe.n4tsu.agyremote.ui.effects.LocalAmbientCovered provides { drawer.value > 0.995f }) {
                        moe.n4tsu.agyremote.ui.components.AppMenuHost(Modifier.fillMaxSize()) {
                        ChatArea(vm, composer, haze, onSheet = { sheet = it }, onMenu = ::openDrawer)
                    }
                    }
                }
                if (sidebarShown) {
                    // The scrim ignores touches (iOS allowsHitTesting(false)); it only draws.
                    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = 0.48f * drawer.value)) })
                }
                if (sidebarOpen) {
                    // Swallows input to the shifted content (iOS .disabled while open).
                    Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture { awaitFirstDown(requireUnconsumed = false); do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed }) }
                    })
                }
            }
            if (sidebarShown) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { translationX = (drawer.value - 1f) * widthPx * dir }
                        .drawerDrag(drawer, widthPx, dir, opening = false, scope = scope, onEnd = ::settle),
                ) {
                    Sidebar(
                        vm, onClose = ::closeDrawer,
                        onSearch = { sheet = Sheet.SEARCH },
                        onSettings = { sheet = Sheet.SETTINGS },
                        onComputers = { sheet = Sheet.COMPUTERS },
                    )
                }
            }
            // The leading-edge strip that starts an opening drag.
            if (!sidebarOpen) {
                val exclusionHalf = with(LocalDensity.current) { 100.dp.toPx() }
                Box(
                    Modifier.align(if (rtl) Alignment.CenterEnd else Alignment.CenterStart).width(24.dp).fillMaxHeight()
                        // Gesture navigation claims the screen edge for Back; keep a band of it
                        // (Android allows 200 dp per edge) so the drawer can still be dragged open.
                        .systemGestureExclusion { lc ->
                            val mid = lc.size.height / 2f
                            androidx.compose.ui.geometry.Rect(0f, mid - exclusionHalf, lc.size.width.toFloat(), mid + exclusionHalf)
                        }
                        .drawerDrag(drawer, widthPx, dir, opening = true, scope = scope, onStart = { focus.clearFocus() }, onEnd = ::settle),
                )
            }
        }
    }

    when (sheet) {
        Sheet.MODEL -> ModelPickerSheet(vm) { sheet = null }
        Sheet.USAGE -> UsageSheet(vm) { sheet = null }
        Sheet.ADD -> AddSheet(vm, composer) { sheet = null }
        Sheet.SETTINGS -> SettingsSheet(vm, onDismiss = { sheet = null }, onModel = { sheet = Sheet.MODEL }, onComputers = { sheet = Sheet.COMPUTERS }, onNotificationsExplainer = { sheet = Sheet.NOTIFICATIONS })
        Sheet.COMPUTERS -> ComputerPickerSheet(vm, onDismiss = { sheet = null }, onScan = { sheet = Sheet.SCANNER })
        Sheet.SEARCH -> SearchSheet(vm, onDismiss = { sheet = null }) { id -> sheet = null; closeDrawer(); vm.openChat(id) }
        Sheet.SCANNER -> QrScannerSheet(onDismiss = { sheet = null }) { code -> sheet = null; scope.launch { vm.pair(code) } }
        Sheet.NOTIFICATIONS -> NotificationsExplainerSheet(onDismiss = { sheet = null }) { vm.setNotifications(true); sheet = null }
        null -> {}
    }
    if (vm.folderPickerFor != null) {
        FolderPickerSheet(vm, onDismiss = { vm.folderPickerFor = null }) { folder ->
            vm.setFolderForPendingChat(folder) {
                val sent = vm.send(composer.value.text, composer.attachments.toList()) { rejected -> composer.attachments.removeAll { it.id in rejected } }
                if (sent) {
                    composer.value = androidx.compose.ui.text.input.TextFieldValue("")
                    composer.attachments.clear()
                }
            }
        }
    }
    if (vm.showRelayConsent) {
        val ctx = LocalContext.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.answerRelayConsent(false) },
            title = { Text(moe.n4tsu.agyremote.state.Texts.relayTitle(ctx)) },
            text = { Text(moe.n4tsu.agyremote.state.Texts.relayMessage(ctx)) },
            confirmButton = { androidx.compose.material3.TextButton({ vm.answerRelayConsent(true) }) { Text(moe.n4tsu.agyremote.state.Texts.relayAllow(ctx)) } },
            dismissButton = { androidx.compose.material3.TextButton({ vm.answerRelayConsent(false) }) { Text(moe.n4tsu.agyremote.state.Texts.relayNotNow(ctx)) } },
        )
    }
}

/** Wide window: the sidebar as a fixed column, a hairline, then the chat. */
@Composable
private fun PinnedWorkspace(vm: WorkspaceViewModel, composer: ComposerState, haze: HazeState, onSheet: (Sheet) -> Unit) {
    val c = LocalAppColors.current
    androidx.compose.runtime.CompositionLocalProvider(LocalSidebarPinned provides true) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(TabletMetrics.SIDEBAR).fillMaxHeight()) {
                Sidebar(
                    vm, onClose = {},
                    onSearch = { onSheet(Sheet.SEARCH) },
                    onSettings = { onSheet(Sheet.SETTINGS) },
                    onComputers = { onSheet(Sheet.COMPUTERS) },
                )
            }
            Box(Modifier.width(androidx.compose.ui.unit.Dp.Hairline).fillMaxHeight().background(c.onSurface.copy(alpha = 0.12f)))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ProvideGlass(haze) {
                    moe.n4tsu.agyremote.ui.components.AppMenuHost(Modifier.fillMaxSize()) {
                        ChatArea(vm, composer, haze, onSheet = onSheet, onMenu = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatArea(vm: WorkspaceViewModel, composer: ComposerState, haze: HazeState, onSheet: (Sheet) -> Unit, onMenu: () -> Unit) {
    val c = LocalAppColors.current
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val keyboardUp = imeBottom > 0.dp
    var composerHeight by remember { mutableStateOf(0) }
    var composerTop by remember { mutableStateOf(0f) }
    // Distance from the end as a share of the viewport; read only in the glow's layer.
    val distance = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    val showEmpty = vm.isEmptyChat && !vm.loadingChat
    val glowTarget = when (vm.glow) { GlowState.IDLE -> 0f; GlowState.WORKING -> 0.7f; GlowState.ANSWERING -> 1f; GlowState.WELCOME -> 0.5f }
    // Read only inside the glow's layer, so scrolling never recomposes this screen.
    // Only while the reader is at the newest output: it fades out as they scroll back.
    val glowDim = { (1f - distance.floatValue * 6f).coerceIn(0f, 1f) }
    // The selected computer while it isn't reachable and there's nothing else worth showing.
    val device = vm.selectedDevice
    val waiting = device != null && vm.status != DeviceStatus.CONNECTED && (vm.isEmptyChat || vm.messages.isEmpty())

    Box(Modifier.fillMaxSize()) {
        // Everything behind the floating glass.
        Box(Modifier.fillMaxSize().hazeSource(haze).glassSource().background(c.surface)) {
        // Glass drawn inside the recorded layer would sample itself and recurse until the render
        // thread overflows; everything in here uses the plain fallback instead.
        androidx.compose.runtime.CompositionLocalProvider(moe.n4tsu.agyremote.ui.components.LocalGlassBackdrop provides null) {
            if (showEmpty) {
                AmbientBackdrop(Modifier.fillMaxSize(), keyboardUp = keyboardUp, keyboardHeight = imeBottom, typingTick = composer.typingTick, swellKey = vm.currentChatId ?: "empty")
            }

            // Centred in the space between the header and the composer, so it never sits under
            // the mode chips / composer when the keyboard is up.
            Melt(waiting, Modifier.fillMaxSize().imePadding().padding(top = statusTop + Metrics.HEADER_HEIGHT.dp, bottom = with(density) { composerHeight.toDp() } + 44.dp)) {
                ConnectionStatus(device?.name.orEmpty(), device?.hardwareModel.orEmpty(), vm.status == DeviceStatus.STANDBY, vm.indicatorStyle)
            }
            // A computer just paired, until it first answers, while a chat is open: in the middle of the screen.
            Melt(!waiting && device != null && device.id == vm.newlyPaired, Modifier.fillMaxSize().zIndex(1f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.background(c.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).padding(horizontal = 28.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        moe.n4tsu.agyremote.ui.components.ThinkingIndicator(vm.indicatorStyle, 22.dp)
                        Text(stringResource(R.string.connecting_to_x, device?.name.orEmpty()), style = moe.n4tsu.agyremote.ui.theme.AppType.sans(14f, androidx.compose.ui.text.font.FontWeight.Medium, c.onSurface))
                    }
                }
            }
            when {
                waiting -> {}
                showEmpty -> Box(Modifier.fillMaxSize()) {
                    ZeroStateView(vm, composer.value.text.isBlank(), onProject = onMenu, modifier = Modifier.align(Alignment.BottomCenter).readableWidth().padding(bottom = with(density) { composerHeight.toDp() } + 6.dp))
                }
                vm.unreadableReason != null && vm.messages.isEmpty() -> UnavailableHistory(cliUnsaved = vm.unreadableReason == "cli_unsaved")
                vm.loadingChat && vm.messages.isEmpty() -> CenterStatus(vm.indicatorStyle, stringResource(R.string.loading_chat))
                else -> {
                    val composerDp = with(density) { composerHeight.toDp() }
                    val headerEdge = with(density) { (statusTop + Metrics.HEADER_HEIGHT.dp).toPx() }
                    ChatTranscript(
                        vm, list,
                        modifier = Modifier.align(Alignment.TopCenter).readableWidth().barEdgeFade(headerEdge, composerTop.takeIf { it > 0f }),
                        contentPadding = PaddingValues(top = statusTop + Metrics.HEADER_HEIGHT.dp + 18.dp, bottom = composerDp + 16.dp),
                        jumpBottom = composerDp, showJumpButton = true,
                        onDistance = { distance.floatValue = it },
                    )
                }
            }
            // Header fade over the scrolling content only; the working glow sits above it so the
            // fade never washes it out (iOS masks the content, not the glow).
            Box(
                Modifier.fillMaxWidth().height(statusTop + Metrics.HEADER_HEIGHT.dp + 26.dp)
                    .background(Brush.verticalGradient(0f to c.surface, 0.55f to c.surface.copy(alpha = 0.85f), 1f to c.surface.copy(alpha = 0f))),
            )
            HeaderGlow(glowTarget, Modifier.fillMaxWidth().fillMaxHeight(0.8f), dim = glowDim)
                }
}

        Header(vm, onMenu = onMenu, onSheet = onSheet, modifier = Modifier.statusBarsPadding())

        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Toast first, then the available-again banner (iOS order); both melt in and out.
            val toast = vm.toasts.lastOrNull()
            var lastToast by remember { mutableStateOf("") }
            if (toast != null) lastToast = toast.text
            Melt(toast != null) {
                // Errors and warnings come through here: always the whole text, on as many lines as it needs.
                Text(lastToast, style = AppType.sans(13f, FontWeight.Medium, c.onSurface), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp).glass(RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
            var lastBanner by remember { mutableStateOf("") }
            vm.releasedBanner?.let { lastBanner = it }
            Melt(vm.releasedBanner != null) { AvailableAgainBanner(lastBanner) }
        }

        // Bottom: the composer (with its mode chips on the empty chat).
        Column(
            Modifier.align(Alignment.BottomCenter).readableWidth().imePadding().navigationBarsPadding()
                .onSizeChanged { composerHeight = it.height }
                .reportEdge(bottom = false) { composerTop = it },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Composer(vm, composer, onPlus = { onSheet(Sheet.ADD) }, showModes = true)
        }
    }
}


@Composable
private fun Header(vm: WorkspaceViewModel, onMenu: () -> Unit, onSheet: (Sheet) -> Unit, modifier: Modifier) {
    val c = LocalAppColors.current
    Row(
        modifier.padding(top = 4.dp, bottom = 6.dp).fillMaxWidth().height(Metrics.HEADER_HEIGHT.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!LocalSidebarPinned.current) {
            GlassCircleButton(Metrics.HEADER_BUTTON.dp, onMenu, contentDescription = stringResource(R.string.menu)) { moe.n4tsu.agyremote.ui.components.MenuIcon() }
        }
        // Title button: "Agy" + model short name + chevron + online dot.
        // The title takes the free width (left-aligned) so the trailing buttons sit at the edge.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
        val modelLabel = stringResource(R.string.model_label_a11y, vm.selectedModel?.name.orEmpty())
        Row(
            Modifier.semantics(mergeDescendants = true) { contentDescription = modelLabel }
                .plainClickable { onSheet(Sheet.MODEL) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.brand), style = AppType.headerBrand.copy(color = c.onSurface))
            val model = vm.selectedModel?.compactTitle.orEmpty()
            if (model.isNotEmpty()) ShrinkingText(model, AppType.headerModel.copy(color = c.onSurfaceVariant), Modifier.padding(start = 5.dp).weight(1f, fill = false))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.padding(start = 6.dp).size(14.dp), tint = c.onSurfaceVariant)
            if (vm.status == DeviceStatus.CONNECTED) Box(Modifier.padding(start = 5.dp).size(6.dp).background(c.primary, CircleShape))
        }
        }
        AnimatedContent(
            vm.isEmptyChat,
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut(tween(160)) + scaleOut(targetScale = 0.9f)) },
            label = "trailing",
        ) { empty ->
            if (empty) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassCircleButton(Metrics.HEADER_BUTTON.dp, { onSheet(Sheet.SEARCH) }, contentDescription = stringResource(R.string.search)) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(19.dp), tint = c.onSurface)
                    }
                    GlassCircleButton(Metrics.HEADER_BUTTON.dp, { onSheet(Sheet.COMPUTERS) }, contentDescription = vm.selectedDevice?.name ?: stringResource(R.string.computer)) {
                        WorkspaceComputerRing(vm.selectedDevice?.hardwareModel.orEmpty(), vm.status == DeviceStatus.CONNECTED)
                    }
                }
            } else {
                ChatCapsule(vm, onSheet)
            }
        }
    }
}

/** Text that shrinks down to 80 % before truncating. */
@Composable
private fun ShrinkingText(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    var scale by remember(text) { mutableStateOf(1f) }
    Text(
        text, style = style.copy(fontSize = style.fontSize * scale), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier,
        onTextLayout = { r -> if (r.hasVisualOverflow && scale > 0.8f) scale = (scale - 0.05f).coerceAtLeast(0.8f) },
    )
}

/** In a chat: one glass capsule with the limit gauge, New chat and More. */
@Composable
private fun ChatCapsule(vm: WorkspaceViewModel, onSheet: (Sheet) -> Unit) {
    val c = LocalAppColors.current
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val newChatLabel = stringResource(R.string.new_chat)
    fun startChat() { Haptics.light(ctx); vm.newChat(vm.currentFolder) }
    Row(Modifier.height(Metrics.HEADER_BUTTON.dp).glass(CircleShape), verticalAlignment = Alignment.CenterVertically) {
        // The % of the tighter Gemini limit; no gauge at all until there's usage data.
        if (vm.limits != null || vm.context != null) Box(Modifier.size(40.dp, 42.dp), contentAlignment = Alignment.Center) {
            moe.n4tsu.agyremote.ui.components.PlanLimitGauge(vm.gaugePercent, { onSheet(Sheet.USAGE) }, stringResource(R.string.limit_gauge))
        }
        Box(Modifier.size(44.dp, 42.dp).semantics { contentDescription = newChatLabel }.plainClickable { startChat() }, contentAlignment = Alignment.Center) {
            moe.n4tsu.agyremote.ui.components.ComposeIcon(19.dp)
        }
        Box {
            Box(Modifier.size(44.dp, 42.dp).plainClickable { menu = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.more), Modifier.size(20.dp), tint = c.onSurface)
            }
            val newChatTitle = stringResource(R.string.new_chat)
            val searchTitle = stringResource(R.string.search)
            val settingsTitle = stringResource(R.string.settings)
            moe.n4tsu.agyremote.ui.components.AppMenu(menu, { menu = false }, alignChecks = true) {
                // iOS: an inline mode picker (only when the connector reports modes), then the actions.
                if (vm.modes.isNotEmpty()) {
                    vm.modes.forEach { m -> item(m.name, modeIcon(m.id), checked = m.id == vm.currentModeId) { vm.selectMode(m.id) } }
                    section()
                }
                item(newChatTitle, Icons.Outlined.EditNote) { startChat() }
                item(searchTitle, Icons.Outlined.Search) { onSheet(Sheet.SEARCH) }
                item(settingsTitle, Icons.Outlined.Settings) { onSheet(Sheet.SETTINGS) }
            }
        }
    }
}

/**
 * Drawer drag. Opening: from the edge strip, past 24 % or a 380 dp/s flick. Closing: on the sidebar,
 * back past 22 % or a flick. Only horizontal drags are claimed, so the sidebar list still scrolls.
 */
private fun Modifier.drawerDrag(
    drawer: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    widthPx: Float, dir: Float, opening: Boolean, scope: kotlinx.coroutines.CoroutineScope, onStart: () -> Unit = {}, onEnd: (Boolean) -> Unit,
): Modifier = pointerInput(widthPx, dir, opening) {
    val flick = 380.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = if (opening) 10.dp.toPx() else 14.dp.toPx()
        var total = Offset.Zero
        var claimed = false
        while (!claimed) {
            val ev = awaitPointerEvent()
            val change = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            total += change.positionChange()
            val x = total.x * dir
            if (abs(total.y) > abs(total.x) && abs(total.y) > slop) return@awaitEachGesture
            if (abs(total.x) > slop) {
                if ((opening && x > 0) || (!opening && x < 0)) { claimed = true; change.consume() } else return@awaitEachGesture
            }
        }
        onStart()
        val start = if (opening) 0f else 1f
        val tracker = VelocityTracker()
        var travel = total.x * dir
        scope.launch { drawer.snapTo((start + travel / widthPx).coerceIn(0f, 1f)) }
        horizontalDrag(down.id) { change ->
            tracker.addPosition(change.uptimeMillis, change.position)
            travel += change.positionChange().x * dir
            val v = (start + travel / widthPx).coerceIn(0f, 1f)
            scope.launch { drawer.snapTo(v) }
            change.consume()
        }
        val v = tracker.calculateVelocity().x * dir
        val open = if (opening) travel > widthPx * 0.24f || v > flick else !(travel < -widthPx * 0.22f || v < -flick)
        onEnd(open)
    }
}

/** Compact name for the header: "Gemini 3 Pro (Thinking)" → "3 Pro". */
val Model.compactTitle: String
    get() {
        var rest = name.trim(' ')
        listOf("Gemini ", "Claude ").firstOrNull { rest.startsWith(it) }?.let { rest = rest.drop(it.length) }
        val open = rest.lastIndexOf(" (")
        if (open >= 0 && rest.endsWith(")")) {
            val word = rest.substring(open + 2, rest.length - 1)
            if (word.isNotEmpty() && word.all { it.isLetter() }) rest = rest.substring(0, open)
        }
        rest = rest.trim(' ')
        return rest.ifEmpty { name }
    }

/** The computer's glyph in a ring: green when online, accentRing otherwise (iOS ComputerRing). */
@Composable
fun WorkspaceComputerRing(
    hardwareModel: String, online: Boolean, size: Dp = 42.dp, glyph: Dp = 15.dp, inset: Dp = 1.5.dp,
    offlineAlpha: Float = 0.85f, modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().padding(inset).border(1.8.dp, if (online) c.connectedGreen else c.accentRing.copy(alpha = offlineAlpha), CircleShape))
        Icon(moe.n4tsu.agyremote.ui.components.deviceIcon(hardwareModel), null, Modifier.size(glyph), tint = c.onSurface)
    }
}
