package moe.n4tsu.agyremote.ui.workspace

import androidx.compose.material.icons.outlined.SwapVert
import moe.n4tsu.agyremote.state.Texts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import moe.n4tsu.agyremote.ui.components.glassSource
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.net.ChatState
import moe.n4tsu.agyremote.net.ChatSummary
import moe.n4tsu.agyremote.net.Folder
import moe.n4tsu.agyremote.net.Model
import moe.n4tsu.agyremote.state.DeviceStatus
import moe.n4tsu.agyremote.state.Haptics
import moe.n4tsu.agyremote.state.WorkspaceViewModel
import moe.n4tsu.agyremote.ui.components.ComposeIcon
import moe.n4tsu.agyremote.ui.components.GlassCircleButton
import moe.n4tsu.agyremote.ui.components.RunningActivityIcon
import moe.n4tsu.agyremote.ui.components.ThinkingIndicator
import moe.n4tsu.agyremote.ui.components.glass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import moe.n4tsu.agyremote.ui.components.plainClickable
import moe.n4tsu.agyremote.ui.theme.AppType
import moe.n4tsu.agyremote.ui.theme.LocalAppColors
import moe.n4tsu.agyremote.ui.theme.Motion

/** Identity of a folder across computers (the same path can exist on two hosts). */
val Folder.hostPathKey: String get() = "$host|$path"

/** Key of the folder a chat lives in, matching [hostPathKey]. */
val ChatSummary.folderKey: String? get() = folder?.let { "$host|$it" }

/** Project icon from the connector's `kind` hint. */
fun Folder.glyph(): ImageVector = when (kind?.lowercase()) {
    "swift" -> Icons.Filled.Code
    "rust" -> Icons.Filled.Settings
    "node" -> Icons.Filled.Inventory2
    "python" -> Icons.Filled.Terminal
    "go" -> Icons.Filled.Layers
    else -> Icons.Filled.Folder
}

/** Short stack name beside the project, when known. */
val Folder.stackLabel: String?
    get() = when (kind?.lowercase()) {
        "swift" -> "Swift"; "rust" -> "Rust"; "node" -> "Node"; "python" -> "Python"; "go" -> "Go"; else -> null
    }

/** Everything the list needs, computed once per change of chats/folders/query rather than per row. */
@Immutable
private class SidebarModel(
    val groups: List<Pair<Folder, List<ChatSummary>>>,
    val loose: List<ChatSummary>,
    val flat: List<ChatSummary>,
    val models: List<Model>,
    val running: Set<String>,
    val busyFolders: Set<String>,
    val folderOf: Map<String, Folder>,
)

private fun buildModel(
    folders: List<Folder>, chats: List<ChatSummary>, models: List<Model>, query: String,
    flatMode: Boolean, sort: String, isRunning: (ChatSummary) -> Boolean,
): SidebarModel {
    val q = query.trim()
    fun hit(s: String) = q.isEmpty() || s.contains(q, ignoreCase = true)
    val byKey = HashMap<String, Folder>(folders.size * 2)
    val byPath = HashMap<String, Folder>(folders.size * 2)
    folders.forEach { byKey.putIfAbsent(it.hostPathKey, it); byPath.putIfAbsent(it.path, it) }
    fun folderFor(c: ChatSummary): Folder? = c.folderKey?.let(byKey::get) ?: c.folder?.let(byPath::get)

    val running = HashSet<String>()
    val busy = HashSet<String>()
    val inFolder = LinkedHashMap<String, MutableList<ChatSummary>>()
    val loose = ArrayList<ChatSummary>()
    val folderOf = HashMap<String, Folder>()
    for (c in chats) {
        val f = folderFor(c)
        if (isRunning(c)) { running += c.id; f?.let { busy += it.hostPathKey } }
        if (f != null) folderOf[c.id] = f
        if (!hit(c.title)) continue
        if (f != null) inFolder.getOrPut(f.hostPathKey) { ArrayList() } += c else loose += c
    }
    val newest = HashMap<String, Long>()
    for (c in chats) folderFor(c)?.let { f -> newest.merge(f.hostPathKey, c.updatedAt, ::maxOf) }
    val byName = compareBy<Folder, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    // "recent": the folder with the newest chat first, folders without chats last; else by name.
    val order = if (sort == "name") byName else compareByDescending<Folder> { newest[it.hostPathKey] ?: Long.MIN_VALUE }.then(byName)
    val groups = folders.distinctBy { it.hostPathKey }.sortedWith(order).mapNotNull { f ->
        val list = inFolder[f.hostPathKey].orEmpty()
        if (q.isNotEmpty() && list.isEmpty() && !hit(f.name)) null else f to list
    }
    return SidebarModel(
        groups = groups,
        loose = loose,
        flat = if (flatMode) chats else emptyList(),
        models = if (q.isEmpty()) emptyList() else models.filter { hit(it.name) },
        running = running, busyFolders = busy, folderOf = folderOf,
    )
}

/** Sidebar drawer content: inline search, quick rows, projects, computer footer. */
@Composable
fun Sidebar(
    vm: WorkspaceViewModel,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onComputers: () -> Unit,
) {
    val c = LocalAppColors.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val flatMode = !vm.groupByFolder && q.isEmpty()
    // Searching shows every match, folded or not.
    val folded = vm.groupByFolder && vm.projectsCollapsed && q.isEmpty()

    // Built once per change of its inputs; rows read only the result.
    val model by remember(q, flatMode, vm.folderSort) {
        derivedStateOf {
            buildModel(vm.folders, vm.chats.toList(), vm.models, q, flatMode, vm.folderSort) { vm.chatIsRunning(it) }
        }
    }
    val nothingFound = q.isNotEmpty() && model.groups.isEmpty() && model.loose.isEmpty() && model.models.isEmpty()

    fun newChat(folder: Folder?) {
        if (folder != null && !vm.folderIsOpen(folder)) vm.toggleFolder(folder)
        vm.newChat(folder?.path ?: vm.currentFolder)
        onClose()
    }
    fun open(chat: ChatSummary) { vm.openChat(chat.id); onClose() }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(vm.sidebarRevealTick) {
        if (vm.sidebarRevealTick == handledRevealTick) return@LaunchedEffect
        handledRevealTick = vm.sidebarRevealTick
        val id = vm.currentChatId ?: return@LaunchedEffect
        val chat = vm.chats.firstOrNull { it.id == id } ?: return@LaunchedEffect
        val key = chat.folderKey
        val f = key?.let { k -> vm.folders.firstOrNull { it.hostPathKey == k } } ?: chat.folder?.let { p -> vm.folders.firstOrNull { it.path == p } }
        if (f != null && !vm.folderIsOpen(f)) vm.toggleFolder(f)
        kotlinx.coroutines.delay(if (vm.sidebarOpen) 380 else 120)
        val i = listState.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.endsWith(":$id") == true }?.index
            ?: rowIndexOf(id, model, vm, q, folded)
        if (i != null && i >= 0) listState.animateScrollToItem(i, -listState.layoutInfo.viewportSize.height / 3)
    }
    // Glass backdrop for the top and bottom bars: the sidebar surface plus the list, recorded behind.
    moe.n4tsu.agyremote.ui.components.ProvideGlass(null) { Box(Modifier.fillMaxSize()) {
    Box(Modifier.matchParentSize().glassSource().background(c.surface))
    moe.n4tsu.agyremote.ui.components.AppMenuHost(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        SidebarHeader(searching, query, onQuery = { query = it },
            onStartSearch = { searching = true },
            onClose = { if (searching) { searching = false; query = "" } else onClose() })

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = 18.dp.toPx() / size.height
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, fade to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp),
        ) {
            if (q.isEmpty()) {
                item(key = "new") {
                    val folder = vm.currentFolder?.let { p -> vm.folders.firstOrNull { it.path == p } }
                    DrawerRow(highlighted = vm.isEmptyChat, onClick = { newChat(folder) }) {
                        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { ComposeIcon(19.dp) }
                        Text(stringResource(R.string.new_chat), style = AppType.sidebarItem.copy(color = c.onSurface), modifier = Modifier.padding(start = 14.dp).weight(1f))
                        val name = folder?.name ?: vm.folderName
                        if (name != null) {
                            Icon(folder?.glyph() ?: Icons.Filled.Folder, null, Modifier.size(12.dp), tint = c.onSurfaceVariant)
                            Text(name, style = AppType.sans(12.5f, color = c.onSurfaceMuted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 5.dp).widthIn(max = 140.dp))
                        }
                    }
                }
                if (vm.modes.isNotEmpty()) item(key = "mode") { ModeRow(vm) }
                if (vm.models.isNotEmpty()) item(key = "model") { ModelRow(vm) }
                item(key = "gap") { Spacer(Modifier.height(20.dp)) }
            }
            if (nothingFound) {
                item(key = "none") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(28.dp), tint = c.onSurfaceMuted)
                        Text(stringResource(R.string.no_matches), style = AppType.sans(14.5f, color = c.onSurfaceVariant))
                    }
                }
                return@LazyColumn
            }
            item(key = "projects") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (vm.groupByFolder) R.string.projects else R.string.all), style = AppType.sans(14.5f, color = c.onSurfaceVariant), modifier = Modifier.weight(1f))
                    SortMenu(vm)
                    if (vm.groupByFolder) {
                        val label = stringResource(R.string.projects)
                        val state = Texts.get(LocalContext.current, if (folded) "collapsed" else "expanded", if (folded) "Collapsed" else "Expanded")
                        val turn by androidx.compose.animation.core.animateFloatAsState(if (folded) 0f else 90f, FolderSpring(), label = "projects")
                        Box(Modifier.size(34.dp).semantics { contentDescription = label; stateDescription = state }.plainClickable { vm.toggleProjectsCollapsed() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp).rotate(turn), tint = c.onSurfaceVariant)
                        }
                    }
                }
            }
            val folders = vm.folders
            if (folders.isEmpty() && vm.chats.isEmpty()) {
                if (vm.status != DeviceStatus.CONNECTED || vm.info == null) item(key = "connecting") {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThinkingIndicator(vm.indicatorStyle, 14.dp)
                        Text(stringResource(R.string.connecting_to_agy), style = AppType.sans(14.5f, color = c.onSurfaceVariant))
                    }
                }
            } else if (flatMode) {
                items(model.flat, key = { "a:" + it.id }) { chat ->
                    ChatRow(chat, vm.currentChatId == chat.id, chat.id in model.running, model.folderOf[chat.id], indented = false, status = "") { open(chat) }
                }
            } else {
                if (!folded) model.groups.forEach { (folder, chats) ->
                    val key = folder.hostPathKey
                    val isOpen = q.isNotEmpty() || vm.folderIsOpen(folder)
                    item(key = "f:$key") {
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = FolderSpring())) {
                        FolderRow(
                            folder, chats.isNotEmpty(), isOpen,
                            current = vm.currentChatId == null && vm.isEmptyChat && vm.currentFolder == folder.path,
                            busy = key in model.busyFolders,
                            onToggle = {
                                if (chats.isEmpty()) newChat(folder)
                                else vm.toggleFolder(folder)
                            },
                            onNew = { newChat(folder) },
                        )
                        }
                    }
                    if (isOpen) items(chats, key = { "c:$key:" + it.id }) { chat ->
                        Box(Modifier.animateItem(fadeInSpec = FolderSpring(), fadeOutSpec = FolderSpring(), placementSpec = FolderSpring())) {
                        ChatRow(chat, vm.currentChatId == chat.id, chat.id in model.running, null, indented = true, status = if (chat.id in model.running) vm.liveStatus(chat.id) else "") { open(chat) }
                        }
                    }
                    item(key = "s:$key") { Spacer(Modifier.height(8.dp).animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = FolderSpring())) }
                }
                if (model.loose.isNotEmpty()) item(key = "loose") {
                    Text(Texts.get(LocalContext.current, "sessions_without_a_project", "Sessions without a project"),
                        style = AppType.sans(14.5f, color = c.onSurfaceVariant),
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp).animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = FolderSpring()))
                }
                items(model.loose, key = { "l:" + it.id }) { chat ->
                    Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = FolderSpring())) {
                    ChatRow(chat, vm.currentChatId == chat.id, chat.id in model.running, null, indented = false, status = if (chat.id in model.running) vm.liveStatus(chat.id) else "") { open(chat) }
                    }
                }
            }
            if (model.models.isNotEmpty()) {
                item(key = "models") {
                    Text(stringResource(R.string.models), style = AppType.sans(14.5f, color = c.onSurfaceVariant), modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
                }
                items(model.models, key = { "m:" + it.id }) { m ->
                    DrawerRow(highlighted = m.id == vm.selectedModel?.id, onClick = { vm.selectModel(m); onClose() }) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(20.dp), tint = c.onSurface)
                        Text(m.name, style = AppType.sidebarItem.copy(color = c.onSurface), modifier = Modifier.padding(start = 14.dp))
                    }
                }
            }
        }

        SidebarFooter(vm, onComputers, onSettings)
    } } } }
}

@Composable
private fun SidebarHeader(searching: Boolean, query: String, onQuery: (String) -> Unit, onStartSearch: () -> Unit, onClose: () -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Row(Modifier.weight(1f).height(44.dp).glass(CircleShape).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.search), style = AppType.sans(16.5f, color = c.onSurfaceMuted))
                    BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                        textStyle = AppType.sans(16.5f, color = c.onSurface), cursorBrush = SolidColor(c.primary),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search))
                }
                if (query.isNotEmpty()) {
                    val clear = stringResource(R.string.clear_search)
                    Icon(Icons.Filled.Cancel, clear, Modifier.size(18.dp).plainClickable { onQuery("") }, tint = c.onSurfaceMuted)
                }
            }
        } else {
            Text(stringResource(R.string.agy_remote_title), style = AppType.sidebarTitle.copy(color = c.onSurface), maxLines = 1, modifier = Modifier.padding(start = 8.dp).weight(1f))
            GlassCircleButton(44.dp, onStartSearch, contentDescription = stringResource(R.string.search)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(19.dp), tint = c.onSurface)
            }
        }
        if (searching || !LocalSidebarPinned.current) {
            GlassCircleButton(44.dp, onClose, contentDescription = stringResource(if (searching) R.string.close_search else R.string.close)) {
                Icon(Icons.Filled.Close, null, Modifier.size(18.dp), tint = c.onSurface)
            }
        }
    }
}

@Composable
private fun SidebarFooter(vm: WorkspaceViewModel, onComputers: () -> Unit, onSettings: () -> Unit) {
    val c = LocalAppColors.current
    val device = vm.selectedDevice
    val status = vm.status
    val line = when {
        device == null -> null
        status == DeviceStatus.CONNECTED -> {
            val v = vm.info?.agyVersion.orEmpty()
            if (v.isEmpty()) "${vm.latencyMs}ms" else "agy $v · ${vm.latencyMs}ms"
        }
        status == DeviceStatus.STANDBY -> stringResource(R.string.connecting)
        else -> stringResource(R.string.offline)
    }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).plainClickable(onClick = onComputers), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkspaceComputerRing(device?.hardwareModel.orEmpty(), status == DeviceStatus.CONNECTED, size = 38.dp, glyph = 15.dp, inset = 1.dp, offlineAlpha = 1f,
                modifier = Modifier.glass(CircleShape))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(device?.name.orEmpty(), style = AppType.sans(15f, FontWeight.Medium, c.onSurface), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line != null) Text(line, style = AppType.sans(12.5f, color = c.onSurfaceVariant), maxLines = 1)
            }
        }
        GlassCircleButton(40.dp, onSettings, contentDescription = stringResource(R.string.settings)) {
            Icon(Icons.Outlined.Settings, null, Modifier.size(21.dp), tint = c.onSurface)
        }
    }
}

/** Capsule row: the selected one sits on a pill; a pressed one gets a faint fill and shrinks a touch. */
@Composable
private fun DrawerRow(highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = LocalAppColors.current
    var pressed by remember { mutableStateOf(false) }
    val tap by androidx.compose.runtime.rememberUpdatedState(onClick)
    val s by animateFloatAsState(if (pressed) 0.985f else 1f, Motion.listPress(), label = "press")
    val fill = when { highlighted -> c.sidebarActivePill; pressed -> c.onSurface.copy(alpha = 0.06f); else -> Color.Transparent }
    Row(
        modifier.fillMaxWidth().height(46.dp).graphicsLayer { scaleX = s; scaleY = s }
            .background(fill, CircleShape)
            // A tap only when the finger stays put: a quick flick of the list must never open the
            // row under it (the plain clickable fired whenever the finger lifted inside the row).
            .semantics { role = androidx.compose.ui.semantics.Role.Button; onClick { tap(); true } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    var moved = false
                    var up = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop || ch.isConsumed) moved = true
                        if (!ch.pressed) { up = true; break }
                        if (moved) break
                    }
                    pressed = false
                    if (up && !moved) tap()
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun ModeRow(vm: WorkspaceViewModel) {
    val c = LocalAppColors.current
    var open by remember { mutableStateOf(false) }
    val current = vm.selectedMode
    val modeA11y = stringResource(R.string.mode_label_a11y, current?.name.orEmpty())
    Box {
        DrawerRow(false, onClick = { open = true }, Modifier.semantics(mergeDescendants = true) { contentDescription = modeA11y }) {
            Icon(modeIcon(vm.currentModeId), null, Modifier.size(20.dp), tint = if (vm.currentModeId.lowercase() == "turbo") c.warningAmber else c.onSurface)
            Text(current?.name.orEmpty(), style = AppType.sidebarItem.copy(color = c.onSurface), maxLines = 1, modifier = Modifier.padding(start = 14.dp).weight(1f))
        }
        moe.n4tsu.agyremote.ui.components.AppMenu(open, { open = false }, align = moe.n4tsu.agyremote.ui.components.AppMenuAlign.START) {
            vm.modes.forEach { m -> item(m.name, icon = modeIcon(m.id), checked = m.id == vm.currentModeId) { vm.selectMode(m.id) } }
        }
    }
}

@Composable
private fun ModelRow(vm: WorkspaceViewModel) {
    val c = LocalAppColors.current
    var open by remember { mutableStateOf(false) }
    val model = vm.selectedModel
    val effort = vm.selectedEffort
    val title = when {
        model == null -> ""
        effort != null -> "${model.name} · ${effort.name}"
        else -> model.name
    }
    val modelA11y = stringResource(R.string.model_label_a11y, model?.name.orEmpty())
    Box {
        DrawerRow(false, onClick = { open = true }, Modifier.semantics(mergeDescendants = true) { contentDescription = modelA11y }) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(20.dp), tint = c.onSurface)
            Text(title, style = AppType.sidebarItem.copy(color = c.onSurface), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp).weight(1f))
        }
        val effortHeader = model?.let { "${stringResource(R.string.effort)} — ${it.compactTitle}" }.orEmpty()
        val modelHeader = stringResource(R.string.model)
        moe.n4tsu.agyremote.ui.components.AppMenu(open, { open = false }, align = moe.n4tsu.agyremote.ui.components.AppMenuAlign.START) {
            val efforts = model?.efforts.orEmpty()
            if (efforts.isNotEmpty()) {
                section(effortHeader)
                efforts.forEach { e -> item(e.name, checked = e.id == effort?.id) { vm.selectEffort(e.id) } }
            }
            section(modelHeader)
            vm.models.forEach { m -> item(m.name, checked = m.id == model?.id) { vm.selectModel(m) } }
        }
    }
}

@Composable
private fun FolderRow(folder: Folder, hasChats: Boolean, open: Boolean, current: Boolean, busy: Boolean, onToggle: () -> Unit, onNew: () -> Unit) {
    val c = LocalAppColors.current
    val ctx = LocalContext.current
    // iOS: .spring(response: 0.26, dampingFraction: 0.88).
    val rot by animateFloatAsState(if (open) 90f else 0f, FolderSpring(), label = "chev")
    val state = if (!hasChats) "" else stringResource(if (open) R.string.expanded else R.string.collapsed)
    Row(
        Modifier.fillMaxWidth()
            .background(if (current) c.sidebarActivePill.copy(alpha = 0.65f) else Color.Transparent, CircleShape)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.weight(1f).semantics { stateDescription = state }
                .plainClickable { onToggle() }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                Icon(folder.glyph(), null, Modifier.size(17.dp), tint = c.onSurface.copy(alpha = 0.86f))
            }
            // Name and badge take the free width (the name truncates), so the running icon and the
            // chevron sit at the trailing end, just before the + button, as on iOS.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(folder.name, style = AppType.sans(16f, FontWeight.Medium, c.onSurface), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                folder.stackLabel?.let {
                    Text(it, style = AppType.sans(11f, FontWeight.Medium, c.onSurfaceMuted),
                        modifier = Modifier.background(c.surfaceContainerHigh, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            if (busy && !open) RunningActivityIcon(13.5.dp)
            if (hasChats) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(15.dp).rotate(rot), tint = c.onSurfaceMuted)
        }
        val label = stringResource(R.string.new_chat_in_x, folder.name)
        Box(Modifier.size(32.dp).semantics { contentDescription = label }.plainClickable(onClick = onNew), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, selected: Boolean, running: Boolean, badge: Folder?, indented: Boolean, status: String, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val working = if (running) stringResource(R.string.working) else ""
    DrawerRow(selected, onClick, Modifier.semantics { stateDescription = working }) {
        Row(Modifier.weight(1f).padding(start = if (indented) 28.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(chat.title.ifEmpty { stringResource(R.string.untitled_chat) }, style = AppType.sidebarItem.copy(color = c.onSurface),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (running && status.isNotEmpty() && badge == null) {
                Text(status, style = AppType.sans(11.5f, FontWeight.Medium, c.primary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 88.dp))
            }
            if (running) RunningActivityIcon(14.dp)
            if (chat.state == ChatState.OPEN_IN_CLI) Text(stringResource(R.string.open_in_cli_short), style = AppType.sans(12f, color = c.onSurfaceMuted))
            if (badge != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(badge.glyph(), null, Modifier.size(11.dp), tint = c.onSurfaceVariant)
                    Text(badge.name, style = AppType.sans(12f, color = c.onSurfaceMuted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
                }
            }
        }
    }
}

/** The reveal request already handled (the drawer is rebuilt each time it opens). */
private var handledRevealTick = 0

/** Index of a chat's row in the list, mirroring the item order the LazyColumn emits. */
private fun rowIndexOf(id: String, m: SidebarModel, vm: WorkspaceViewModel, q: String, folded: Boolean): Int? {
    var i = 0
    if (q.isEmpty()) { i += 1; if (vm.modes.isNotEmpty()) i++; if (vm.models.isNotEmpty()) i++; i++ }
    i++ // projects header
    if (!vm.groupByFolder && q.isEmpty()) return m.flat.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(i)
    if (!folded) for ((f, chats) in m.groups) {
        i++
        val key = f.hostPathKey
        val open = q.isNotEmpty() || vm.folderIsOpen(f)
        if (open) { val j = chats.indexOfFirst { it.id == id }; if (j >= 0) return i + j; i += chats.size }
        i++
    }
    if (m.loose.isNotEmpty()) i++ // "Sessions without a project" header
    return m.loose.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(i)
}

/** Order of the projects (recent / name), and the switch to one flat list of every chat. */
@Composable
private fun SortMenu(vm: WorkspaceViewModel) {
    val c = LocalAppColors.current
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val label = Texts.get(ctx, "sort_projects", "Sort projects")
    Box {
        Box(Modifier.size(34.dp).semantics { contentDescription = label }.plainClickable { open = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.SwapVert, null, Modifier.size(19.dp), tint = c.onSurfaceVariant)
        }
        moe.n4tsu.agyremote.ui.components.AppMenu(open, { open = false }, alignChecks = true) {
            section(label)
            item(Texts.get(ctx, "recently_used", "Recently used"), checked = vm.folderSort == "recent") { vm.updateFolderSort("recent") }
            item(Texts.get(ctx, "name", "Name"), checked = vm.folderSort == "name") { vm.updateFolderSort("name") }
            section()
            item(Texts.get(ctx, "show_all_chats", "Show all chats"), checked = !vm.groupByFolder) { vm.toggleGrouping() }
        }
    }
}

/** iOS folder toggle spring: response 0.26 s, damping 0.88 (stiffness = (2π / 0.26)²). */
private fun <T> FolderSpring() = spring<T>(dampingRatio = 0.88f, stiffness = 584f)
