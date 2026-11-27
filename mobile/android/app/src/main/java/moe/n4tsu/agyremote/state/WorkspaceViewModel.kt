package moe.n4tsu.agyremote.state

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import moe.n4tsu.agyremote.BuildConfig
import moe.n4tsu.agyremote.net.ApiError
import moe.n4tsu.agyremote.net.SharedTransport
import moe.n4tsu.agyremote.net.Approval
import moe.n4tsu.agyremote.net.Attachment
import moe.n4tsu.agyremote.net.ChatState
import moe.n4tsu.agyremote.net.ChatSummary
import moe.n4tsu.agyremote.net.ConnectorClient
import moe.n4tsu.agyremote.net.DemoConnector
import moe.n4tsu.agyremote.net.ConnectorInfo
import moe.n4tsu.agyremote.net.ContextUsage
import moe.n4tsu.agyremote.net.Effort
import moe.n4tsu.agyremote.net.FileHit
import moe.n4tsu.agyremote.net.Folder
import moe.n4tsu.agyremote.net.Limit
import moe.n4tsu.agyremote.net.Limits
import moe.n4tsu.agyremote.net.Message
import moe.n4tsu.agyremote.net.Mode
import moe.n4tsu.agyremote.net.Model
import moe.n4tsu.agyremote.net.NativeTransport
import moe.n4tsu.agyremote.net.Options
import moe.n4tsu.agyremote.net.P2PTransport
import moe.n4tsu.agyremote.net.PairingTicket
import moe.n4tsu.agyremote.net.PlanEntry
import moe.n4tsu.agyremote.net.Result
import moe.n4tsu.agyremote.net.SUPPORTED_PROTOCOL
import moe.n4tsu.agyremote.net.StatusText
import moe.n4tsu.agyremote.net.Step
import moe.n4tsu.agyremote.net.UnavailableTransport
import moe.n4tsu.agyremote.push.PushState
import moe.n4tsu.agyremote.ui.theme.ThemeSetting
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

enum class DeviceStatus { CONNECTED, STANDBY, OFFLINE }

/** Header glow (spec 9.2). */
enum class GlowState { IDLE, WORKING, ANSWERING, WELCOME }

data class Toast(val id: Long, val text: String)

enum class VersionProblem { UPDATE_APP, UPDATE_CONNECTOR }

/** Oldest connector the app works with (iOS PairedDevice.minimumConnector). */
const val MINIMUM_CONNECTOR = "0.2.0"

/** What is known about a paired computer while the app runs (not stored). */
data class DeviceRuntime(
    val status: DeviceStatus = DeviceStatus.STANDBY,
    val latencyMs: Int = 0,
    val transport: String = "",
    val info: ConnectorInfo? = null,
    /** The last error reaching it, cleared by a sync that works. */
    val error: String? = null,
) {
    val agentVersion: String get() = info?.agyVersion.orEmpty()
    val connectorVersion: String get() = info?.connectorVersion.orEmpty()
}

/** A message held back until its CLI chat has a folder. */
data class PendingSend(val text: String, val attachments: List<Attachment>, val fromComposer: Boolean)

/**
 * Everything the workspace shows and every request it makes (docs/protocol.md). Every paired
 * computer is synced; their folders and chats are merged into one list, each tagged with its
 * computer ([ChatSummary.host], [Folder.host]), and requests about a chat go to that chat's computer.
 */
class WorkspaceViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val transport: P2PTransport = SharedTransport.get(store.secretKeyHex)
    private val ctx get() = getApplication<Application>()

    /** This phone's P2P endpoint id (diagnostics). */
    var clientEndpointId by mutableStateOf(""); private set

    init {
        val id = runCatching { transport.identity() }.getOrNull()
        id?.optString("secret_key_hex")?.takeIf { it.isNotEmpty() && it != store.secretKeyHex }?.let { store.secretKeyHex = it }
        clientEndpointId = id?.optString("endpoint_id").orEmpty()
    }

    // MARK: settings
    var theme by mutableStateOf(store.theme); private set
    var indicatorStyle by mutableStateOf(store.indicator); private set
    var notificationsEnabled by mutableStateOf(store.notificationsEnabled); private set
    var groupByFolder by mutableStateOf(store.groupByFolder); private set
    // Nothing paired means setup isn't finished, whatever an earlier run stored: otherwise pairing
    // from the setup pages would close them before "You're connected." (and iOS likewise).
    private var onboardingComplete by mutableStateOf(store.devices.isNotEmpty() && (!store.onboardingStored || store.onboardingDone))
    /** Setup pages: not onboarded, or nothing paired (iOS showsLanding). */
    val showsSetup: Boolean get() = !onboardingComplete || devices.isEmpty()

    fun updateTheme(t: ThemeSetting) { theme = t; store.theme = t }
    fun updateIndicator(s: IndicatorStyle) { indicatorStyle = s; store.indicator = s }
    fun toggleGrouping() { groupByFolder = !groupByFolder; store.groupByFolder = groupByFolder }
    /** "recent" (the newest chat first) or "name". */
    var folderSort by mutableStateOf(store.folderSort); private set
    fun updateFolderSort(s: String) { folderSort = s; store.folderSort = s }
    /** The drawer's project list is folded away (the sessions without a project stay in view). */
    var projectsCollapsed by mutableStateOf(store.projectsCollapsed); private set
    fun toggleProjectsCollapsed() { projectsCollapsed = !projectsCollapsed; store.projectsCollapsed = projectsCollapsed }

    // MARK: computers
    var devices by mutableStateOf(store.devices); private set
    var selectedDeviceId by mutableStateOf(store.selectedDeviceId?.takeIf { s -> store.devices.any { it.id == s } } ?: store.devices.firstOrNull()?.id)
        private set
    val selectedDevice: PairedDevice? get() = devices.firstOrNull { it.id == selectedDeviceId } ?: devices.firstOrNull()
    /** Live state per computer id. */
    val deviceStates = mutableStateMapOf<String, DeviceRuntime>()
    fun runtime(id: String?): DeviceRuntime = id?.let { deviceStates[it] } ?: DeviceRuntime()
    fun status(of: PairedDevice): DeviceStatus = runtime(of.id).status

    /** The selected computer's state (older single-computer API). */
    val status: DeviceStatus get() = runtime(selectedDevice?.id).status
    val latencyMs: Int get() = runtime(selectedDevice?.id).latencyMs
    val transportName: String get() = runtime(selectedDevice?.id).transport
    /** The selected computer's connector.info. */
    val info: ConnectorInfo? get() = runtime(selectedDevice?.id).info
    val isConnected: Boolean get() = status == DeviceStatus.CONNECTED
    /** The last error per computer id. */
    val errorByDevice: Map<String, String> get() = deviceStates.mapNotNull { (k, v) -> v.error?.let { k to it } }.toMap()
    var pairingError by mutableStateOf<String?>(null); private set

    var versionProblem by mutableStateOf<VersionProblem?>(null); private set
    /** iOS name of [versionProblem]. */
    val versionIssue: VersionProblem? get() = versionProblem
    var connectorUpdateNote by mutableStateOf<String?>(null); private set

    // MARK: relay
    /** The relay question is up (iOS showRelayConsent). */
    var showRelayAlert by mutableStateOf(false)
    var showRelayConsent: Boolean
        get() = showRelayAlert
        set(v) { showRelayAlert = v }
    var relayAllowed by mutableStateOf(store.relayAllowed)
        @JvmName("relayAllowedState") private set
    private var relayAsked = false
    private var relayDeclined = false
    private val failuresByDevice = HashMap<String, Int>()
    private var consecutiveFailures = 0

    // MARK: merged workspace data
    val chats = mutableStateListOf<ChatSummary>()
    /** Folders over all computers: listed ones and ones a chat uses, newest-chat first. */
    var folders by mutableStateOf<List<Folder>>(emptyList()); private set
    private val chatsByHost = HashMap<String, List<ChatSummary>>()
    private val foldersByHost = HashMap<String, List<Folder>>()
    var models by mutableStateOf<List<Model>>(emptyList()); private set
    var modes by mutableStateOf<List<Mode>>(emptyList()); private set

    // MARK: per-chat state
    /** Messages per chat id, or per new chat's draft key until the computer names it. */
    val messagesByChat = mutableStateMapOf<String, List<Message>>()
    val usageByChat = mutableStateMapOf<String, ContextUsage>()
    /** Why a chat can't be shown (`desktop_app`, `cli_unsaved`). */
    val unreadableByChat = mutableStateMapOf<String, String>()
    /** The error a chat's last turn ended with; kept across transcript reloads. */
    val turnNoticeByChat = mutableStateMapOf<String, String>()
    /** Cache keys (chat id or draft key) of this phone's turns that haven't been stopped. */
    var localRunning by mutableStateOf<Set<String>>(emptySet()); private set
    private val turns = HashMap<String, LiveTurn>()
    private var viewingDraftKey by mutableStateOf<String?>(null)
    private val loadedUpdated = HashMap<String, String>()
    private val openSeq = HashMap<String, Int>()
    private val openInFlight = HashSet<String>()
    private val optionsAdopted = HashSet<String>()
    private var optionChangesPending = 0
    private val lastRunning = HashMap<String, Boolean>()
    private val lastCliReload = HashMap<String, Long>()
    private var lastOpenInCli = emptySet<String>()
    private var pendingLinkChatId: String? = null
    private val optionsPushed = HashSet<String>()

    private class LiveTurn(
        val requestId: String, val deviceId: String, val agentMessageId: String, val draftKey: String,
        var chatId: String?, var folder: String?, val prompt: String, var status: String, var stopRequested: Boolean = false,
    ) {
        val cacheKey: String get() = chatId ?: draftKey
    }

    private fun reindexTurns() {
        val keys = turns.values.filter { !it.stopRequested }.map { it.cacheKey }.toSet()
        if (keys != localRunning) localRunning = keys
    }

    // MARK: selection
    /** The open chat (iOS selectedChatId). */
    var currentChatId by mutableStateOf<String?>(null); private set
    val selectedChatId: String? get() = currentChatId
    /** The folder path for the next new chat (iOS currentFolderPath). */
    var currentFolder by mutableStateOf(store.lastFolder); private set
    private fun setFolderPath(p: String?) { currentFolder = p; store.lastFolder = p }

    var selectedModelId by mutableStateOf(store.selectedModelId); private set
    var selectedEffortValue by mutableStateOf(store.selectedEffort); private set
    var selectedModeId by mutableStateOf(store.selectedModeId); private set
    private fun setModel(v: String?) { if (v != selectedModelId) { selectedModelId = v; store.selectedModelId = v } }
    private fun setEffort(v: String?) { if (v != selectedEffortValue) { selectedEffortValue = v; store.selectedEffort = v } }
    private fun setModeId(v: String) { if (v != selectedModeId) { selectedModeId = v; store.selectedModeId = v } }

    var loadingChat by mutableStateOf(false); private set
    var limits by mutableStateOf<Limits?>(null); private set
    var glow by mutableStateOf(GlowState.IDLE); private set
    /** Changes whenever new output arrives (drives "keep the bottom in view" and the jump dot). */
    var outputTick by mutableStateOf(0L); private set
    /** Changes when the transcript should jump to the bottom (send, open, switch). */
    var scrollToBottomRequest by mutableStateOf(0L); private set
    /** Title of the chat the agy CLI just released (iOS availableAgainChatTitle). */
    var releasedBanner by mutableStateOf<String?>(null); private set
    val availableAgainChatTitle: String? get() = releasedBanner
    /** At most one toast shows; a new one replaces it (iOS). */
    val toasts = mutableStateListOf<Toast>()
    val toastText: String? get() = toasts.lastOrNull()?.text
    /** The chat id waiting for a folder before the held message is sent. */
    var folderPickerFor by mutableStateOf<String?>(null)
    var folderPickerPending by mutableStateOf<PendingSend?>(null); private set
    var lowLimitDismissed by mutableStateOf(false)
    val expandedFolders = mutableStateMapOf<String, Boolean>()
    var turnIndicator by mutableStateOf(IndicatorStyle.RING); private set
    /** Set by the drawer while it is open: keeps the selected computer on the fast schedule. */
    var sidebarOpen by mutableStateOf(false)
    /** Bumped when the drawer should scroll the selected chat into view. */
    var sidebarRevealTick by mutableStateOf(0); private set
    var isRefreshing by mutableStateOf(false); private set

    // MARK: composer (one draft, like iOS)
    var draft by mutableStateOf("")
    val draftAttachments = mutableStateListOf<Attachment>()
    val canSend: Boolean get() = draft.isNotBlank() || draftAttachments.isNotEmpty()

    fun addAttachment(a: Attachment) {
        if (a.path != null && draftAttachments.any { it.path == a.path }) return
        Haptics.light(ctx)
        draftAttachments.add(a)
    }

    fun removeAttachment(a: Attachment) { Haptics.light(ctx); draftAttachments.removeAll { it.id == a.id } }

    /** Sends [draft] and [draftAttachments]; they are cleared only when the message goes out. */
    fun send(): Boolean = send(draft, draftAttachments.toList()) { bad -> draftAttachments.removeAll { it.id in bad } }

    /** Slash commands for a leading `/word`; the first eight for a bare "/". */
    val matchingSlashCommands get(): List<moe.n4tsu.agyremote.net.Command> {
        val t = draft.trim()
        if (!t.startsWith("/") || t.contains(' ')) return emptyList()
        val q = t.drop(1).lowercase()
        if (q.isEmpty()) return commands.take(8)
        return commands.filter { it.name.lowercase().contains(q) || it.description.orEmpty().lowercase().contains(q) }
    }

    fun insertSlashCommand(c: moe.n4tsu.agyremote.net.Command) {
        var rest = draft
        if (rest.startsWith("/")) rest = rest.dropWhile { !it.isWhitespace() }.trimStart(' ')
        draft = "/" + c.name + " " + rest
        Haptics.selection(ctx)
    }

    // MARK: drawer
    /** Open folders, keyed "host|path" (bare paths from before count), persisted. */
    val openFolders = mutableStateListOf<String>().also { it.addAll(store.openFolders.orEmpty()) }
    private var autoExpandFolders = store.openFolders == null
    private val autoExpanded = HashSet<String>()
    private fun saveOpenFolders() { store.openFolders = openFolders.toSet() }

    fun folderIsOpen(f: Folder): Boolean = (f.host + "|" + f.path) in openFolders

    fun toggleFolder(f: Folder) {
        Haptics.selection(ctx)
        autoExpandFolders = false
        if (folderIsOpen(f)) openFolders.remove(f.host + "|" + f.path) else openFolders.add(f.host + "|" + f.path)
        saveOpenFolders()
    }

    private fun openFolder(host: String, path: String) {
        val k = "$host|$path"
        if (k !in openFolders) { openFolders.add(k); saveOpenFolders() }
        expandedFolders[path] = true
    }

    fun showSidebar() {
        Haptics.light(ctx)
        selectedDevice?.id?.let { viewModelScope.launch { sync(it) } }
        sidebarOpen = true
    }

    fun hideSidebar() { sidebarOpen = false }

    /** Bumped by agy-rem://scroll-mid. */
    var scrollToMiddleTick by mutableStateOf(0); private set

    // MARK: notification settings
    var showNotificationExplainer by mutableStateOf(false)
    /** The Activity asks for POST_NOTIFICATIONS when this turns true, then calls [confirmNotifications]. */
    var notificationPermissionRequest by mutableStateOf(false)

    /** The switch in Settings/setup: turning on goes through the explainer first. */
    fun requestNotifications(on: Boolean) {
        if (on) { if (!notificationsEnabled) showNotificationExplainer = true } else setNotifications(false)
    }

    /** "Turn On Notifications" in the explainer. */
    fun confirmNotificationsTapped() { showNotificationExplainer = false; notificationPermissionRequest = true }

    fun confirmNotifications(granted: Boolean) {
        notificationPermissionRequest = false
        if (!granted) { toast(Texts.get(ctx, "allow_notifications_for_agy_remote_in_settings_t", "Allow notifications for AGY Remote in Settings to turn this on.")); return }
        setNotifications(true)
    }

    fun notifyKind(kind: String) = store.notifyKind(kind)
    fun setNotificationKind(kind: String, on: Boolean) { store.setNotifyKind(kind, on); registerPushEverywhere() }

    // MARK: derived
    private val viewKey: String? get() = currentChatId ?: viewingDraftKey
    val messages: List<Message> get() = viewKey?.let { messagesByChat[it] }.orEmpty()
    val context: ContextUsage? get() = viewKey?.let { usageByChat[it] }
    val unreadableReason: String? get() = currentChatId?.let { unreadableByChat[it] }
    val isSelectedChatCliUnsaved: Boolean get() = unreadableReason == "cli_unsaved"
    val selectedTurnNotice: String? get() = viewKey?.let { turnNoticeByChat[it] }
    val chatState: ChatState
        get() {
            val id = currentChatId ?: return if (viewingDraftKey?.let { it in localRunning } == true) ChatState.RUNNING else ChatState.IDLE
            if (id in localRunning) return ChatState.RUNNING
            return chats.firstOrNull { it.id == id }?.state ?: ChatState.IDLE
        }
    val isRunning: Boolean get() = chatState == ChatState.RUNNING || messages.lastOrNull()?.live == true
    /** Older name of [isRunning]: drives the send/stop button. */
    val turnRunning: Boolean get() = isRunning
    val isOpenInCli: Boolean get() = chatState == ChatState.OPEN_IN_CLI
    val isChatAvailable: Boolean get() = !isOpenInCli && !(unreadableReason != null && messages.isEmpty())
    val anyRunning: Boolean get() = localRunning.isNotEmpty() || chats.any { it.state == ChatState.RUNNING }

    val isPaired: Boolean get() = devices.isNotEmpty()
    val isEmptyChat: Boolean get() = currentChatId == null && messages.isEmpty()
    val options: Options get() = Options(selectedModelId, selectedEffortValue, selectedModeId)
    val selectedModel: Model? get() = models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull()
    val selectedEffort: Effort? get() = selectedModel?.efforts?.let { e -> e.firstOrNull { it.id == selectedEffortValue } ?: e.firstOrNull() }
    val selectedMode: Mode? get() = modes.firstOrNull { it.id == selectedModeId } ?: modes.firstOrNull()
    val currentModeId: String get() = selectedMode?.id ?: selectedModeId
    val commands get() = info?.commands.orEmpty()
    val currentChat: ChatSummary? get() = chats.firstOrNull { it.id == currentChatId }

    /** The folder for the next new chat, on the selected computer when it has it. */
    val currentFolderEntry: Folder?
        get() {
            val host = selectedDevice?.id
            return folders.firstOrNull { it.path == currentFolder && it.host == host } ?: folders.firstOrNull { it.path == currentFolder }
                ?: folders.firstOrNull { it.host == host } ?: folders.firstOrNull()
        }
    val folderName: String?
        get() {
            val chat = currentChat
            val path = if (chat != null) chat.folder else currentFolderEntry?.path ?: currentFolder
            return path?.let { p -> folders.firstOrNull { it.path == p }?.name ?: p.substringAfterLast('/') }
        }
    fun folderName(chat: ChatSummary): String = chat.folder?.let { p ->
        folders.firstOrNull { it.path == p && it.host == chat.host }?.name ?: p.substringAfterLast('/')
    }.orEmpty()

    /** Header gauge: the tighter Gemini limit's remaining percent. */
    val gaugePercent: Int? get() = limits?.tightestGemini?.remaining?.toInt()
    val headerPlanLimit: Limit? get() = limits?.tightestGemini

    private fun device(id: String?): PairedDevice? = id?.let { i -> devices.firstOrNull { it.id == i } }
    /** A chat on any paired computer (`chats` holds only the selected one's). */
    private fun listedChat(id: String): ChatSummary? = devices.firstNotNullOfOrNull { d -> chatsByHost[d.id]?.firstOrNull { it.id == id } }
    /** The computer a chat lives on. */
    fun deviceForChat(chatId: String): PairedDevice? = device(listedChat(chatId)?.host) ?: selectedDevice
    private fun turnForChat(id: String): LiveTurn? = turns.values.firstOrNull { it.chatId == id }
    private fun hostDevice(forChat: String): PairedDevice? {
        turnForChat(forChat)?.let { return device(it.deviceId) }
        return device(listedChat(forChat)?.host)
    }
    /** Where the next message goes: the open chat's computer, else the chosen folder's, else the selected one. */
    val targetDevice: PairedDevice?
        get() = currentChatId?.let { deviceForChat(it) } ?: device(currentFolderEntry?.host) ?: selectedDevice

    private fun client(d: PairedDevice) = ConnectorClient(if (DemoConnector.owns(d.ticket)) DemoConnector else transport, d.ticket)

    // MARK: lifecycle

    private var connectLoop: Job? = null
    private val syncJobs = HashMap<String, CompletableDeferred<Boolean>>()
    private val lastSyncAt = HashMap<String, Long>()
    private val lastInfoAt = HashMap<String, Long>()

    /** Live-status words in the UI language; the Activity calls this again when the app language changes. */
    fun localizeStatusWords(c: android.content.Context) {
        fun t(key: String, en: String) = Texts.get(c, key, en)
        Mode.known = mapOf(
            "default" to (t("default_", "Default") to t("ask_before_edits_and_commands_per_your_permissio", "Ask before edits and commands, per your permission settings.")),
            "accept-edits" to (t("accept_edits", "Accept edits") to t("approve_file_edits_in_the_workspace_without_aski", "Approve file edits in the workspace without asking.")),
            "auto" to (t("auto", "Auto") to t("approve_edits_and_safe_commands_ask_before_irrev", "Approve edits and safe commands; ask before irreversible actions.")),
            "plan" to (t("plan", "Plan") to t("plan_carefully_before_executing_a_task", "Plan carefully before executing a task.")),
            "turbo" to (t("turbo", "Turbo") to t("runs_in_agy_s_auto_mode_and_allows_every_request", "Runs in agy's auto mode and allows every request once, without asking.")),
        )
        StatusText.words = mapOf(
            "thinking" to Texts.thinking(c), "responding" to Texts.responding(c), "waiting" to Texts.waitingForApproval(c),
            "answer" to Texts.get(c, "waiting_for_your_answer", "Waiting for your answer"),
            "reading" to Texts.get(c, "reading", "Reading"), "editing" to Texts.get(c, "editing", "Editing"),
            "searching" to Texts.get(c, "searching", "Searching"), "running" to Texts.get(c, "running", "Running"),
            "fetching" to Texts.get(c, "fetching", "Fetching"), "working" to Texts.get(c, "working", "Working"),
        )
    }

    init {
        localizeStatusWords(app)
        transport.setRelayAllowed(relayAllowed)
        installPushHooks()
        if (devices.isNotEmpty()) viewModelScope.launch { syncAllNow() }
    }

    /** MainActivity onStart: keep every computer in sync while the app is in front. */
    fun onForeground() { if (devices.isNotEmpty()) startConnecting() }

    /** MainActivity onStop: no polling in the background. */
    fun onBackground() { connectLoop?.cancel(); connectLoop = null }

    /** Older entry point: (re)starts syncing. */
    fun connect() = startConnecting()

    private fun startConnecting() {
        connectLoop?.cancel()
        connectLoop = viewModelScope.launch {
            while (isActive) {
                kickSyncs()
                delay(1_300)
            }
        }
    }

    private suspend fun syncAllNow() {
        if (devices.isEmpty()) return
        isRefreshing = true
        devices.map { d -> viewModelScope.launch { sync(d.id) } }.forEach { it.join() }
        isRefreshing = false
    }

    private fun hostIsBusy(id: String) = turns.values.any { it.deviceId == id } || chatsByHost[id].orEmpty().any { it.state == ChatState.RUNNING }

    /**
     * The selected computer, the open chat's and any with a running chat every 1.3 s (5 s when
     * nothing on screen needs it); the others every 20 s.
     */
    private fun kickSyncs() {
        val now = System.currentTimeMillis()
        val onScreen = currentChatId != null || anyRunning || sidebarOpen
        val openHost = currentChatId?.let { hostDevice(it)?.id }
        for (d in devices) {
            val id = d.id
            if (syncJobs.containsKey(id)) continue
            val busy = hostIsBusy(id)
            val fast = id == selectedDevice?.id || id == openHost || busy
            val interval = if (fast) (if (onScreen || busy) FAST_MS else 5_000L) else BACKGROUND_MS
            if (now - (lastSyncAt[id] ?: 0L) < interval - 50) continue
            viewModelScope.launch { sync(id) }
        }
    }

    /** Pull-to-refresh / refresh button: the selected computer, with its latency in a toast. */
    fun refreshActiveConnector() {
        val id = selectedDevice?.id ?: return
        viewModelScope.launch { isRefreshing = true; sync(id, announce = true, full = true); isRefreshing = false }
    }

    /** Re-reads the selected computer's chats. */
    fun refreshChats() { selectedDevice?.id?.let { viewModelScope.launch { sync(it) } } }

    /**
     * One sync of one computer: connector.info when due (first time, every minute, when not
     * connected, or [full]/[announce]), then its chats. A sync already running is waited for;
     * [announce]/[full] then run a fresh one.
     */
    private suspend fun sync(deviceId: String, announce: Boolean = false, full: Boolean = false): Boolean {
        syncJobs[deviceId]?.let { running ->
            val ok = running.await()
            if (!announce && !full) return ok
            syncJobs[deviceId]?.takeIf { it !== running }?.let { return it.await() }
        }
        val wantInfo = full || announce || System.currentTimeMillis() - (lastInfoAt[deviceId] ?: 0L) >= INFO_MS ||
            runtime(deviceId).status != DeviceStatus.CONNECTED
        val job = CompletableDeferred<Boolean>()
        syncJobs[deviceId] = job
        val ok = runCatching { performSync(deviceId, wantInfo) }.getOrDefault(false)
        job.complete(ok)
        if (syncJobs[deviceId] === job) syncJobs.remove(deviceId)
        lastSyncAt[deviceId] = System.currentTimeMillis()
        val d = device(deviceId) ?: return false
        if (ok) {
            openPendingLink()
            refreshOpenChat(deviceId)
            if (announce) toast("${d.name} · ${runtime(deviceId).latencyMs}ms")
        } else if (announce) {
            runtime(deviceId).error?.let(::toast)
        }
        return ok
    }

    private suspend fun performSync(deviceId: String, withInfo: Boolean): Boolean {
        val start = device(deviceId) ?: return false
        val c = client(start)
        val began = System.currentTimeMillis()
        var info: ConnectorInfo? = null
        if (withInfo) {
            when (val r = c.info()) {
                is Result.Ok -> info = r.value
                is Result.Err -> return syncFailed(deviceId, r.error)
            }
        }
        val elapsed = System.currentTimeMillis() - began
        val list = when (val r = c.chats()) {
            is Result.Ok -> r
            is Result.Err -> return syncFailed(deviceId, r.error)
        }
        if (device(deviceId) == null) return false
        consecutiveFailures = 0
        failuresByDevice[deviceId] = 0
        val rt = runtime(deviceId)
        var next = rt.copy(status = DeviceStatus.CONNECTED, error = null)
        if (info != null) {
            lastInfoAt[deviceId] = System.currentTimeMillis()
            next = next.copy(info = info, latencyMs = info.telemetry?.rttMs ?: maxOf(1, elapsed.toInt()),
                transport = info.telemetry?.transport ?: rt.transport)
            applyInfo(info, start)
        } else {
            list.telemetry?.let { next = next.copy(latencyMs = it.rttMs, transport = it.transport.ifEmpty { next.transport }) }
        }
        if (next != rt) deviceStates[deviceId] = next
        applyChats(list.value, deviceId)
        if (deviceId == selectedDevice?.id) {
            if (info != null) applyMeta(info, deviceId)
            refreshQuota(false)
        }
        refreshVersionIssue()
        noticeCliReleases()
        if (notificationsEnabled) viewModelScope.launch { registerPush(deviceId, force = false) }
        return true
    }

    private fun syncFailed(deviceId: String, e: ApiError): Boolean {
        val text = Texts.error(ctx, e.code, e.message)
        val rt = runtime(deviceId)
        val next = rt.copy(status = DeviceStatus.OFFLINE, error = text)
        if (next != rt) deviceStates[deviceId] = next
        lastInfoAt.remove(deviceId)
        noteConnectionFailure(deviceId)
        refreshVersionIssue()
        return false
    }

    /** Name, model and OS of a computer, and its folders; the device list is saved only when a stored field changes. */
    private fun applyInfo(i: ConnectorInfo, d: PairedDevice) {
        val updated = d.copy(
            name = i.computerName.ifEmpty { d.name },
            hardwareModel = i.computerModel.ifEmpty { d.hardwareModel },
            osVersion = i.os.ifEmpty { d.osVersion },
        )
        if (updated != d) saveDevices(devices.map { if (it.id == d.id) updated else it })
        val newFolders = i.folders.map { it.copy(host = d.id) }
        if (foldersByHost[d.id] != newFolders) { foldersByHost[d.id] = newFolders; rebuildLists() }
    }

    private fun saveDevices(list: List<PairedDevice>) {
        val changed = list.map { it.storedKey } != devices.map { it.storedKey }
        devices = list
        if (changed) store.devices = list
    }

    /** Whether a computer and this app fit together (protocol, then versions); only while connected. */
    fun versionIssue(of: PairedDevice): VersionProblem? {
        val rt = runtime(of.id)
        if (rt.status != DeviceStatus.CONNECTED) return null
        val i = rt.info ?: return null
        val p = i.protocolOrNull
        if (p != null && p > SUPPORTED_PROTOCOL) return VersionProblem.UPDATE_APP
        if (p != null && p < SUPPORTED_PROTOCOL) return VersionProblem.UPDATE_CONNECTOR
        val minApp = i.minAppVersion.orEmpty()
        if (minApp.isNotEmpty() && compareVersions(BuildConfig.VERSION_NAME, minApp) < 0) return VersionProblem.UPDATE_APP
        val tooOld = if (i.connectorVersion.isEmpty()) i.versionMissing else compareVersions(i.connectorVersion, MINIMUM_CONNECTOR) < 0
        if (tooOld) return VersionProblem.UPDATE_CONNECTOR
        return null
    }

    private fun refreshVersionIssue() {
        val d = selectedDevice
        if (d == null || runtime(d.id).status != DeviceStatus.CONNECTED) {
            versionProblem = null; connectorUpdateNote = null; return
        }
        connectorUpdateNote = runtime(d.id).info?.updateNote?.ifEmpty { null }
        versionProblem = versionIssue(d)
    }

    /**
     * Models and modes come from the selected computer. On its first sync the saved choices are
     * sent to it; after that the app follows the computer's current choices while nothing local
     * is running or waiting to be confirmed.
     */
    private fun applyMeta(i: ConnectorInfo, id: String) {
        if (i.models.isNotEmpty() && i.models != models) models = i.models
        if (i.modes.isNotEmpty() && i.modes != modes) modes = i.modes
        val defs = i.defaults
        val firstSync = id !in optionsPushed
        if (!firstSync && currentChatId == null && optionChangesPending == 0 && localRunning.isEmpty()) adoptChatOptions(defs)
        if (models.isNotEmpty() && models.none { it.id == selectedModelId }) setModel(models.firstOrNull { it.id == defs.model }?.id ?: models[0].id)
        validateEffort(defs.effort)
        if (modes.isNotEmpty() && modes.none { it.id == selectedModeId }) setModeId(modes.firstOrNull { it.id == defs.mode }?.id ?: modes[0].id)

        if (!firstSync || (models.isEmpty() && modes.isEmpty())) return
        val d = device(id) ?: return
        optionsPushed += id
        val changes = buildList {
            selectedModelId?.let { if (it != defs.model) add("model" to it) }
            selectedEffortValue?.let { if (it != defs.effort) add("effort" to it) }
            if (selectedModeId != defs.mode) add("mode" to selectedModeId)
        }
        if (changes.isEmpty()) return
        optionChangesPending++
        viewModelScope.launch {
            try { for ((o, v) in changes) client(d).setOption(null, o, v) } finally { optionChangesPending-- }
        }
    }

    private fun effortsOfSelected(): List<Effort> = selectedModel?.efforts.orEmpty()

    /** Keeps the effort valid for the selected model. */
    private fun validateEffort(preferred: String? = null) {
        val efforts = effortsOfSelected()
        if (efforts.isEmpty()) { if (selectedEffortValue != null && models.isNotEmpty()) setEffort(null); return }
        if (efforts.any { it.id == selectedEffortValue }) return
        setEffort(efforts.firstOrNull { it.id == preferred }?.id ?: efforts.firstOrNull { it.id == "medium" }?.id ?: efforts[0].id)
    }

    /** The open chat's own model, effort and mode (or the computer's current ones), when known. */
    private fun adoptChatOptions(o: Options) {
        o.model?.let { m -> if (m != selectedModelId && models.any { it.id == m }) setModel(m) }
        o.effort?.let { e -> if (e != selectedEffortValue && effortsOfSelected().any { it.id == e }) setEffort(e) }
        validateEffort()
        o.mode?.let { m -> if (m != selectedModeId && modes.any { it.id == m }) setModeId(m) }
    }

    // MARK: relay

    /** Reaching a computer failed. After two in a row for the computer in use, without the relay, asks once per launch. */
    private fun noteConnectionFailure(deviceId: String? = null) {
        if (relayAllowed || relayDeclined) return
        val count = if (deviceId != null) {
            val n = (failuresByDevice[deviceId] ?: 0) + 1
            failuresByDevice[deviceId] = n
            if (deviceId != selectedDevice?.id && deviceId != targetDevice?.id) return
            n
        } else ++consecutiveFailures
        if (count >= 2 && !relayAsked) { relayAsked = true; showRelayAlert = true }
    }

    /** Settings switch; turning it off also means "not now" for this launch. */
    fun setRelayAllowed(allowed: Boolean) {
        if (!allowed) relayDeclined = true
        applyRelay(allowed)
        showRelayAlert = false
    }

    /** "Use Relay" / "Not Now" in the relay question. */
    fun answerRelayConsent(allow: Boolean) {
        if (allow) applyRelay(true) else relayDeclined = true
        showRelayAlert = false
    }

    /** "Not Now": not asked again this launch. */
    fun declineRelay() = answerRelayConsent(false)

    private fun applyRelay(allowed: Boolean) {
        if (allowed == relayAllowed) return
        relayAllowed = allowed
        store.relayAllowed = allowed
        transport.setRelayAllowed(allowed)
        if (allowed) {
            consecutiveFailures = 0
            failuresByDevice.clear()
            selectedDevice?.id?.let { viewModelScope.launch { sync(it, announce = true) } }
        }
    }

    // MARK: computers

    fun selectDevice(id: String) {
        Haptics.selection(ctx)
        selectedDeviceId = id
        store.selectedDeviceId = id
        rebuildLists()
        refreshVersionIssue()
        viewModelScope.launch { sync(id, announce = true) }
    }

    /** Selects a computer without a toast; a change re-reads its info. */
    private fun selectDeviceQuietly(id: String) {
        if (selectedDeviceId == id) return
        selectedDeviceId = id
        store.selectedDeviceId = id
        rebuildLists()
        refreshVersionIssue()
        viewModelScope.launch { sync(id, full = true) }
    }

    /** Unpairs a computer and drops everything cached from it. */
    fun forgetDevice(id: String) {
        store.removePushKey(id)
        store.removePushGrant(id)
        grantAskedAt.remove(id)
        val d = device(id) ?: return
        if (notificationsEnabled && !d.isDemo) viewModelScope.launch { client(d).unregisterNotifications() }
        pushSignatures.remove(id); pushRegisteredAt.remove(id); pushRetryAfter.remove(id)
        val draftKeys = HashSet<String>()
        for (t in turns.values.filter { it.deviceId == id }) {
            if (d.isDemo) DemoConnector.cancel(t.requestId) else transport.cancel(t.requestId)
            if (t.chatId == null) draftKeys += t.draftKey
            turns.remove(t.requestId)
        }
        reindexTurns()
        for (k in draftKeys) { messagesByChat.remove(k); usageByChat.remove(k); turnNoticeByChat.remove(k) }
        if (viewingDraftKey in draftKeys) viewingDraftKey = null
        val gone = chatsByHost[id].orEmpty().map { it.id }.toSet()
        for (cid in gone) {
            messagesByChat.remove(cid); usageByChat.remove(cid); unreadableByChat.remove(cid); turnNoticeByChat.remove(cid)
            loadedUpdated.remove(cid); lastRunning.remove(cid); lastCliReload.remove(cid)
            openSeq.remove(cid); openInFlight.remove(cid); optionsAdopted.remove(cid)
        }
        lastOpenInCli = lastOpenInCli - gone
        val folderWasThere = currentFolderEntry?.host == id
        chatsByHost.remove(id); foldersByHost.remove(id)
        syncJobs.remove(id); lastSyncAt.remove(id); lastInfoAt.remove(id)
        failuresByDevice.remove(id); deviceStates.remove(id); optionsPushed.remove(id)
        saveDevices(devices.filterNot { it.id == id })
        if (currentChatId in gone) currentChatId = null
        if (selectedDeviceId == id || devices.none { it.id == selectedDeviceId }) {
            selectedDeviceId = devices.firstOrNull()?.id
            store.selectedDeviceId = selectedDeviceId
        }
        rebuildLists()
        if (folderWasThere) setFolderPath(folders.firstOrNull { it.host == selectedDevice?.id }?.path ?: folders.firstOrNull()?.path)
        refreshVersionIssue()
        val next = selectedDevice?.id
        if (next != null) viewModelScope.launch { sync(next, full = true) } else {
            onBackground()
            // Back to setup: it closes again only from its Start button.
            onboardingComplete = false; store.onboardingDone = false
        }
    }

    /**
     * An `agy-rem://pair` link opened from outside the app, waiting for the user's yes. Until
     * then the computer it names learns nothing (not this phone's address, not its id).
     */
    var pendingPairLink by mutableStateOf<String?>(null); private set

    fun answerPairLink(go: Boolean) {
        val link = pendingPairLink ?: return
        pendingPairLink = null
        if (go) viewModelScope.launch { pair(link) }
    }

    /**
     * A pairing waiting for the user: this phone shows a number to type into
     * `agy-connector pair` on the computer. Nothing is added before that, so a link or QR code
     * can't pair this phone with a computer on its own.
     */
    data class PendingPairing(
        val ticket: PairingTicket,
        val computer: String,
        /** The 6-digit number to show; the computer itself never displays it. */
        val number: String,
        val error: String? = null,
        /** Tells one pairing from the next, so a wait for an old one stops. */
        val id: Long = System.nanoTime(),
    )

    var pendingPairing by mutableStateOf<PendingPairing?>(null); private set

    /**
     * Starts pairing from a scanned or pasted code (or an agy-rem://pair link). With a code from
     * `agy-connector pair` the phone shows a number and waits until it was typed on the computer
     * ([awaitPairing]). True once that step is showing (or a demo computer was added).
     */
    suspend fun pair(raw: String): Boolean {
        pairingError = null
        val parsed = if (DemoConnector.isCode(raw)) kotlin.Result.success(DemoConnector.pairingTicket) else PairingTicket.parse(transport, raw)
        val t = parsed.getOrNull()
        if (t == null) {
            val e = parsed.exceptionOrNull()?.message?.ifEmpty { null } ?: Texts.invalidPairingCode(ctx)
            pairingError = e
            noteConnectionFailure()
            toast(e)
            return false
        }
        if (DemoConnector.isCode(raw)) { addPaired(t); return true }
        val fallbackName = t.hostName.ifEmpty { Texts.get(ctx, "computer", "Computer") }
        // Only a code from `agy-connector pair` (with its one-time secret) pairs.
        val secret = t.pairingSecret ?: run {
            val e = Texts.invalidPairingCode(ctx)
            pairingError = e
            toast(e)
            return false
        }
        val reply = ConnectorClient(transport, t.uri).call("pair.begin", JSONObject().put("secret", secret), timeoutMs = 20_000)
        return when (reply) {
            is Result.Ok -> {
                val name = reply.value.optString("computer").ifEmpty { fallbackName }
                val number = reply.value.optString("code")
                if (number.length != 6 || !number.all { it in '0'..'9' }) {
                    val e = unexpectedReply()
                    pairingError = e
                    toast(e)
                    return false
                }
                val waiting = PendingPairing(t, name, number)
                pendingPairing = waiting
                viewModelScope.launch { awaitPairing(waiting.id) }
                true
            }
            is Result.Err -> {
                val e = pairingMessage(reply.error)
                pairingError = e
                // Couldn't reach the computer at all: counts toward offering the relay.
                if (reply.error.code == "transport") noteConnectionFailure()
                toast(e)
                false
            }
        }
    }

    private fun unexpectedReply() =
        Texts.get(ctx, "the_computer_sent_back_something_unexpected_try", "The computer sent back something unexpected. Try pairing again.")

    /**
     * Asks the computer about once a second whether the number was typed there, and adds the
     * computer once it was. Ends when the dialog is closed or the code stops working.
     */
    private suspend fun awaitPairing(id: Long) {
        val started = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.delay(1_000)
            val p = pendingPairing?.takeIf { it.id == id && it.error == null } ?: return
            val secret = p.ticket.pairingSecret ?: return
            val r = ConnectorClient(transport, p.ticket.uri).call("pair.confirm", JSONObject().put("secret", secret), timeoutMs = 10_000)
            if (pendingPairing?.id != id) return
            val e = when (r) {
                is Result.Ok -> {
                    val stored = r.value.optString("ticket").ifEmpty { null }?.let { PairingTicket.parse(transport, it).getOrNull() }
                    if (stored != null && stored.endpointId == p.ticket.endpointId && stored.pairingSecret == null) {
                        pendingPairing = null
                        addPaired(stored)
                        return
                    }
                    unexpectedReply()
                }
                is Result.Err -> when {
                    r.error.code == "pair_pending" -> continue
                    // A dropped connection is tried again for as long as the code lasts (ten minutes).
                    r.error.code == "transport" && System.currentTimeMillis() - started < 600_000 -> continue
                    else -> pairingMessage(r.error)
                }
            }
            pendingPairing = pendingPairing?.copy(error = e)
            return
        }
    }

    fun cancelPairing() { pendingPairing = null }

    // MARK: links in agent replies

    /** A file an agent reply linked to, read from the computer (`files.read`). */
    data class FileView(val link: String, val name: String, val text: String? = null, val error: String? = null,
                        val truncated: Boolean = false, val line: Int? = null)

    var fileView by mutableStateOf<FileView?>(null); private set
    /** A web link waiting for the user to see where it really goes. */
    var pendingWebLink by mutableStateOf<String?>(null); private set

    fun closeFileView() { fileView = null }
    fun closeWebLink() { pendingWebLink = null }

    /**
     * A tapped link in an agent reply. Only web links leave the app (after the user sees the
     * address); links to files open in the app; anything else (other apps, `agy-rem://`,
     * `intent:`) is never opened, so a reply can't pair the phone or start another app. Nothing
     * here hands a link to the system unchecked, which used to crash on `file://` links.
     */
    fun handleChatLink(link: String) {
        when (moe.n4tsu.agyremote.ui.chat.ChatLinks.classify(link)) {
            moe.n4tsu.agyremote.ui.chat.ChatLinks.Kind.WEB -> pendingWebLink = link
            moe.n4tsu.agyremote.ui.chat.ChatLinks.Kind.FILE -> openFileLink(link)
            moe.n4tsu.agyremote.ui.chat.ChatLinks.Kind.REFUSED -> toast(Texts.get(ctx, "agy_remote_doesn_t_open_this_kind_of_link", "AGY Remote doesn't open this kind of link."))
        }
    }

    private fun openFileLink(link: String) {
        val anchor = link.substringAfter('#', "")
        val line = if (anchor.startsWith("L")) anchor.drop(1).takeWhile { it.isDigit() }.toIntOrNull() else null
        val path = link.substringBefore('#').substringBefore('?')
        val name = path.trimEnd('/').substringAfterLast('/').let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
        fileView = FileView(link, name.ifEmpty { link }, line = line)
        val chat = currentChatId
        val d = chat?.let { hostDevice(it) } ?: selectedDevice ?: return
        viewModelScope.launch {
            val params = JSONObject().put("path", link)
            if (chat != null) params.put("chatId", chat)
            val r = client(d).call("files.read", params)
            if (fileView?.link != link) return@launch
            fileView = when (r) {
                is Result.Ok -> fileView?.copy(name = r.value.optString("name").ifEmpty { name }, text = r.value.optString("text"),
                                               truncated = r.value.optBoolean("truncated"))
                is Result.Err -> fileView?.copy(error = when (r.error.code) {
                    "not_allowed" -> Texts.get(ctx, "this_file_is_outside_the_folders_the_computer_sh", "This file is outside the folders the computer shares.")
                    "not_text" -> Texts.get(ctx, "this_file_isn_t_text_so_it_can_t_be_shown_here", "This file isn't text, so it can't be shown here.")
                    else -> Texts.error(ctx, r.error.code, r.error.message)
                })
            }
        }
    }

    private fun pairingMessage(e: ApiError): String = when (e.code) {
        "pairing_closed" -> Texts.get(ctx, "this_pairing_code_no_longer_works_run_agy_connec", "This pairing code no longer works. Run agy-connector pair on the computer again.")
        "busy" -> Texts.get(ctx, "another_phone_is_already_pairing_with_this_code", "Another phone is already pairing with this code. Run agy-connector pair on the computer again.")
        else -> Texts.error(ctx, e.code, e.message)
    }

    /** Adds (or updates) a computer the user just confirmed and connects to it. */
    /** A computer just paired and not answered yet: "Connecting to …" in the middle of the screen. */
    var newlyPaired by mutableStateOf<String?>(null)
        private set

    private suspend fun addPaired(t: PairingTicket) {
        val existing = devices.firstOrNull { it.id == t.endpointId }
        var entry = existing ?: PairedDevice(t.uri, t.endpointId, t.hostName, t.hardwareModel, osVersion = t.osVersion)
        entry = entry.copy(
            ticket = t.uri,
            name = entry.name.ifEmpty { t.hostName.ifEmpty { Texts.get(ctx, "computer", "Computer") } },
            hardwareModel = entry.hardwareModel.ifEmpty { t.hardwareModel },
        )
        // Other computers stay as they are: a new code never replaces a paired computer on its own.
        saveDevices(listOf(entry) + devices.filterNot { it.id == t.endpointId })
        selectedDeviceId = t.endpointId
        store.selectedDeviceId = t.endpointId
        // Freshly accepted: "Connecting…" until the first sync answers.
        deviceStates[t.endpointId] = runtime(t.endpointId).copy(status = DeviceStatus.STANDBY, error = null)
        if (connectLoop == null) startConnecting()
        newlyPaired = t.endpointId
        val ok = sync(t.endpointId, announce = true)
        if (newlyPaired == t.endpointId) newlyPaired = null
        if (!ok) pairingError = runtime(t.endpointId).error
    }

    /** Page 4 of setup: connecting to the computer just paired, connected, or failed. */
    enum class SetupConnection { CONNECTING, CONNECTED, FAILED }

    val setupConnection: SetupConnection
        get() {
            val rt = runtime(selectedDevice?.id)
            return when {
                rt.status == DeviceStatus.CONNECTED -> SetupConnection.CONNECTED
                rt.error != null && !setupRetrying -> SetupConnection.FAILED
                else -> SetupConnection.CONNECTING
            }
        }
    /** Why page 4 couldn't connect (shown inline with Retry). */
    val setupError: String? get() = runtime(selectedDevice?.id).error
    private var setupRetrying by mutableStateOf(false)

    /** Retry on page 4: a fresh sync of the computer just paired. */
    fun retrySetupConnection() {
        val id = selectedDevice?.id ?: return
        if (setupRetrying) return
        setupRetrying = true
        viewModelScope.launch {
            val ok = sync(id, full = true)
            pairingError = if (ok) null else runtime(id).error
            setupRetrying = false
        }
    }

    fun completeOnboarding() {
        store.onboardingDone = true
        onboardingComplete = true
        glow = GlowState.IDLE
    }

    // MARK: lists

    private fun applyChats(list0: List<ChatSummary>, host: String) {
        val list = list0.map { it.copy(host = host) }.toMutableList()
        // Chats this phone is running a turn in stay running, and a brand-new one stays listed.
        val previous = chatsByHost[host].orEmpty()
        for (t in turns.values) {
            if (t.deviceId != host || t.stopRequested) continue
            val cid = t.chatId ?: continue
            val i = list.indexOfFirst { it.id == cid }
            if (i >= 0) list[i] = list[i].copy(state = ChatState.RUNNING)
            else previous.firstOrNull { it.id == cid }?.let { list.add(0, it) }
        }
        if (list == previous && chatsByHost.containsKey(host)) return
        chatsByHost[host] = list
        rebuildLists()
    }

    /** The selected computer's lists (others stay apart until chosen): chats newest first; folders by their newest chat, then by name. */
    private fun rebuildLists() {
        val shown = listOfNotNull(selectedDevice)
        val merged = shown.flatMap { chatsByHost[it.id].orEmpty() }.sortedByDescending { it.updatedAt }
        val newest = HashMap<String, Long>()
        for (c in merged) { val p = c.folder ?: continue; newest.putIfAbsent(c.host + "|" + p, c.updatedAt) }
        val all = ArrayList<Folder>()
        for (d in shown) {
            val seen = HashSet<String>()
            for (f in foldersByHost[d.id].orEmpty()) if (seen.add(f.path)) all += f
            for (c in chatsByHost[d.id].orEmpty()) {
                val p = c.folder ?: continue
                if (!seen.add(p)) continue
                all += Folder(p, p.substringAfterLast('/').ifEmpty { p }, null, host = d.id, synthesized = true)
            }
        }
        val sorted = all.sortedWith(compareByDescending<Folder> { newest[it.host + "|" + it.path] ?: Long.MIN_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        if (merged != chats.toList()) { chats.clear(); chats.addAll(merged) }
        if (sorted != folders) folders = sorted
        if (autoExpandFolders) {
            val fresh = newest.keys - autoExpanded
            if (fresh.isNotEmpty()) { autoExpanded += fresh; openFolders.addAll(fresh.filter { it !in openFolders }); saveOpenFolders() }
        }
    }

    private fun patchChat(id: String, change: (ChatSummary) -> ChatSummary) {
        for ((host, list) in chatsByHost.entries.toList()) {
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) chatsByHost[host] = list.toMutableList().also { it[i] = change(it[i]) }
        }
        val i = chats.indexOfFirst { it.id == id }
        if (i >= 0) { val c = change(chats[i]); if (c != chats[i]) chats[i] = c }
    }

    /** The chat on screen was released by the agy CLI: say it can be continued here. */
    private fun noticeCliReleases() {
        val now = chats.filter { it.state == ChatState.OPEN_IN_CLI }.map { it.id }.toSet()
        val id = currentChatId
        if (id != null && id in lastOpenInCli && id !in now) {
            showReleased(chats.firstOrNull { it.id == id }?.title.orEmpty())
            viewModelScope.launch { loadChat(id, quiet = true) }
        }
        lastOpenInCli = now
    }

    private fun showReleased(title: String) {
        releasedBanner = title
        Haptics.success(ctx)
        viewModelScope.launch { delay(4000); if (releasedBanner == title) releasedBanner = null }
    }

    /** Re-reads the open chat when it runs (or just stopped), is open in the CLI, or changed. */
    private suspend fun refreshOpenChat(host: String) {
        val id = currentChatId ?: return
        if (turnForChat(id) != null) return
        val chat = chatsByHost[host]?.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val cliDue = chat.state == ChatState.OPEN_IN_CLI && now - (lastCliReload[id] ?: 0L) > 2_500
        if (cliDue) lastCliReload[id] = now
        val changed = loadedUpdated[id]?.let { it != chat.changeStamp } ?: true
        if (chat.state == ChatState.RUNNING || lastRunning[id] == true || cliDue || changed) loadChat(id, quiet = true)
    }

    // MARK: plan limits

    private var lastQuotaAt = 0L
    private var quotaLoading = false
    private var quotaDismissals by mutableStateOf(store.quotaDismissals)

    /** Re-reads plan limits from the selected computer; unforced reads at most once a minute. */
    fun refreshQuota(force: Boolean) {
        val d = selectedDevice ?: return
        if (quotaLoading) return
        val now = System.currentTimeMillis()
        if (!force && now - lastQuotaAt < 60_000) return
        quotaLoading = true
        lastQuotaAt = now
        viewModelScope.launch {
            try {
                val r = client(d).limits(force) as? Result.Ok ?: return@launch
                if (r.value.limits.isEmpty()) return@launch
                if (limits?.limits != r.value.limits || limits?.credits != r.value.credits) limits = r.value
                forgetRefilledDismissals()
            } finally { quotaLoading = false }
        }
    }

    /** Older name of [refreshQuota]. */
    fun refreshLimits(refresh: Boolean) = refreshQuota(refresh)

    private fun threshold(l: Limit): Double? = QUOTA_THRESHOLDS.filter { l.remaining <= it }.minOrNull()
    private fun dismissedLevel(l: Limit): Double? = quotaDismissals[l.id]

    /** The tightest limit past a level (50/10/5 % left) it wasn't dismissed at. */
    val quotaNoticeLimit: Limit?
        get() = limits?.limits.orEmpty().filter { l ->
            val level = threshold(l) ?: return@filter false
            val at = dismissedLevel(l) ?: return@filter true
            level < at
        }.minByOrNull { it.remaining }

    fun dismissQuotaNotice(limit: Limit) {
        val level = threshold(limit) ?: return
        quotaDismissals = quotaDismissals + (limit.id to level)
        store.quotaDismissals = quotaDismissals
    }

    private fun forgetRefilledDismissals() {
        val ls = limits?.limits ?: return
        if (quotaDismissals.isEmpty()) return
        val kept = quotaDismissals.toMutableMap()
        for (l in ls) kept[l.id]?.let { if (l.remaining > it) kept.remove(l.id) }
        if (kept != quotaDismissals) { quotaDismissals = kept; store.quotaDismissals = kept }
    }

    // MARK: chats

    /** A new chat; [folder] (a path) also picks its computer. */
    fun newChat(folder: String?) {
        val f = folder?.let { p -> folders.firstOrNull { it.path == p && it.host == selectedDevice?.id } ?: folders.firstOrNull { it.path == p } }
        if (f != null) startNewChat(f) else {
            if (folder != null) setFolderPath(folder)
            startNewChat(null)
        }
    }

    fun startNewChat(folder: Folder?) {
        Haptics.light(ctx)
        if (folder != null) {
            setFolderPath(folder.path)
            if (device(folder.host) != null) selectDeviceQuietly(folder.host)
        }
        currentChatId = null
        viewingDraftKey = null
        loadingChat = false
        glow = GlowState.IDLE
        scrollToBottomRequest++
    }

    /** Picks the folder (and its computer) for the next new chat. */
    fun selectFolder(folder: Folder) {
        Haptics.selection(ctx)
        setFolderPath(folder.path)
        openFolder(folder.host, folder.path)
        if (device(folder.host) != null) selectDeviceQuietly(folder.host)
        currentChatId = null
        viewingDraftKey = null
        glow = GlowState.IDLE
    }

    fun openChat(requested: String) {
        val id = resolveChatId(requested) ?: requested
        Haptics.selection(ctx)
        currentChatId = id
        viewingDraftKey = null
        listedChat(id)?.let { chat ->
            chat.folder?.let(::setFolderPath)
            if (device(chat.host) != null) selectDeviceQuietly(chat.host)
        }
        glow = if (isRunning) GlowState.WORKING else GlowState.IDLE
        scrollToBottomRequest++
        if (turnForChat(id) != null) { loadingChat = false; return }
        loadingChat = messagesByChat[id].isNullOrEmpty()
        viewModelScope.launch { loadChat(id, quiet = false) }
    }

    /** Opens a chat picked outside the drawer (a link or search) and scrolls the drawer to it. */
    fun openChatAndReveal(id: String) { openChat(id); sidebarRevealTick++ }

    private fun resolveChatId(id: String): String? = chats.firstOrNull { it.id == id }?.id ?: chats.firstOrNull { it.id.startsWith(id) }?.id

    private fun openPendingLink() {
        val want = pendingLinkChatId ?: return
        val found = resolveChatId(want) ?: return
        pendingLinkChatId = null
        openChatAndReveal(found)
    }

    /** `agy-rem://session/<id>`, `agy-rem://sidebar`, `agy-rem://pair?…`. Returns true when handled. */
    fun handleLink(url: android.net.Uri): Boolean {
        if (url.scheme != "agy-rem") return false
        when (url.host) {
            // A link can come from any web page or app: nothing is contacted until the user agrees.
            "pair" -> pendingPairLink = url.toString()
            "session" -> {
                val id = url.path.orEmpty().trim('/')
                if (id.isEmpty()) return true
                resolveChatId(id)?.let(::openChatAndReveal) ?: run { pendingLinkChatId = id }
            }
            "sidebar" -> showSidebar()
            "scroll-mid" -> scrollToMiddleTick++
            else -> return false
        }
        return true
    }

    /**
     * `chat.open` on the chat's computer, decoded off the main thread. A quiet reload is skipped
     * while one is out; a newer request makes older replies stale, and stale replies are dropped.
     */
    private suspend fun loadChat(id: String, quiet: Boolean) {
        if (quiet && id in openInFlight) return
        val d = hostDevice(id)
        if (d == null) {
            if (chats.none { it.id == id }) pendingLinkChatId = id
            if (currentChatId == id) loadingChat = false
            return
        }
        val seq = (openSeq[id] ?: 0) + 1
        openSeq[id] = seq
        openInFlight += id
        try {
            when (val r = client(d).open(id)) {
                is Result.Ok -> {
                    if (openSeq[id] != seq) return
                    val o = r.value
                    val unreadable = if (o.readable) null else (o.unreadableReason ?: "desktop_app")
                    if (unreadable == null) unreadableByChat.remove(id) else if (unreadableByChat[id] != unreadable) unreadableByChat[id] = unreadable
                    o.context?.let { if (usageByChat[id] != it) usageByChat[id] = it }
                    if (turnForChat(id) != null) return
                    val was = lastRunning[id] ?: false
                    if (messagesByChat[id] != o.messages) { messagesByChat[id] = o.messages; if (currentChatId == id) outputTick++ }
                    chats.firstOrNull { it.id == id }?.let { s ->
                        if (s.state != o.state) patchChat(id) { it.copy(state = o.state) }
                        loadedUpdated[id] = s.changeStamp
                    }
                    lastRunning[id] = o.state == ChatState.RUNNING
                    if (currentChatId == id) {
                        if (o.state == ChatState.RUNNING) { if (glow != GlowState.WORKING && glow != GlowState.ANSWERING) glow = GlowState.WORKING }
                        else if (was) glow = GlowState.IDLE
                        // The chat's own options on first load; later only when nothing local is pending.
                        if (id !in optionsAdopted || (optionChangesPending == 0 && localRunning.isEmpty())) adoptChatOptions(o.options)
                        optionsAdopted += id
                    }
                }
                is Result.Err -> if (openSeq[id] == seq && !quiet) toastError(r.error)
            }
        } finally {
            if (openSeq[id] == seq) {
                openInFlight -= id
                if (currentChatId == id) loadingChat = false
            }
        }
    }

    fun recentChatsInFolder(): List<ChatSummary> {
        val f = currentFolderEntry ?: return chats.take(3)
        return chats.filter { it.folder == f.path && it.host == f.host }.take(3)
    }

    // MARK: options

    private fun sendOptions(changes: List<Pair<String, String>>, onError: ((ApiError) -> Unit)? = null) {
        if (changes.isEmpty()) return
        val target = targetDevice ?: return
        val chatId = currentChatId
        optionChangesPending++
        viewModelScope.launch {
            try {
                for ((option, value) in changes) {
                    when (val r = client(target).setOption(chatId, option, value)) {
                        is Result.Ok -> if (optionChangesPending == 1) adoptChatOptions(r.value)
                        is Result.Err -> onError?.invoke(r.error)
                    }
                }
            } finally { optionChangesPending-- }
        }
    }

    fun selectModel(m: Model) {
        Haptics.selection(ctx)
        setModel(m.id)
        if (m.efforts.isEmpty()) setEffort(null)
        else if (m.efforts.none { it.id == selectedEffortValue }) setEffort(m.efforts.firstOrNull { it.id == "medium" }?.id ?: m.efforts[0].id)
        sendOptions(listOfNotNull("model" to m.id, selectedEffortValue?.let { "effort" to it }))
    }

    fun selectEffort(id: String) {
        Haptics.selection(ctx)
        setEffort(id)
        sendOptions(listOf("effort" to id))
    }

    fun selectMode(id: String) {
        Haptics.selection(ctx)
        setModeId(id)
        val name = modes.firstOrNull { it.id == id }?.name ?: id
        sendOptions(listOf("mode" to id)) { e -> toast(Texts.couldntSwitch(ctx, name, Texts.error(ctx, e.code, e.message))) }
    }

    // MARK: turns

    /**
     * Sends from the composer. Returns true when the message went out and the composer should be
     * cleared. Phone files that can't be sent are dropped with a toast and their ids passed to
     * [onRejected] (remove them from the composer); when a folder is needed first,
     * [folderPickerFor] is set and false is returned.
     */
    fun send(text: String, attachments: List<Attachment>, onRejected: (Set<String>) -> Unit = {}): Boolean =
        submit(text, attachments, fromComposer = true, onRejected)

    private fun submit(raw: String, given: List<Attachment>, fromComposer: Boolean, onRejected: (Set<String>) -> Unit = {}): Boolean {
        val text = raw.trim()
        if ((text.isEmpty() && given.isEmpty()) || isRunning) return false
        val unsendable = given.filter { !it.isSendable }
        val attachments = given.filter { it.isSendable }
        if (unsendable.isNotEmpty()) {
            toast(Texts.unsendable(ctx, unsendable.joinToString(", ") { it.name }))
            if (fromComposer) onRejected(unsendable.map { it.id }.toSet())
            if (text.isEmpty() && attachments.isEmpty()) return false
        }
        val target = targetDevice ?: return false
        versionIssue(target)?.let { issue ->
            Haptics.attention(ctx)
            toast(if (issue == VersionProblem.UPDATE_APP) Texts.appNeedsUpdate(ctx, target.name) else Texts.updateConnector(ctx, target.name))
            return false
        }
        if (isOpenInCli) {
            Haptics.attention(ctx)
            toast(Texts.closeInCli(ctx))
            return false
        }
        val chat = currentChat
        if (chat != null && chat.folder == null) {
            folderPickerPending = PendingSend(text, attachments, fromComposer)
            folderPickerFor = chat.id
            return false
        }
        Haptics.light(ctx)
        val folder = chat?.folder ?: currentFolderEntry?.takeIf { it.host == target.id }?.path
        startTurn(text, attachments, currentChatId, target, folder)
        return true
    }

    /** Assigns the folder picked for a CLI chat, then sends the held message. */
    fun continuePending(folder: Folder) {
        val pending = folderPickerPending ?: return
        val id = currentChatId ?: return
        folderPickerPending = null
        folderPickerFor = null
        viewModelScope.launch {
            if (assignFolder(folder.path, id, folder.host)) submit(pending.text, pending.attachments, pending.fromComposer)
        }
    }

    /** Older flow: assigns [folder] to [folderPickerFor]'s chat, then runs [then] (which re-sends). */
    fun setFolderForPendingChat(folder: String, then: () -> Unit) {
        val chatId = folderPickerFor ?: return
        folderPickerPending = null
        viewModelScope.launch {
            val ok = assignFolder(folder, chatId, null)
            folderPickerFor = null
            if (ok) then()
        }
    }

    /** `chat.set_folder` on the chat's computer. */
    fun setFolder(folder: Folder, chatId: String) { viewModelScope.launch { assignFolder(folder.path, chatId, folder.host) } }

    private suspend fun assignFolder(path: String, chatId: String, host: String?): Boolean {
        val d = deviceForChat(chatId) ?: return false
        return when (val r = client(d).setFolder(chatId, path)) {
            is Result.Ok -> {
                patchChat(chatId) { it.copy(folder = path) }
                rebuildLists()
                setFolderPath(path)
                openFolder(host ?: deviceForChat(chatId)?.id.orEmpty(), path)
                true
            }
            is Result.Err -> { toastError(r.error); false }
        }
    }

    /** Retry: sends the chat's last prompt again. */
    fun rerunLastPrompt() {
        val prompt = messages.lastOrNull { it.fromUser } ?: return
        submit(prompt.text, prompt.attachments.filter { it.isResendable }, fromComposer = false)
    }

    /** Sends again the prompt that [message] answered. */
    fun rerun(message: Message) {
        val list = messages
        val idx = list.indexOfFirst { it.id == message.id }
        if (idx < 0) return
        val prompt = list.take(idx + 1).lastOrNull { it.fromUser } ?: return
        submit(prompt.text, prompt.attachments.filter { it.isResendable }, fromComposer = false)
    }

    /** Older name of [rerun]. */
    fun retry(agentMessage: Message) = rerun(agentMessage)

    /** turn.start attachments; text files from the phone go into the prompt. */
    private fun wire(attachments: List<Attachment>, text: String): Pair<JSONArray, String> {
        val items = JSONArray()
        val prompt = StringBuilder(text)
        for (a in attachments) {
            val path = a.path ?: if (a.kind != "image" && a.bytes == null) a.name else null
            if (path != null) { items.put(JSONObject().put("kind", "file").put("path", path)); continue }
            if (a.kind == "image") {
                val data = a.data ?: a.bytes?.let { Base64.getEncoder().encodeToString(it) }
                if (data != null) {
                    val mime = a.mime ?: if (a.bytes?.take(4) == listOf(0x89.toByte(), 0x50, 0x4E, 0x47)) "image/png" else "image/jpeg"
                    items.put(JSONObject().put("kind", "image").put("name", a.name).put("mime", mime).put("data", data))
                    continue
                }
            }
            val body = a.utf8Text
            if (body != null) prompt.append("\n\n`${a.name}`:\n```\n$body\n```")
            else toast(Texts.unsendable(ctx, a.name))
        }
        return items to prompt.toString()
    }

    private fun startTurn(text: String, attachments: List<Attachment>, chatId: String?, device: PairedDevice, folder: String?) {
        val requestId = UUID.randomUUID().toString().lowercase()
        val draftKey = "draft-$requestId"
        val thinking = Texts.thinking(ctx)
        val user = Message(UUID.randomUUID().toString(), true, text, attachments)
        val agent = Message(UUID.randomUUID().toString(), false, "", live = true, statusLabel = thinking)
        val key = chatId ?: draftKey
        turnNoticeByChat.remove(key)
        messagesByChat[key] = messagesByChat[key].orEmpty() + user + agent
        turns[requestId] = LiveTurn(requestId, device.id, agent.id, draftKey, chatId, folder, text, thinking)
        reindexTurns()
        if (chatId != null) patchChat(chatId) { it.copy(state = ChatState.RUNNING) } else viewingDraftKey = draftKey
        glow = GlowState.WORKING
        turnIndicator = if (indicatorStyle == IndicatorStyle.RANDOM) IndicatorStyle.entries.filter { it != IndicatorStyle.RANDOM }.random() else indicatorStyle
        scrollToBottomRequest++

        val (items, prompt) = wire(attachments, text)
        val params = JSONObject()
            .put("chatId", chatId ?: JSONObject.NULL)
            .put("folder", if (chatId == null) folder ?: JSONObject.NULL else JSONObject.NULL)
            .put("text", prompt)
            .put("attachments", items)
            .put("options", turnOptions())
        viewModelScope.launch {
            runCatching { client(device).startTurn(requestId, params).collect { handleEvent(it, requestId) } }
            streamEnded(requestId)
        }
    }

    /** Options sent with a turn; unset ones are left out. */
    private fun turnOptions() = JSONObject().apply {
        selectedModelId?.takeIf { it.isNotEmpty() }?.let { put("model", it) }
        selectedEffortValue?.takeIf { it.isNotEmpty() }?.let { put("effort", it) }
        if (selectedModeId.isNotEmpty()) put("mode", selectedModeId)
    }

    /**
     * The stream closed. Before the computer named the chat the turn never started: that fails
     * visibly. After, without a final event, the turn goes on on the computer; syncs pick it up.
     */
    private fun streamEnded(requestId: String) {
        val current = turns[requestId] ?: return
        if (current.chatId == null && !current.stopRequested) { fail(requestId, Texts.couldntStartReply(ctx)); return }
        val t = turns.remove(requestId) ?: return
        reindexTurns()
        if (!t.stopRequested && isViewing(t)) toast(Texts.reconnecting(ctx))
        t.chatId?.let { lastRunning[it] = true }
        mutateAgent(t) { it.copy(live = false, statusLabel = "") }
        viewModelScope.launch { sync(t.deviceId) }
    }

    private fun mutateAgent(t: LiveTurn, change: (Message) -> Message) {
        val list = messagesByChat[t.cacheKey] ?: return
        val i = list.indexOfLast { it.id == t.agentMessageId }
        if (i < 0) return
        messagesByChat[t.cacheKey] = list.toMutableList().also { it[i] = change(it[i]) }
        if (isViewing(t)) outputTick++
    }

    private fun agentOf(t: LiveTurn): Message? = messagesByChat[t.cacheKey]?.lastOrNull { it.id == t.agentMessageId }

    private fun isViewing(t: LiveTurn): Boolean =
        t.chatId?.let { currentChatId == it } ?: (currentChatId == null && viewingDraftKey == t.draftKey)

    private fun setStatus(label: String, t: LiveTurn) {
        t.status = label
        mutateAgent(t) { if (it.statusLabel == label) it else it.copy(statusLabel = label) }
    }

    /** One stream frame, applied to its own turn's chat wherever the user is looking. */
    private fun handleEvent(ev: JSONObject, requestId: String) {
        val t = turns[requestId] ?: return
        if (ev.has("ok") && !ev.optBoolean("ok")) {
            val e = ApiError.from(ev) ?: ApiError("internal", "")
            fail(requestId, Texts.error(ctx, e.code, e.message)); return
        }
        val type = ev.optString("type")
        val event = ev.optString("event")
        if (type == "connected" || event == "transport.connected") {
            ev.optJSONObject("_p2p")?.let { p ->
                val rt = runtime(t.deviceId)
                val next = rt.copy(status = DeviceStatus.CONNECTED, latencyMs = p.optInt("rtt_ms", rt.latencyMs),
                    transport = p.optString("transport").ifEmpty { rt.transport })
                if (next != rt) deviceStates[t.deviceId] = next
            }
            return
        }
        if (type == "error" || event == "transport.error") {
            // Before the computer named the chat, the turn never started.
            if (t.chatId == null) fail(requestId, ev.optString("message").ifEmpty { Texts.couldntReach(ctx) })
            return
        }
        if (t.stopRequested && !LATE_EVENTS.contains(event)) return
        when (event) {
            "turn.chat" -> ev.optString("chatId").takeIf { it.isNotEmpty() }?.let { cid ->
                bindChat(cid, ev.optString("folder").takeIf { it.isNotEmpty() }?.let(Folder::normalize), t)
            }
            "turn.thinking" -> {
                setStatus(Texts.thinking(ctx), t)
                mutateAgent(t) { it.copy(thinking = (it.thinking ?: "") + ev.optString("text")) }
            }
            "turn.text" -> {
                if (isViewing(t)) glow = GlowState.ANSWERING
                setStatus(Texts.responding(ctx), t)
                mutateAgent(t) { it.copy(text = it.text + ev.optString("text")) }
            }
            "turn.step" -> ev.optJSONObject("step")?.let { s ->
                val id = s.optString("id").takeIf { it.isNotEmpty() } ?: return@let
                mutateAgent(t) { m ->
                    val idx = m.steps.indexOfFirst { it.id == id }
                    m.copy(steps = if (idx >= 0) m.steps.toMutableList().also { it[idx] = it[idx].merged(s) } else m.steps + Step.from(s).let { if (it.at == null) it.copy(at = m.text.length) else it })
                }
                agentOf(t)?.let { setStatus(StatusText.live(it), t) }
            }
            "turn.plan" -> {
                val arr = ev.optJSONArray("plan")
                val plan = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.let(PlanEntry::from) }
                mutateAgent(t) { it.copy(plan = plan) }
            }
            "turn.context" -> if (ev.has("used") && ev.has("size")) usageByChat[t.cacheKey] = ContextUsage(ev.optLong("used"), ev.optLong("size"))
            "turn.title" -> {
                val title = ev.optString("title")
                val cid = t.chatId
                if (title.isNotEmpty() && cid != null) patchChat(cid) { it.copy(title = title) }
            }
            "turn.approval" -> ev.optJSONObject("approval")?.let(Approval::from)?.let { a ->
                Haptics.attention(ctx)
                mutateAgent(t) { m ->
                    val i = m.approvals.indexOfFirst { it.id == a.id }
                    m.copy(approvals = if (i >= 0) m.approvals.toMutableList().also { it[i] = a } else m.approvals + a)
                }
                setStatus(if (a.isQuestion) Texts.get(ctx, "waiting_for_your_answer", "Waiting for your answer") else Texts.waitingForApproval(ctx), t)
            }
            "turn.notice" -> if (isViewing(t)) {
                if (ev.optString("code") == "cli_touched") toast(Texts.cliTouched(ctx))
                else ev.optString("message").ifEmpty { ev.optString("code") }.takeIf { it.isNotEmpty() }?.let(::toast)
            }
            "turn.finished" -> {
                val secs = if (ev.has("seconds") && !ev.isNull("seconds")) ev.optDouble("seconds") else null
                mutateAgent(t) { it.copy(seconds = secs) }
                finish(requestId)
            }
            "turn.stopped" -> finish(requestId)
            "turn.failed" -> {
                val e = ApiError.from(JSONObject().put("ok", false).put("error", ev.optJSONObject("error") ?: JSONObject())) ?: ApiError("internal", "")
                fail(requestId, Texts.error(ctx, e.code, e.message))
            }
        }
    }

    /** The computer named the chat a turn runs in: file the messages under it and list it. */
    private fun bindChat(cid: String, folder: String?, t: LiveTurn) {
        if (t.chatId == null) {
            val moved = messagesByChat.remove(t.draftKey).orEmpty()
            messagesByChat[cid] = messagesByChat[cid].orEmpty() + moved
            usageByChat.remove(t.draftKey)?.let { usageByChat[cid] = it }
            turnNoticeByChat.remove(t.draftKey)?.let { turnNoticeByChat[cid] = it }
        }
        t.chatId = cid
        t.folder = folder ?: t.folder
        reindexTurns()
        val list = chatsByHost[t.deviceId].orEmpty().toMutableList()
        val nowIso = java.time.Instant.now().toString()
        val i = list.indexOfFirst { it.id == cid }
        if (i >= 0) list[i] = list[i].copy(state = ChatState.RUNNING, updated = nowIso, updatedAt = System.currentTimeMillis())
        else list.add(0, ChatSummary(cid, t.prompt.trim(), t.folder, nowIso, ChatState.RUNNING, host = t.deviceId))
        chatsByHost[t.deviceId] = list
        rebuildLists()
        t.folder?.let { openFolder(t.deviceId, it) }
        if (currentChatId == null && viewingDraftKey == t.draftKey) {
            currentChatId = cid
            viewingDraftKey = null
        }
        if (t.stopRequested) device(t.deviceId)?.let { d -> viewModelScope.launch { client(d).stop(cid) } }
    }

    private fun fail(requestId: String, message: String) {
        turns[requestId]?.let { t ->
            mutateAgent(t) { it.copy(text = if (it.text.isEmpty()) message else it.text + "\n\n" + message) }
            turnNoticeByChat[t.cacheKey] = message
        }
        toast(message)
        finish(requestId)
    }

    private fun finish(requestId: String) {
        val t = turns.remove(requestId) ?: return
        reindexTurns()
        mutateAgent(t) { it.copy(live = false, statusLabel = "") }
        t.chatId?.let { cid ->
            patchChat(cid) { if (it.state == ChatState.RUNNING) it.copy(state = ChatState.IDLE) else it }
            lastRunning[cid] = false
        }
        if (isViewing(t)) glow = GlowState.IDLE
        refreshQuota(true)
        viewModelScope.launch { sync(t.deviceId) }
    }

    /** Stops the turn on screen; works before the computer named the chat, and for turns started elsewhere. */
    fun stop() {
        val local = currentChatId?.let(::turnForChat)
            ?: viewingDraftKey?.takeIf { currentChatId == null }?.let { k -> turns.values.firstOrNull { it.draftKey == k && !it.stopRequested } }
        if (local != null) {
            local.stopRequested = true
            reindexTurns()
            mutateAgent(local) { it.copy(live = false, statusLabel = "") }
            local.chatId?.let { cid ->
                patchChat(cid) { it.copy(state = ChatState.IDLE) }
                device(local.deviceId)?.let { d -> viewModelScope.launch { client(d).stop(cid) } }
            }
        } else {
            val id = currentChatId ?: return
            if (chats.firstOrNull { it.id == id }?.state != ChatState.RUNNING) return
            val d = deviceForChat(id)
            patchChat(id) { it.copy(state = ChatState.IDLE) }
            messagesByChat[id]?.let { list ->
                if (list.lastOrNull()?.live == true) messagesByChat[id] = list.dropLast(1) + list.last().copy(live = false, statusLabel = "")
            }
            if (d != null) viewModelScope.launch { client(d).stop(id); delay(450); sync(d.id) }
        }
        glow = GlowState.IDLE
    }

    fun answer(approval: Approval, choice: String) {
        Haptics.press(ctx)
        var key: String? = null
        for ((k, list) in messagesByChat.entries.toList()) {
            if (list.none { m -> m.approvals.any { it.id == approval.id } }) continue
            key = k
            messagesByChat[k] = list.map { m ->
                if (m.approvals.none { it.id == approval.id }) m else {
                    val u = m.copy(approvals = m.approvals.map { if (it.id == approval.id) it.copy(chosen = choice) else it })
                    if (u.live) u.copy(statusLabel = StatusText.live(u)) else u
                }
            }
        }
        val owner = turns.values.firstOrNull { it.cacheKey == key }
        owner?.let { o -> agentOf(o)?.let { o.status = StatusText.live(it) } }
        val target = device(owner?.deviceId) ?: key?.let { deviceForChat(it) } ?: targetDevice ?: return
        viewModelScope.launch {
            val r = client(target).answer(approval.id, choice)
            if (r is Result.Err && r.error.code == "not_found") markApprovalStale(approval.id)
        }
    }

    /** Answers an `ask_question` form (`answers` null: skipped). The card then shows it as done. */
    fun answerQuestion(approval: Approval, answers: JSONObject?) {
        Haptics.press(ctx)
        var key: String? = null
        val done = if (answers == null) "skipped" else "answered"
        for ((k, list) in messagesByChat.entries.toList()) {
            if (list.none { m -> m.approvals.any { it.id == approval.id } }) continue
            key = k
            messagesByChat[k] = list.map { m ->
                if (m.approvals.none { it.id == approval.id }) m else {
                    val u = m.copy(approvals = m.approvals.map { if (it.id == approval.id) it.copy(chosen = done) else it })
                    if (u.live) u.copy(statusLabel = StatusText.live(u)) else u
                }
            }
        }
        val owner = turns.values.firstOrNull { it.cacheKey == key }
        owner?.let { o -> agentOf(o)?.let { o.status = StatusText.live(it) } }
        val target = device(owner?.deviceId) ?: key?.let { deviceForChat(it) } ?: targetDevice ?: return
        viewModelScope.launch {
            val r = client(target).answerQuestion(approval.id, answers)
            if (r is Result.Err && r.error.code == "not_found") markApprovalStale(approval.id)
        }
    }

    private fun markApprovalStale(id: String) {
        for ((k, list) in messagesByChat.entries.toList()) {
            if (list.none { m -> m.approvals.any { it.id == id } }) continue
            messagesByChat[k] = list.map { m -> m.copy(approvals = m.approvals.map { if (it.id == id) it.copy(stale = true) else it }) }
        }
    }

    /** The agent's live status line for a chat (drawer rows). */
    fun liveStatus(chatId: String): String {
        turns.values.firstOrNull { it.cacheKey == chatId && !it.stopRequested }?.let { return it.status }
        if (chats.none { it.id == chatId && it.state == ChatState.RUNNING }) return ""
        val last = messagesByChat[chatId]?.lastOrNull { !it.fromUser }
        if (last != null && last.live) return last.statusLabel.ifEmpty { StatusText.live(last) }
        return Texts.thinking(ctx)
    }

    fun chatIsRunning(chat: ChatSummary): Boolean = chat.id in localRunning || chat.state == ChatState.RUNNING

    /** Workspace files in the open chat's folder (else the selected folder), on that computer. */
    suspend fun searchFiles(query: String): List<FileHit> {
        val chat = currentChat
        val (path, target) = if (chat?.folder != null) chat.folder to device(chat.host) else currentFolderEntry?.path to device(currentFolderEntry?.host)
        val d = target ?: selectedDevice ?: return emptyList()
        path ?: return emptyList()
        return (client(d).searchFiles(path, query) as? Result.Ok)?.value.orEmpty()
    }

    // MARK: notifications

    fun setNotifications(on: Boolean) {
        notificationsEnabled = on
        store.notificationsEnabled = on
        if (on) {
            fetchPushToken()
        } else {
            pushSignatures.clear()
            devices.forEach { d -> viewModelScope.launch { client(d).unregisterNotifications() } }
            dropPushToken()
        }
    }

    // FCM token + end-to-end key → each computer (iOS registerPush). The token lives with Google;
    // the key never leaves the phone except over the encrypted P2P link.
    private var pushToken: String? = store.fcmToken
    private val pushSignatures = HashMap<String, String>()
    private val pushRegisteredAt = HashMap<String, Long>()
    /** Device id → (retry not before, failures in a row). */
    private val pushRetryAfter = HashMap<String, Pair<Long, Int>>()
    private var pushFailureToasted = false
    /** Device id → when its relay was last asked for a grant. */
    private val grantAskedAt = HashMap<String, Long>()
    /** Relays that said they hand out no grants → when they may be asked again. */
    private val grantlessRelays = HashMap<String, Long>()
    /** Computers waiting for a grant request, sent together a moment later ([flushGrantRequests]). */
    private val grantWanted = LinkedHashSet<String>()
    private var grantFlush: kotlinx.coroutines.Job? = null
    private val firebaseReady: Boolean get() = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    private fun installPushHooks() {
        PushState.onNewToken = { t -> if (t != pushToken) { pushToken = t; registerPushEverywhere() } }
        PushState.answerApproval = { id, allow, computer -> answerFromNotification(id, allow, computer) }
        PushState.onGrant = { g -> receiveGrant(g) }
        if (notificationsEnabled) fetchPushToken()
    }

    /** Asks Firebase for this install's token (iOS registerForRemoteNotifications). */
    private fun fetchPushToken() {
        if (!firebaseReady) {
            // Built without app/google-services.json (see docs/push-android.md).
            toast(Texts.get(ctx, "push_couldnt_turn_on", "Couldn't turn on notifications: %s", "Firebase is not configured in this build"))
            return
        }
        val fm = FirebaseMessaging.getInstance()
        fm.isAutoInitEnabled = true
        fm.token
            .addOnSuccessListener { t ->
                store.fcmToken = t
                if (t != pushToken) { pushToken = t; registerPushEverywhere() }
            }
            .addOnFailureListener { e ->
                toast(Texts.get(ctx, "push_couldnt_turn_on", "Couldn't turn on notifications: %s", e.localizedMessage ?: e.toString()))
            }
    }

    /** Turned off: Google forgets the token too (iOS unregisterForRemoteNotifications). */
    private fun dropPushToken() {
        pushToken = null
        store.fcmToken = null
        if (!firebaseReady) return
        runCatching {
            val fm = FirebaseMessaging.getInstance()
            fm.isAutoInitEnabled = false
            fm.deleteToken()
        }
    }

    /** The notification doesn't say which computer asked, so each one is tried until one knows it. */
    private suspend fun answerFromNotification(approvalId: String, allow: Boolean, computer: String): Boolean {
        val d = device(computer) ?: return false
        return client(d).answer(approvalId, if (allow) "allow" else "deny") is Result.Ok
    }

    private fun registerPushEverywhere() {
        pushSignatures.clear()
        pushRetryAfter.clear()
        for (id in devices.map { it.id }) viewModelScope.launch { registerPush(id, force = true) }
    }

    /** The computer's push relay and its key there, if it reports them and the address is one to contact. */
    private fun pushRelayOf(id: String): Pair<String, String>? {
        val i = runtime(id).info ?: return null
        val relay = i.pushRelay?.takeIf { moe.n4tsu.agyremote.push.PushGrants.endpoint(it) != null } ?: return null
        val key = i.pushKey?.takeIf { it.isNotEmpty() } ?: return null
        return relay to key
    }

    /**
     * Asks the computer's relay for a grant when there is none, or it runs out soon; not more
     * often than every ten minutes per computer. Computers registering at about the same time
     * (all of them, when notifications are turned on) go into one request per relay, so a phone
     * with many computers asks once. The relay answers with an FCM data message ([receiveGrant]).
     */
    private fun askForGrant(id: String, token: String) {
        if (!notificationsEnabled || !PushState.foreground) return
        val (relay, _) = pushRelayOf(id) ?: return
        val now = System.currentTimeMillis()
        if (store.pushGrant(id, token, relay, now / 1000 + moe.n4tsu.agyremote.push.PushGrants.RENEW_BEFORE_SECONDS) != null) return
        if (now - (grantAskedAt[id] ?: 0L) < 600_000) return
        // A relay without grants is asked again only a day later: the phone doesn't send its
        // push token there for nothing.
        if (now < (grantlessRelays[relay] ?: 0L)) return
        grantWanted += id
        if (grantFlush?.isActive == true) return
        grantFlush = viewModelScope.launch {
            kotlinx.coroutines.delay(1_000)
            flushGrantRequests()
        }
    }

    private suspend fun flushGrantRequests() {
        val wanted = grantWanted.toList()
        grantWanted.clear()
        val token = pushToken ?: return
        if (!notificationsEnabled) return
        val byRelay = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (id in wanted.sorted()) pushRelayOf(id)?.let { (relay, key) -> byRelay.getOrPut(relay) { mutableListOf() } += id to key }
        for ((relay, list) in byRelay) {
            val now = System.currentTimeMillis()
            list.forEach { (id, _) -> grantAskedAt[id] = now }
            for (part in list.map { it.second }.distinct().chunked(moe.n4tsu.agyremote.push.PushGrants.MAX_KEYS_PER_REQUEST)) {
                if (moe.n4tsu.agyremote.push.PushGrants.ask(relay, token, part) == 404) {
                    grantlessRelays[relay] = System.currentTimeMillis() + 24 * 3_600_000L
                    break
                }
            }
        }
    }

    /**
     * Grants arrived from a relay. Each is kept only if this app asked for that computer in the
     * last ten minutes; anything else (someone else asking in this phone's name) is dropped.
     */
    private fun receiveGrant(text: String) {
        val g = moe.n4tsu.agyremote.push.PushGrants.parse(text) ?: return
        val token = pushToken ?: return
        val now = System.currentTimeMillis()
        if (g.expires <= now / 1000) return
        for ((key, value) in g.grants) {
            for (id in devices.map { it.id }) {
                val (relay, ownKey) = pushRelayOf(id) ?: continue
                if (ownKey != key || now - (grantAskedAt[id] ?: 0L) >= 600_000) continue
                store.setPushGrant(id, moe.n4tsu.agyremote.push.PushGrants.Grant(value, g.expires, token, relay))
                viewModelScope.launch { registerPush(id, force = true) }
            }
        }
    }

    /**
     * Sends the token, the end-to-end key and the wanted kinds to one computer. Repeated syncs
     * skip computers that already have the current set; failures back off 15 s, 30 s, … 10 min.
     */
    private suspend fun registerPush(id: String, force: Boolean) {
        val token = pushToken ?: return
        val d = device(id) ?: return
        if (!notificationsEnabled || d.isDemo) return
        // Its own key: no computer can make notifications that look like another's.
        val key = store.pushKeyFor(id)
        val kinds = PUSH_KINDS.filter(store::notifyKind).sorted()
        // The relay's grant for this computer and token, once the phone has one.
        val grant = pushRelayOf(id)?.let { (relay, _) -> store.pushGrant(id, token, relay) }
        val signature = (listOf(token, key, grant?.value.orEmpty()) + kinds).joinToString(",")
        // Before the skip below: the relay's address may have arrived only after the last registration.
        askForGrant(id, token)
        val now = System.currentTimeMillis()
        // Same set: again only every ten minutes (the computer may have restarted and lost it).
        val fresh = now - (pushRegisteredAt[id] ?: 0L) < 600_000
        if (!force && pushSignatures[id] == signature && fresh) return
        if (!force) pushRetryAfter[id]?.let { (until, _) -> if (now < until) return }
        pushSignatures[id] = signature
        pushRegisteredAt[id] = now
        val params = JSONObject()
            .put("token", token).put("platform", "fcm").put("environment", "production")
            .put("key", key).put("kinds", JSONArray(kinds))
        if (grant != null) params.put("grant", grant.value).put("grantExp", grant.expires)
        when (val r = client(d).call("notifications.register", params)) {
            is Result.Ok -> {
                pushRetryAfter.remove(id)
                pushFailureToasted = false
                // The relay stopped honouring this grant (the computer found out when it sent one).
                if (r.value.optBoolean("grantRefused")) { store.removePushGrant(id); askForGrant(id, token) }
                // Turned off while this was on its way: take it back.
                if (!notificationsEnabled) { pushSignatures.remove(id); client(d).unregisterNotifications() }
            }
            is Result.Err -> {
                pushSignatures.remove(id)
                pushRegisteredAt.remove(id)
                val n = (pushRetryAfter[id]?.second ?: 0) + 1
                val delayMs = (15_000.0 * Math.pow(2.0, (n - 1).toDouble())).coerceAtMost(600_000.0).toLong()
                pushRetryAfter[id] = (now + delayMs) to n
                if (!pushFailureToasted) {
                    pushFailureToasted = true
                    toast(Texts.get(ctx, "push_unavailable_on_x", "%1\$s: notifications unavailable (%2\$s)", d.name, Texts.error(ctx, r.error.code, r.error.message)))
                }
            }
        }
    }

    // MARK: toasts

    fun toast(text: String) {
        val t = Toast(System.nanoTime(), text)
        toasts.clear()
        toasts.add(t)
        val ms = (maxOf(2.1, text.length / 15.0).coerceAtMost(8.0) * 1000).toLong()
        viewModelScope.launch { delay(ms); toasts.remove(t) }
    }

    private fun toastError(e: ApiError) = toast(Texts.error(ctx, e.code, e.message))

    // MARK: reset

    /** Back to first launch; the phone's P2P identity is kept so pairing again works right away. */
    fun resetApp() {
        devices.forEach { d -> viewModelScope.launch { client(d).unregisterNotifications() } }
        if (notificationsEnabled) dropPushToken()
        pushSignatures.clear(); pushRegisteredAt.clear(); pushRetryAfter.clear(); pushFailureToasted = false
        turns.values.forEach { transport.cancel(it.requestId) }
        onBackground()
        turns.clear(); reindexTurns()
        viewingDraftKey = null
        syncJobs.clear(); lastSyncAt.clear(); lastInfoAt.clear(); lastQuotaAt = 0
        failuresByDevice.clear(); deviceStates.clear()
        openSeq.clear(); openInFlight.clear(); optionsAdopted.clear(); optionsPushed.clear()
        turnNoticeByChat.clear(); pendingLinkChatId = null; folderPickerPending = null; folderPickerFor = null
        loadingChat = false; toasts.clear(); releasedBanner = null
        showRelayAlert = false; quotaLoading = false
        chatsByHost.clear(); foldersByHost.clear(); chats.clear(); folders = emptyList(); models = emptyList(); modes = emptyList()
        messagesByChat.clear(); usageByChat.clear(); unreadableByChat.clear()
        loadedUpdated.clear(); lastRunning.clear(); lastCliReload.clear(); lastOpenInCli = emptySet()
        currentChatId = null; limits = null; quotaDismissals = emptyMap()
        versionProblem = null; connectorUpdateNote = null; pairingError = null
        expandedFolders.clear()
        val secret = store.secretKeyHex
        store.clear()
        store.secretKeyHex = secret
        devices = emptyList(); selectedDeviceId = null
        selectedModelId = null; selectedEffortValue = null; selectedModeId = "default"
        currentFolder = null
        relayAllowed = false; transport.setRelayAllowed(false)
        relayAsked = false; relayDeclined = false; consecutiveFailures = 0
        notificationsEnabled = false; groupByFolder = true; folderSort = "recent"; projectsCollapsed = false; onboardingComplete = false
        openFolders.clear(); autoExpandFolders = true; autoExpanded.clear(); draft = ""; draftAttachments.clear()
        updateTheme(ThemeSetting.SYSTEM)
        indicatorStyle = store.indicator
        glow = GlowState.IDLE
    }

    override fun onCleared() {
        PushState.onNewToken = null
        PushState.onGrant = null
        PushState.answerApproval = null
        // The transport is process-wide (see SharedTransport): blocking calls on IO threads may still be
        // using it, so freeing it here crashed the app (use after free in the native client).
    }

    companion object {
        private const val FAST_MS = 1_300L
        /** Computers nothing on screen or running depends on. */
        private const val BACKGROUND_MS = 20_000L
        /** How often connector.info is re-read once connected. */
        private const val INFO_MS = 60_000L
        /** Remaining-percent levels at which the low-limit notice shows. */
        val QUOTA_THRESHOLDS = listOf(50.0, 10.0, 5.0)

        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d
            }
            return 0
        }
    }
}
