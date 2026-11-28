import Foundation
import Observation
import SwiftUI
import UIKit
import UserNotifications

/// Everything the workspace shows, and every request it makes to agy-connector (docs/protocol.md).
/// Every paired computer is synced; their folders and chats are shown in one list, each tagged
/// with the computer it lives on, and requests about a chat go to that chat's computer.
@MainActor
@Observable
final class RemoteWorkspaceViewModel {
    // MARK: Ambient
    let gradientFlow = GradientFlow()

    // MARK: Computers
    var devices: [RemoteDevice] = [] {
        didSet { if Self.storedFields(devices) != Self.storedFields(oldValue) { saveDevices() } }
    }
    var selectedDeviceId: String? {
        didSet {
            persist(selectedDeviceId, forKey: Keys.selectedDevice)
            if selectedDeviceId != oldValue { rebuildLists() }
        }
    }
    var selectedDevice: RemoteDevice? { devices.first { $0.id == selectedDeviceId } ?? devices.first }
    var pairedTickets: [String] { devices.map(\.ticket) }
    var isConnected: Bool { selectedDevice?.status == .connected }
    var onboardingComplete: Bool { didSet { persist(onboardingComplete, forKey: Keys.onboarded) } }
    var showsLanding: Bool { forceLanding || !onboardingComplete || devices.isEmpty }
    /// Debug `--show-landing`: the setup pages even when paired.
    var forceLanding = false
    var pairingError: String?
    /// The connector speaks a protocol this app doesn't, or the versions don't fit together.
    var versionIssue: VersionIssue?
    /// Asking whether to use the public relay, after direct connections keep failing.
    var showRelayConsent = false
    /// The user agreed to use the relay when a direct connection isn't possible (all computers).
    var relayAllowed: Bool {
        didSet {
            guard relayAllowed != oldValue else { return }
            persist(relayAllowed, forKey: Keys.relay)
            client?.setRelayAllowed(relayAllowed)
            if relayAllowed {
                consecutiveFailures = 0
                failuresByDevice = [:]
                if let id = selectedDevice?.id { Task { await sync(deviceId: id, announce: true) } }
            }
        }
    }
    @ObservationIgnored private var consecutiveFailures = 0
    /// Failed syncs in a row, per computer.
    @ObservationIgnored private var failuresByDevice: [String: Int] = [:]
    /// The last error per computer.
    var errorByDevice: [String: String] = [:]
    @ObservationIgnored private var relayAsked = false
    @ObservationIgnored private var relayDeclined = false

    enum VersionIssue: Equatable { case updateApp, updateConnector }

    // MARK: Diagnostics
    /// This phone's P2P endpoint id.
    var clientEndpointId: String = ""
    var isRefreshingP2P = false
    var lastP2PError: String?

    // MARK: Workspace data (merged over all computers)
    var folders = [WorkspaceFolder]()
    var chats = [ChatSummary]()
    var baseModels = [AgyBaseModel]()
    var modes = [AgyMode]()
    var commands = [SlashCommand]()
    var connectorUpdateNote: String?
    private var foldersByHost: [String: [WorkspaceFolder]] = [:]
    private var chatsByHost: [String: [ChatSummary]] = [:]
    @ObservationIgnored private var protocolByHost: [String: Int] = [:]

    // MARK: Per-chat state
    /// Messages per chat (or per not-yet-named new chat, keyed by its draft key).
    var messagesByChat: [String: [ChatMessage]] = [:] { didSet { refreshViewFlags() } }
    var usageByChat: [String: SessionUsage] = [:]
    /// Why a chat can't be shown (`desktop_app`, `cli_unsaved`).
    var unreadableByChat: [String: String] = [:]
    @ObservationIgnored private var turns: [String: LiveTurn] = [:] { didSet { reindexTurns() } }
    /// Cache keys (chat id or draft key) of this phone's turns that haven't been stopped.
    private(set) var localRunning: Set<String> = []
    /// Live status line per running turn's cache key; only `liveStatus(for:)` reads it.
    private var turnStatus: [String: String] = [:]
    private func reindexTurns() {
        let live = turns.values.filter { !$0.stopRequested }
        let keys = Set(live.map(\.cacheKey))
        if keys != localRunning { localRunning = keys }
        let status = Dictionary(live.map { ($0.cacheKey, $0.status) }, uniquingKeysWith: { a, _ in a })
        if status != turnStatus { turnStatus = status }
    }
    /// The error a chat's last turn ended with; kept across transcript reloads.
    var turnNoticeByChat: [String: String] = [:]
    var selectedTurnNotice: String? { viewKey.flatMap { turnNoticeByChat[$0] } }
    /// The new chat on screen whose turn hasn't been given an id yet.
    private(set) var viewingDraftKey: String? { didSet { refreshViewFlags() } }
    /// Cheap stand-ins for `messages.isEmpty` / `messages.last?.isStreaming`, so views that only
    /// need these (the composer) don't re-render on every streamed token.
    private(set) var viewHasMessages = false
    private(set) var viewLastStreaming = false
    private func refreshViewFlags() {
        let list = messages
        if viewHasMessages != !list.isEmpty { viewHasMessages = !list.isEmpty }
        let streaming = list.last?.isStreaming == true
        if viewLastStreaming != streaming { viewLastStreaming = streaming }
    }
    @ObservationIgnored private var loadedUpdated: [String: String] = [:]
    /// chat.open bookkeeping: the newest request per chat, and whether one is out.
    @ObservationIgnored private var openSeq: [String: Int] = [:]
    @ObservationIgnored private var openInFlight: Set<String> = []
    /// Chats whose options were adopted from the computer at least once.
    @ObservationIgnored private var optionsAdopted: Set<String> = []
    /// A local model/effort/mode change not yet confirmed by the computer.
    @ObservationIgnored private var optionChangesPending = 0
    @ObservationIgnored private var lastRunning: [String: Bool] = [:]
    @ObservationIgnored private var lastCliReload: [String: Date] = [:]
    @ObservationIgnored private var lastOpenInCli: Set<String> = []
    @ObservationIgnored private var pendingLinkChatId: String?

    private struct LiveTurn {
        let requestId: String
        let deviceId: String
        let agentMessageId: String
        let draftKey: String
        var chatId: String?
        var folder: String?
        let prompt: String
        var status: String
        var stopRequested = false
        var cacheKey: String { chatId ?? draftKey }
    }

    // MARK: Selection
    var selectedChatId: String? {
        didSet {
            refreshViewFlags()
            if selectedChatId != oldValue { AppDelegate.visibleSessionId = selectedChatId }
        }
    }
    var messages: [ChatMessage] {
        get { viewKey.flatMap { messagesByChat[$0] } ?? [] }
        set { if let k = viewKey { messagesByChat[k] = newValue } }
    }
    var chatState: ChatSummary.State {
        get {
            guard let id = selectedChatId else { return viewingDraftKey.map(localRunning.contains) == true ? .running : .idle }
            if localRunning.contains(id) { return .running }
            return chats.first { $0.id == id }?.state ?? .idle
        }
        set { if let id = selectedChatId { patchChat(id) { $0.state = newValue } } }
    }
    var isLoadingChat = false
    var unreadableReason: String? {
        get { selectedChatId.flatMap { unreadableByChat[$0] } }
        set { if let id = selectedChatId { unreadableByChat[id] = newValue } }
    }
    var isSelectedChatUnavailable: Bool { unreadableReason != nil }
    var isSelectedChatCliUnsaved: Bool { unreadableReason == "cli_unsaved" }
    var currentFolderPath: String? { didSet { persist(currentFolderPath, forKey: Keys.folder) } }
    var selectedModelId: String? { didSet { persist(selectedModelId, forKey: Keys.model) } }
    var selectedEffortValue: String? { didSet { persist(selectedEffortValue, forKey: Keys.effort) } }
    var selectedModeId: String = "default" { didSet { persist(selectedModeId, forKey: Keys.mode) } }
    var selectedSessionUsage: SessionUsage? {
        get { viewKey.flatMap { usageByChat[$0] } }
        set { if let k = viewKey { usageByChat[k] = newValue } }
    }
    var quota: QuotaSnapshot?
    var availableAgainChatTitle: String?
    var toast: String?
    var folderPickerPending: PendingSend?

    struct PendingSend: Identifiable {
        let id = UUID(); let text: String; let attachments: [MessageAttachment]; var fromComposer = false
    }

    // MARK: Composer
    var draft: String = ""
    var draftAttachments: [MessageAttachment] = []

    // MARK: Presentation
    var sidebarOpen = false
    var showModelPicker = false
    var showUsage = false
    var showSettings = false
    var showAddSheet = false
    var showComputerPicker = false
    var showSearch = false
    var showNotificationExplainer = false
    // Opened from the + sheet after it closes (presented by the composer).
    var showWorkspaceFilePicker = false
    var showCommandPicker = false
    /// Live horizontal drag of the drawer (points; positive while pulling it open).
    var sidebarDragOffset: CGFloat = 0
    /// The drawer's inline search.
    var sidebarSearchActive = false
    var sidebarQuery = ""
    /// The full-screen search.
    var globalSearchQuery = ""
    /// Bumped to ask the drawer to scroll the selected chat into view.
    var sidebarRevealTick = 0
    /// Bumped to ask the transcript to scroll to its middle (debug / `agy-rem://scroll-mid`).
    var scrollToMiddleTick = 0
    /// Set to have the composer take focus once the workspace appears.
    var composerFocusRequested = false
    /// Mirrors the composer's keyboard focus; set to false to dismiss the keyboard (transcript tap).
    var composerFocused = false
    /// Show the empty-chat entrance settled instead of springing in.
    var skipEntranceSplash = false

    // MARK: Preferences
    var theme: ThemePreference {
        didSet { persist(theme.rawValue, forKey: Keys.theme); applyAppearanceToWindows() }
    }
    var thinkingStyle: ThinkingIndicatorStyle { didSet { persist(thinkingStyle.rawValue, forKey: Keys.thinking) } }
    var notificationsEnabled: Bool { didSet { persist(notificationsEnabled, forKey: Keys.notifications) } }
    var notificationKinds: Set<NotificationKind> {
        didSet { persist(notificationKinds.map(\.rawValue), forKey: Keys.kinds) }
    }
    var groupByFolder: Bool { didSet { persist(!groupByFolder, forKey: Keys.flat) } }
    /// How the drawer orders projects: most recently used first, or by name.
    var folderSort: FolderSort { didSet { persist(folderSort.rawValue, forKey: Keys.folderSort) } }
    /// The drawer's project list is folded away (the sessions without a project stay in view).
    var projectsCollapsed: Bool { didSet { persist(projectsCollapsed, forKey: Keys.projectsCollapsed) } }
    var collapsedFolders: Set<String> { didSet { persist(Array(collapsedFolders), forKey: Keys.collapsed) } }
    /// Folders open in the drawer; everything else starts folded.
    var openFolders: Set<String> { didSet { persist(Array(openFolders), forKey: Keys.openFolders) } }
    @ObservationIgnored private var autoExpandFolders: Bool
    @ObservationIgnored private var autoExpanded: Set<String> = []

    // MARK: Internals
    @ObservationIgnored private var client: P2PClient?
    @ObservationIgnored private var connectLoop: Task<Void, Never>?
    /// The sync running per computer, so callers can wait for it.
    @ObservationIgnored private var syncTasks: [String: Task<Bool, Never>] = [:]
    @ObservationIgnored private var lastSyncAt: [String: Date] = [:]
    @ObservationIgnored private var lastInfoAt: [String: Date] = [:]
    @ObservationIgnored private var lastQuotaAt: Date = .distantPast
    @ObservationIgnored private var pushRegisteredAt: [String: Date] = [:]
    /// Each computer's push relay and the key it signs with there (`connector.info`), for asking
    /// that relay for a grant; and when one was last asked for.
    @ObservationIgnored private var pushRelays: [String: (relay: String, key: String)] = [:]
    @ObservationIgnored private var grantAskedAt: [String: Date] = [:]
    /// Relays that said they hand out no grants, and until when they aren't asked again.
    @ObservationIgnored private var grantlessRelays: [String: Date] = [:]
    /// Computers waiting for a grant request, sent together a moment later (`flushGrantRequests`).
    @ObservationIgnored private var grantWanted: Set<String> = []
    @ObservationIgnored private var grantFlush: Task<Void, Never>?
    @ObservationIgnored private var pushRetryAfter: [String: (Date, Int)] = [:]
    @ObservationIgnored private var pushFailureToasted = false
    /// Sidebar indexes, rebuilt with the lists.
    @ObservationIgnored private var chatsByFolderKey: [String: [ChatSummary]] = [:]
    @ObservationIgnored private var unfiledChats: [ChatSummary] = []
    @ObservationIgnored private var runningChatIds: Set<String> = []
    @ObservationIgnored private var pushToken: String?
    @ObservationIgnored private var pushSignatures: [String: String] = [:]
    @ObservationIgnored private var quotaLoading = false
    private var quotaDismissals: [String: Double]
    @ObservationIgnored private var optionsPushed: Set<String> = []

    private enum Keys {
        static let selectedDevice = "agy.pref.selectedDeviceId"
        static let onboarded = "agy.pref.onboardingCompleted"
        static let relay = "agy.pref.relayAllowed"
        static let folder = "agy.pref.selectedFolder"
        static let theme = "agy.pref.appearance"
        static let thinking = "agy.pref.thinkingStyle"
        static let notifications = "agy.pref.notificationsEnabled"
        static let kinds = "agy.pref.notificationKinds"
        static let flat = "agy.pref.showAllSessionsFlat"
        static let folderSort = "agy.pref.projectSort"
        static let projectsCollapsed = "agy.pref.projectsCollapsed"
        static let collapsed = "agy.pref.collapsedFolders"
        static let openFolders = "agy.pref.expandedProjectIds"
        static let model = "agy.pref.selectedBaseModelId"
        static let effort = "agy.pref.selectedEffort"
        static let mode = "agy.pref.selectedModeId"
        static let dismissals = "agy.pref.quotaNoticeDismissals"
    }

    init() {
        let d = UserDefaults.standard
        func string(_ key: String) -> String? { d.string(forKey: key) }
        theme = ThemePreference(rawValue: (string(Keys.theme) ?? "").lowercased()) ?? .system
        thinkingStyle = ThinkingIndicatorStyle(rawValue: string(Keys.thinking) ?? "") ?? .random
        notificationsEnabled = d.bool(forKey: Keys.notifications)
        notificationKinds = d.stringArray(forKey: Keys.kinds).map { Set($0.compactMap(NotificationKind.init)) }
            ?? Set(NotificationKind.allCases)
        groupByFolder = !d.bool(forKey: Keys.flat)
        folderSort = FolderSort(rawValue: d.string(forKey: Keys.folderSort) ?? "") ?? .recent
        projectsCollapsed = d.bool(forKey: Keys.projectsCollapsed)
        collapsedFolders = Set(d.stringArray(forKey: Keys.collapsed) ?? [])
        let savedOpen = d.stringArray(forKey: Keys.openFolders)
        openFolders = Set(savedOpen ?? [])
        autoExpandFolders = savedOpen == nil
        currentFolderPath = string(Keys.folder)
        selectedModelId = string(Keys.model).flatMap { $0.isEmpty ? nil : $0 }
        selectedEffortValue = string(Keys.effort).flatMap { $0.isEmpty ? nil : $0 }
        selectedModeId = string(Keys.mode).flatMap { $0.isEmpty ? nil : $0 } ?? "default"
        relayAllowed = d.bool(forKey: Keys.relay)
        quotaDismissals = d.dictionary(forKey: Keys.dismissals) as? [String: Double] ?? [:]
        let paired = Self.loadDevices()
        devices = paired
        let savedDevice = string(Keys.selectedDevice)
        selectedDeviceId = paired.contains { $0.id == savedDevice } ? savedDevice : paired.first?.id
        if let done = d.object(forKey: Keys.onboarded) as? Bool {
            onboardingComplete = done
        } else {
            // People who paired before the setup flow had a last page skip it.
            onboardingComplete = !paired.isEmpty
        }
        // Nothing paired: setup isn't finished, so pairing from it doesn't close it before page 4.
        if paired.isEmpty { onboardingComplete = false }
        persist(onboardingComplete, forKey: Keys.onboarded)

        if let c = P2PClient() {
            client = c
            clientEndpointId = c.endpointId
            c.setRelayAllowed(relayAllowed)
        } else {
            lastP2PError = String(localized: "Couldn't start networking on this phone.")
        }
        installPushHooks()
        applyAppearanceToWindows()
        gradientFlow.setZeroStateActive(true, triggerPulse: false)
        Task { [weak self] in
            await self?.syncAllNow()
            self?.startConnecting()
        }
    }

    // MARK: - Stored computers

    /// The paired computers with their tickets (each holds this phone's token for that
    /// computer): in the keychain, on this device only, not in UserDefaults or any backup.
    private static let ticketsItem = KeychainBlob(service: "moe.n4tsu.agyremote.p2p", account: "paired-computers-v1")

    private static func loadDevices() -> [RemoteDevice] {
        guard let data = ticketsItem.read(), let list = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return list.compactMap { entry in
            guard let id = entry["endpoint_id"] as? String, let uri = entry["uri"] as? String, !uri.isEmpty else { return nil }
            return RemoteDevice(id: id, ticket: uri, name: entry["host_name"] as? String ?? "",
                                hardwareModel: entry["hardware_model"] as? String ?? "",
                                osVersion: entry["os_version"] as? String ?? "")
        }
    }

    private static func encodeDevices(_ list: [RemoteDevice]) -> Data? {
        let rows: [[String: Any]] = list.map {
            ["endpoint_id": $0.id, "uri": $0.ticket, "host_name": $0.name, "hardware_model": $0.hardwareModel, "os_version": $0.osVersion]
        }
        return try? JSONSerialization.data(withJSONObject: rows)
    }

    private func persist(_ value: Any?, forKey key: String) {
        UserDefaults.standard.set(value, forKey: key)
    }

    /// The fields of a computer that are stored; latency, status and versions aren't.
    private static func storedFields(_ list: [RemoteDevice]) -> [String] {
        list.map { [$0.id, $0.ticket, $0.name, $0.hardwareModel, $0.osVersion].joined(separator: "\u{1}") }
    }

    private func saveDevices() {
        guard let data = Self.encodeDevices(devices) else { return }
        // Only in the keychain, on this device: never in UserDefaults or a backup.
        _ = Self.ticketsItem.write(data)
    }

    // MARK: - Derived

    private var viewKey: String? { selectedChatId ?? viewingDraftKey }
    private var viewingDraftTurn: LiveTurn? {
        guard selectedChatId == nil, let k = viewingDraftKey else { return nil }
        return turns.values.first { $0.draftKey == k && !$0.stopRequested }
    }
    private func turn(forChat id: String) -> LiveTurn? { turns.values.first { $0.chatId == id } }
    private func device(_ id: String?) -> RemoteDevice? { id.flatMap { i in devices.first { $0.id == i } } }
    /// A chat on any paired computer (the drawer's `chats` holds only the selected one's).
    private func listedChat(_ id: String) -> ChatSummary? {
        for d in devices { if let c = chatsByHost[d.id]?.first(where: { $0.id == id }) { return c } }
        return nil
    }
    /// The computer a chat lives on.
    func device(forChat id: String) -> RemoteDevice? {
        device(listedChat(id)?.host) ?? selectedDevice
    }
    /// The computer a known chat lives on; nil for a chat no computer listed yet.
    private func hostDevice(forChat id: String) -> RemoteDevice? {
        if let t = turn(forChat: id) { return device(t.deviceId) }
        return device(listedChat(id)?.host)
    }
    /// Where the next message goes: the open chat's computer, else the chosen folder's, else the selected one.
    var targetDevice: RemoteDevice? {
        if let id = selectedChatId { return device(forChat: id) }
        return device(currentFolder?.host) ?? selectedDevice
    }

    var currentFolder: WorkspaceFolder? {
        let host = selectedDevice?.id
        return folders.first { $0.path == currentFolderPath && $0.host == host }
            ?? folders.first { $0.path == currentFolderPath }
            ?? folders.first { $0.host == host } ?? folders.first
    }
    var selectedChat: ChatSummary? { chats.first { $0.id == selectedChatId } }
    var isEmptyChat: Bool { selectedChatId == nil && !viewHasMessages }
    var isRunning: Bool { chatState == .running || viewLastStreaming }
    /// The chat on screen accepts a new turn (not open in the CLI, not an unreadable one).
    var isChatAvailable: Bool { !isOpenInCli && !(unreadableReason != nil && !viewHasMessages) }
    var isOpenInCli: Bool { chatState == .openInCli }
    /// Something runs anywhere: a turn from this phone, or one the computers report.
    var anyRunning: Bool { !localRunning.isEmpty || !runningChatIds.isEmpty }
    var selectedBaseModel: AgyBaseModel? {
        if let hit = baseModels.first(where: { m in m.id == selectedModelId }) { return hit }
        return baseModels.first
    }
    var availableEffortsForSelectedModel: [AgyEffortOption] {
        guard let model = selectedBaseModel else { return [] }
        return model.efforts
    }
    var selectedEffortOption: AgyEffortOption? {
        let list = availableEffortsForSelectedModel
        if let hit = list.first(where: { e in e.value == selectedEffortValue }) { return hit }
        return list.first
    }
    var selectedMode: AgyMode? { modes.first { $0.id == selectedModeId } ?? modes.first }
    var modelShortName: String { selectedBaseModel?.shortName ?? "" }
    var placeholder: String {
        if let chat = selectedChat {
            guard let path = chat.folder else { return String(localized: "Ask Agy") }
            let name = folders.first { $0.path == path }?.name ?? (path as NSString).lastPathComponent
            return String(localized: "Ask in \(name)")
        }
        if let f = currentFolder { return String(localized: "Ask in \(f.name)") }
        return String(localized: "Ask Agy")
    }
    var recentChatsInFolder: [ChatSummary] {
        guard let f = currentFolder else { return Array(chats.prefix(3)) }
        return Array(chats.filter { $0.folder == f.path && $0.host == f.host }.prefix(3))
    }
    /// The plan limit for the header: the Gemini one with the least left (any, if none is Gemini).
    var headerPlanLimit: QuotaLimit? {
        // The Gemini limits decide when agy lists them; otherwise the tightest of whatever it lists.
        let all = quota?.limits ?? []
        let gemini = all.filter { $0.group.range(of: "gemini", options: .caseInsensitive) != nil }
        return (gemini.isEmpty ? all : gemini).min { $0.remaining < $1.remaining }
    }
    /// "4 Neon High" for the header.
    var headerModelLabel: String {
        var words = [String]()
        if !modelShortName.isEmpty { words.append(modelShortName) }
        if let effort = selectedEffortOption?.displayName, !effort.isEmpty { words.append(effort) }
        return words.joined(separator: " ")
    }
    var tightestLimit: Double? { headerPlanLimit?.remaining }
    /// Remaining percentage of the limit the low-limit notice is about.
    var quotaNoticeRemaining: Double? { quotaNoticeLimit?.remaining }

    /// Debug-only demo content.

    // MARK: - Sidebar & search data

    private func matches(_ text: String, _ q: String) -> Bool { text.localizedCaseInsensitiveContains(q) }
    private var trimmedSidebarQuery: String { sidebarQuery.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Chats of a folder (on its computer), newest first, narrowed by the drawer search.
    func chats(in folder: WorkspaceFolder) -> [ChatSummary] {
        let q = trimmedSidebarQuery
        _ = chats // observe; the index is rebuilt with it
        let list = chatsByFolderKey[folder.id] ?? []
        guard !q.isEmpty, !(matches(folder.name, q) || matches(folder.path, q)) else { return list }
        return list.filter { matches($0.title, q) }
    }
    /// Folders for the grouped drawer, in `folderSort` order: matching by name or path, or holding
    /// a matching chat.
    var sidebarFolders: [WorkspaceFolder] {
        let q = trimmedSidebarQuery
        _ = chats
        let shown = q.isEmpty ? folders : folders.filter { matches($0.name, q) || matches($0.path, q) || !chats(in: $0).isEmpty }
        let byName: (WorkspaceFolder, WorkspaceFolder) -> Bool = { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        switch folderSort {
        case .name:
            return shown.sorted(by: byName)
        case .recent:
            // The newest chat in each folder (lists are newest first); folders without chats last.
            let last = { (f: WorkspaceFolder) in self.chatsByFolderKey[f.id]?.first?.updated ?? .distantPast }
            return shown.sorted { a, b in
                let (x, y) = (last(a), last(b))
                return x == y ? byName(a, b) : x > y
            }
        }
    }
    /// Chats without a known folder (started in the interactive CLI, or in a folder not listed).
    var sidebarUnfiledChats: [ChatSummary] {
        let q = trimmedSidebarQuery
        _ = chats; _ = folders
        return q.isEmpty ? unfiledChats : unfiledChats.filter { matches($0.title, q) }
    }
    /// Every chat, newest first, for the flat drawer and search.
    var sidebarFlatChats: [ChatSummary] {
        let q = trimmedSidebarQuery
        return q.isEmpty ? chats : chats.filter { matches($0.title, q) }
    }
    var sidebarModels: [AgyBaseModel] {
        let q = trimmedSidebarQuery
        return q.isEmpty ? baseModels : baseModels.filter { matches($0.name, q) || matches($0.id, q) }
    }
    func folderName(for chat: ChatSummary) -> String {
        guard let p = chat.folder else { return "" }
        return folders.first { $0.path == p && $0.host == chat.host }?.name ?? (p as NSString).lastPathComponent
    }
    /// Chats, folders, models and text in loaded messages matching `globalSearchQuery`.
    var globalSearchResults: [GlobalSearchResult] {
        let needle = globalSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        var out = chats.filter { needle.isEmpty || matches($0.title, needle) }.map { GlobalSearchResult.chat($0, folderName: folderName(for: $0)) }
        out += folders.filter { needle.isEmpty || matches($0.name, needle) || matches($0.path, needle) }.map(GlobalSearchResult.folder)
        guard !needle.isEmpty else { return out }
        out += baseModels.filter { matches($0.name, needle) || matches($0.id, needle) }.map(GlobalSearchResult.model)
        for (cid, list) in messagesByChat where !cid.hasPrefix("draft-") {
            out += list.filter { matches($0.text, needle) }.map { GlobalSearchResult.message(chatId: cid, message: $0) }
        }
        return out
    }

    // MARK: - Drawer

    func showSidebar() {
        Haptics.impact()
        if let id = selectedDevice?.id { Task { await sync(deviceId: id) } }
        withAnimation(.drawerOpen) { sidebarOpen = true }
    }

    /// Closes the drawer and folds its search away.
    func hideSidebar() {
        withAnimation(.drawerClose) {
            sidebarOpen = false
            sidebarSearchActive = false
            sidebarQuery = ""
        }
    }

    func toggleFolder(_ folder: WorkspaceFolder) {
        Haptics.select()
        autoExpandFolders = false
        let wasOpen = folderIsOpen(folder)
        let motion = Animation.spring(response: 0.26, dampingFraction: 0.88)
        withAnimation(motion) {
            if wasOpen {
                openFolders.remove(folder.id)
            } else {
                openFolders.insert(folder.id)
            }
        }
    }

    /// Open in the drawer (keyed by computer and path).
    func folderIsOpen(_ folder: WorkspaceFolder) -> Bool {
        openFolders.contains(folder.id)
    }

    /// The agent's live status line for a chat.
    func liveStatus(for chatId: String) -> String {
        if let s = turnStatus[chatId] { return s }
        _ = chats
        guard runningChatIds.contains(chatId) else { return "" }
        if let last = messagesByChat[chatId]?.last(where: { $0.role == .agent }), last.isStreaming {
            return last.activeStatusLabel.isEmpty ? ProtocolV1.liveStatus(of: last) : last.activeStatusLabel
        }
        return String(localized: "Thinking")
    }

    func chatIsRunning(_ chat: ChatSummary) -> Bool {
        localRunning.contains(chat.id) || chat.state == .running
    }

    func folderIsRunning(_ folder: WorkspaceFolder) -> Bool {
        _ = chats
        return (chatsByFolderKey[folder.id] ?? []).contains { localRunning.contains($0.id) || runningChatIds.contains($0.id) }
    }

    // MARK: - Links

    /// `agy-rem://session/<id>`, `agy-rem://sidebar`, `agy-rem://scroll-mid`, `agy-rem://pair?…`.
    func handleIncomingURL(_ url: URL) {
        guard url.scheme == "agy-rem" else { return }
        switch url.host {
        case "sidebar": showSidebar()
        case "scroll-mid": scrollToMiddleTick += 1
        case "session":
            let id = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard !id.isEmpty else { return }
            if let found = resolveChatId(id) { openChatAndReveal(found) } else { pendingLinkChatId = id }
        // A link can come from any web page or app: nothing is contacted until the user agrees.
        case "pair": pendingPairLink = url.absoluteString
        default: break
        }
    }

    /// An `agy-rem://pair` link opened from outside the app, waiting for the user's yes. Until
    /// then the computer it names learns nothing (not this phone's address, not its id).
    var pendingPairLink: String?

    func answerPairLink(_ go: Bool) {
        guard let link = pendingPairLink else { return }
        pendingPairLink = nil
        if go { Task { await pairConnector(rawTicket: link) } }
    }

    private func resolveChatId(_ id: String) -> String? {
        chats.first { $0.id == id }?.id ?? chats.first { $0.id.hasPrefix(id) }?.id
    }

    private func openPendingLink() {
        guard let want = pendingLinkChatId, let found = resolveChatId(want) else { return }
        pendingLinkChatId = nil
        openChatAndReveal(found)
    }

    /// Opens a chat picked outside the drawer (a link or search) and scrolls the drawer to it.
    func openChatAndReveal(_ id: String) {
        Task {
            await openChat(id)
            sidebarRevealTick += 1
        }
    }

    // MARK: - Pairing

    /// A pairing that waits for the user: this phone shows a number to type into
    /// `agy-connector pair` on the computer, or (an older code) asks for a plain yes. Nothing is
    /// added before that, so a link or QR code can't pair this phone with a computer on its own.
    struct PendingPairing: Identifiable {
        let id = UUID()
        let ticket: P2PClient.Ticket
        /// The computer's name as it reports it.
        let computer: String
        /// The 6-digit number to show. Only someone at the computer's keyboard can finish the
        /// pairing with it; the computer itself never displays it.
        let number: String
        var error: String?
        var busy = false
    }

    var pendingPairing: PendingPairing?
    /// A computer just paired and not answered yet: "Connecting to …" in the middle of the screen.
    var newlyPaired: String?

    /// Starts pairing from a scanned, pasted or linked code. With a code from `agy-connector pair`
    /// the phone shows a number and waits until it was typed on the computer (`awaitPairing`).
    func pairConnector(rawTicket: String) async {
        pairingError = nil
        if DemoConnector.isCode(rawTicket) {
            let demo = P2PClient.Ticket(endpointId: DemoConnector.endpointId, uri: DemoConnector.ticket,
                                        hostName: DemoConnector.computerName, hardwareModel: "Mac16,10", osVersion: "macOS 26.1",
                                        pairingSecret: nil)
            await addPaired(demo)
            return
        }
        switch P2PClient.parseTicket(rawTicket) {
        case .failure(let e):
            // A code that doesn't parse is a typo or a partial paste; the raw parser error means nothing to people.
            let message = String(localized: "That isn't a complete pairing code. Copy the whole line agy-connector printed, or scan the QR code.")
            pairingError = message
            lastP2PError = e.localizedDescription
            noteConnectionFailure()
            // The setup page shows the error under the field; elsewhere (a link) only a toast can.
            if !showsLanding { showToast(message) }
        case .success(let t):
            // Only a code from `agy-connector pair` (with its one-time secret) pairs.
            guard let secret = t.pairingSecret else {
                let message = String(localized: "That isn't a complete pairing code. Copy the whole line agy-connector printed, or scan the QR code.")
                pairingError = message
                if !showsLanding { showToast(message) }
                return
            }
            guard let client else { pairingError = String(localized: "Couldn't start the connection."); return }
            do {
                let reply = try await client.request(ticket: t.uri, method: "pair.begin",
                                                     body: P2PClient.encode(["secret": secret]), timeoutMs: 20_000)
                let name = (reply["computer"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? t.hostName
                guard let number = reply["code"] as? String, number.count == 6, number.allSatisfy(\.isNumber) else {
                    throw P2PError.message(String(localized: "The computer sent back something unexpected. Try pairing again."))
                }
                let waiting = PendingPairing(ticket: t, computer: name.isEmpty ? String(localized: "Computer") : name,
                                             number: number)
                await closeSheets()
                pendingPairing = waiting
                Task { await awaitPairing(waiting.id) }
            } catch {
                let message = Self.pairingMessage(error)
                pairingError = message
                lastP2PError = (error as? P2PError)?.diagnostic ?? error.localizedDescription
                // Couldn't reach the computer at all: counts toward offering the relay.
                if case P2PError.remote = error {} else { noteConnectionFailure() }
                if !showsLanding { showToast(message) }
            }
        }
    }

    /// Asks the computer about once a second whether the number was typed there, and adds the
    /// computer once it was. Ends when the sheet is closed or the code stops working.
    private func awaitPairing(_ id: UUID) async {
        let started = Date()
        while let pending = pendingPairing, pending.id == id, pending.error == nil {
            try? await Task.sleep(for: .seconds(1))
            guard pendingPairing?.id == id, let client, let secret = pending.ticket.pairingSecret else { return }
            do {
                let reply = try await client.request(ticket: pending.ticket.uri, method: "pair.confirm",
                                                     body: P2PClient.encode(["secret": secret]), timeoutMs: 10_000)
                guard pendingPairing?.id == id else { return }
                guard let issued = reply["ticket"] as? String, case .success(let stored) = P2PClient.parseTicket(issued),
                      stored.endpointId == pending.ticket.endpointId, stored.pairingSecret == nil else {
                    throw P2PError.message(String(localized: "The computer sent back something unexpected. Try pairing again."))
                }
                pendingPairing = nil
                await addPaired(stored)
                return
            } catch {
                guard pendingPairing?.id == id else { return }
                if case P2PError.remote(let code, _) = error, code == "pair_pending" { continue }
                // A dropped connection is tried again for as long as the code lasts (ten minutes).
                let answered: Bool = switch error {
                case P2PError.remote, P2PError.message: true
                default: false
                }
                if !answered, Date().timeIntervalSince(started) < 600 { continue }
                pendingPairing?.error = Self.pairingMessage(error)
                return
            }
        }
    }

    /// Closes whatever sheet is up. The number to type is shown by one sheet at the app's root,
    /// which can't appear over another sheet (pairing a second computer starts in Connectors).
    private func closeSheets() async {
        let open = showComputerPicker || showSettings || showAddSheet || showModelPicker || showUsage || showSearch
        showComputerPicker = false; showSettings = false; showAddSheet = false
        showModelPicker = false; showUsage = false; showSearch = false
        if open { try? await Task.sleep(for: .milliseconds(450)) }
    }

    func cancelPairing() {
        pendingPairing = nil
    }

    // MARK: - Links in agent replies

    /// A file an agent reply linked to, read from the computer (`files.read`).
    struct FileView: Identifiable {
        let id = UUID()
        let link: String
        var name: String
        var text: String?
        var error: String?
        var truncated = false
        /// First line of a `#L12` / `#L12-L20` anchor, 1-based.
        var line: Int?
    }

    var fileView: FileView?
    /// A web link waiting for the user to see where it really goes.
    var pendingWebLink: URL?

    enum ChatLink: Equatable { case web, file, refused }

    /// What a link in an agent reply is: only web links leave the app (after the user sees the
    /// address); links to files open in the app; anything else (other apps, `agy-rem://`) is
    /// never opened, so a reply can't trigger pairing or another app.
    nonisolated static func classify(_ url: URL) -> ChatLink {
        switch url.scheme?.lowercased() {
        case "http", "https": return url.host?.isEmpty == false ? .web : .refused
        case "file": return .file
        case nil: return url.relativeString.isEmpty ? .refused : .file
        default: return .refused
        }
    }

    func handleChatLink(_ url: URL) {
        switch Self.classify(url) {
        case .web: pendingWebLink = url
        case .file: openFileLink(url)
        case .refused: showToast(String(localized: "AGY Remote doesn't open this kind of link."))
        }
    }

    private func openFileLink(_ url: URL) {
        let link = url.scheme == nil ? url.relativeString : url.absoluteString
        let name = (url.path.isEmpty ? link : (url.path as NSString).lastPathComponent)
        let anchor = url.fragment ?? ""
        let line = anchor.hasPrefix("L") ? Int(anchor.dropFirst().prefix { $0.isNumber }) : nil
        fileView = FileView(link: link, name: name, line: line)
        let chat = selectedChatId
        let target = chat.flatMap { device(forChat: $0) }
        Task {
            var params: [String: Any] = ["path": link]
            if let chat { params["chatId"] = chat }
            do {
                let reply = try await call("files.read", params, on: target)
                guard fileView?.link == link else { return }
                fileView?.name = (reply["name"] as? String) ?? name
                fileView?.text = (reply["text"] as? String) ?? ""
                fileView?.truncated = reply["truncated"] as? Bool ?? false
            } catch {
                guard fileView?.link == link else { return }
                if case P2PError.remote(let code, _) = error, code == "not_allowed" {
                    fileView?.error = String(localized: "This file is outside the folders the computer shares.")
                } else if case P2PError.remote(let code, _) = error, code == "not_text" {
                    fileView?.error = String(localized: "This file isn't text, so it can't be shown here.")
                } else {
                    fileView?.error = error.localizedDescription
                }
            }
        }
    }

    private static func pairingMessage(_ error: Error) -> String {
        if case P2PError.remote(let code, let message) = error {
            switch code {
            case "pairing_closed": return String(localized: "This pairing code no longer works. Run agy-connector pair on the computer again.")
            case "busy": return String(localized: "Another phone is already pairing with this code. Run agy-connector pair on the computer again.")
            default: return message.isEmpty ? String(localized: "Pairing failed.") : message
            }
        }
        return error.localizedDescription
    }

    /// Adds (or updates) a computer the user just confirmed and connects to it.
    private func addPaired(_ t: P2PClient.Ticket) async {
        var entry = devices.first { $0.id == t.endpointId }
            ?? RemoteDevice(id: t.endpointId, ticket: t.uri, name: t.hostName, hardwareModel: t.hardwareModel, osVersion: t.osVersion)
        entry.ticket = t.uri
        if entry.name.isEmpty { entry.name = t.hostName.isEmpty ? String(localized: "Computer") : t.hostName }
        if entry.hardwareModel.isEmpty { entry.hardwareModel = t.hardwareModel }
        // Other computers stay as they are, even one with the same name: a new code never
        // replaces a paired computer on its own (unpair the old one in the list instead).
        var list = devices.filter { $0.id != t.endpointId }
        list.insert(entry, at: 0)
        devices = list
        selectedDeviceId = t.endpointId
        lastP2PError = nil
        // Freshly accepted: "Connecting…" until the first sync answers.
        errorByDevice[t.endpointId] = nil
        if let i = devices.firstIndex(where: { $0.id == t.endpointId }) { devices[i].status = .standby }
        if connectLoop == nil { startConnecting() }
        newlyPaired = t.endpointId
        let ok = await sync(deviceId: t.endpointId, announce: true)
        if newlyPaired == t.endpointId { newlyPaired = nil }
        if !ok { pairingError = lastP2PError }
    }

    func completeOnboarding() {
        forceLanding = false
        withAnimation(.smooth(duration: 0.5)) { onboardingComplete = true }
    }

    func selectDevice(_ device: RemoteDevice) {
        Haptics.select()
        selectedDeviceId = device.id
        Task { await sync(deviceId: device.id, announce: true) }
    }

    func removeDevice(_ device: RemoteDevice) {
        let id = device.id
        PushKeyStore.remove(computer: id)
        PushGrants.remove(computer: id)
        pushRelays[id] = nil
        if let client, !device.isDemo, notificationsEnabled || pushSignatures[id] != nil {
            let ticket = device.ticket
            Task { _ = try? await client.request(ticket: ticket, method: "notifications.unregister") }
        }
        pushSignatures[id] = nil
        pushRegisteredAt[id] = nil
        pushRetryAfter[id] = nil
        var draftKeys: Set<String> = []
        for t in turns.values where t.deviceId == id {
            client?.detach(ticket: device.ticket, requestId: t.requestId)
            if t.chatId == nil { draftKeys.insert(t.draftKey) }
            turns[t.requestId] = nil
        }
        // A new chat still waiting for its id on that computer goes too.
        for k in draftKeys { messagesByChat[k] = nil; usageByChat[k] = nil; turnNoticeByChat[k] = nil }
        if let k = viewingDraftKey, draftKeys.contains(k) { viewingDraftKey = nil }
        let gone = Set((chatsByHost[id] ?? []).map(\.id))
        for cid in gone {
            messagesByChat[cid] = nil; usageByChat[cid] = nil; unreadableByChat[cid] = nil; turnNoticeByChat[cid] = nil
            loadedUpdated[cid] = nil; lastRunning[cid] = nil; lastCliReload[cid] = nil
            openSeq[cid] = nil; openInFlight.remove(cid); optionsAdopted.remove(cid)
        }
        lastOpenInCli.subtract(gone)
        let folderWasThere = currentFolder?.host == id
        chatsByHost[id] = nil
        foldersByHost[id] = nil
        protocolByHost[id] = nil
        syncTasks[id]?.cancel(); syncTasks[id] = nil
        lastSyncAt[id] = nil; lastInfoAt[id] = nil
        failuresByDevice[id] = nil; errorByDevice[id] = nil
        optionsPushed.remove(id)
        devices.removeAll { $0.id == id }
        if let sel = selectedChatId, gone.contains(sel) { selectedChatId = nil }
        if selectedDeviceId == id || !devices.contains(where: { $0.id == selectedDeviceId }) { selectedDeviceId = devices.first?.id }
        rebuildLists()
        if folderWasThere {
            currentFolderPath = folders.first { $0.host == selectedDevice?.id }?.path ?? folders.first?.path
        }
        refreshVersionIssue()
        if let next = selectedDevice?.id { Task { await sync(deviceId: next, full: true) } }
        else { onboardingComplete = false } // back to setup; only its Start button closes it
    }

    // MARK: - Setup connection (page 4)

    enum SetupConnection: Equatable { case connecting, connected, failed }
    /// Set while page 4's Retry runs, so it shows Connecting… instead of the old error.
    var setupRetrying = false

    /// The computer just paired: connecting until its first sync answers, then connected, or failed.
    var setupConnection: SetupConnection {
        guard let d = selectedDevice else { return .connecting }
        if d.status == .connected { return .connected }
        if errorByDevice[d.id] != nil, !setupRetrying { return .failed }
        return .connecting
    }
    var setupError: String? { selectedDevice.flatMap { errorByDevice[$0.id] } }

    func retrySetupConnection() {
        guard let id = selectedDevice?.id, !setupRetrying else { return }
        setupRetrying = true
        Task {
            let ok = await sync(deviceId: id, full: true)
            pairingError = ok ? nil : errorByDevice[id]
            setupRetrying = false
        }
    }

    func setRelayAllowed(_ allowed: Bool) {
        if !allowed { relayDeclined = true }
        relayAllowed = allowed
        showRelayConsent = false
    }

    /// "Use Relay" / "Not Now" in the relay question.
    func answerRelayConsent(_ allow: Bool) {
        if allow { relayAllowed = true } else { relayDeclined = true }
        showRelayConsent = false
    }

    /// Reaching a computer failed. After two in a row for the computer in use (selected, or
    /// where the next message goes), without the relay, asks once per launch.
    private func noteConnectionFailure(_ deviceId: String? = nil) {
        guard !relayAllowed, !relayDeclined else { return }
        let count: Int
        if let deviceId {
            count = (failuresByDevice[deviceId] ?? 0) + 1
            failuresByDevice[deviceId] = count
            guard deviceId == selectedDevice?.id || deviceId == targetDevice?.id else { return }
        } else {
            consecutiveFailures += 1
            count = consecutiveFailures
        }
        if count >= 2, !relayAsked {
            relayAsked = true
            showRelayConsent = true
        }
    }

    // MARK: - Connection

    /// Seconds between syncs of a computer that nothing on screen or running depends on.
    private static let backgroundSyncInterval: TimeInterval = 20
    /// How often connector.info is re-read once connected.
    private static let infoInterval: TimeInterval = 60

    /// Keeps every paired computer in sync: quickly the selected one, the open chat's and those
    /// with running turns, the others now and then.
    func startConnecting() {
        connectLoop?.cancel()
        connectLoop = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.kickSyncs()
                try? await Task.sleep(for: .milliseconds(1300))
            }
        }
    }

    /// Syncs every computer once and waits for all of them.
    func connectOnce() async { await syncAllNow() }

    private func syncAllNow() async {
        guard !devices.isEmpty else { return }
        isRefreshingP2P = true
        defer { isRefreshingP2P = false }
        let pending = devices.map { id in Task { [weak self] in _ = await self?.sync(deviceId: id.id) } }
        for task in pending { await task.value }
    }

    private func hostIsBusy(_ id: String) -> Bool {
        turns.values.contains { $0.deviceId == id } || (chatsByHost[id] ?? []).contains { $0.state == .running }
    }

    private func kickSyncs() {
        let now = Date()
        let onScreen = selectedChatId != nil || anyRunning || sidebarOpen
        let openHost = selectedChatId.flatMap { hostDevice(forChat: $0)?.id }
        for id in devices.map(\.id) where syncTasks[id] == nil {
            let fast = id == selectedDevice?.id || id == openHost || hostIsBusy(id)
            let interval: TimeInterval = fast ? (onScreen || hostIsBusy(id) ? 1.3 : 5) : Self.backgroundSyncInterval
            guard now.timeIntervalSince(lastSyncAt[id] ?? .distantPast) >= interval - 0.05 else { continue }
            Task { [weak self] in _ = await self?.sync(deviceId: id) }
        }
    }

    /// Pull-to-refresh / refresh button: the selected computer, with its latency in a toast.
    func refreshActiveConnector() async {
        guard let id = selectedDevice?.id else {
            showComputerPicker = true
            return
        }
        isRefreshingP2P = true
        defer { isRefreshingP2P = false }
        await sync(deviceId: id, announce: true, full: true)
    }

    /// One sync of one computer: its info (when due or `full`), then its chats. Returns whether
    /// it worked. A sync already running is waited for; `announce`/`full` then run a fresh one.
    @discardableResult
    private func sync(deviceId: String, announce: Bool = false, full: Bool = false) async -> Bool {
        if let running = syncTasks[deviceId] {
            let ok = await running.value
            guard announce || full else { return ok }
            if let again = syncTasks[deviceId], again != running { return await again.value }
        }
        let wantInfo = full || announce || Date().timeIntervalSince(lastInfoAt[deviceId] ?? .distantPast) >= Self.infoInterval
            || device(deviceId)?.status != .connected
        let task = Task { [weak self] () -> Bool in
            await self?.performSync(deviceId: deviceId, withInfo: wantInfo) ?? false
        }
        syncTasks[deviceId] = task
        let ok = await task.value
        syncTasks[deviceId] = nil
        lastSyncAt[deviceId] = Date()
        guard device(deviceId) != nil else { return false }
        if ok {
            // Slow follow-ups run after the guard is released.
            openPendingLink()
            if notificationsEnabled { await registerPush(on: deviceId, force: false) }
            await refreshOpenChat(onHost: deviceId)
            if announce, let d = device(deviceId) { showToast("\(d.name) · \(d.latencyMs)ms") }
        } else if announce, let e = errorByDevice[deviceId] {
            showToast(e)
        }
        return ok
    }

    private func performSync(deviceId: String, withInfo: Bool) async -> Bool {
        guard let start = device(deviceId), let client else { return false }
        let began = Date()
        do {
            var info: [String: Any]?
            if withInfo { info = try await client.request(ticket: start.ticket, method: "connector.info") }
            let infoElapsed = Date().timeIntervalSince(began)
            let list = try await client.request(ticket: start.ticket, method: "chats.list")
            guard device(deviceId) != nil else { return false }
            consecutiveFailures = 0
            failuresByDevice[deviceId] = 0
            lastP2PError = nil
            if errorByDevice[deviceId] != nil { errorByDevice[deviceId] = nil }
            if let info {
                lastInfoAt[deviceId] = Date()
                apply(info: info, to: deviceId, elapsed: infoElapsed)
            } else if let i = devices.firstIndex(where: { $0.id == deviceId }), devices[i].status != .connected {
                devices[i].status = .connected
            }
            applyChats(list, host: deviceId)
            if deviceId == selectedDevice?.id {
                if let info { applyMeta(info, from: deviceId) }
                refreshQuota(force: false)
            }
            refreshVersionIssue()
            noticeCliReleases()
            return true
        } catch {
            if Task.isCancelled { return false }
            lastP2PError = (error as? P2PError)?.diagnostic ?? error.localizedDescription
            errorByDevice[deviceId] = error.localizedDescription
            noteConnectionFailure(deviceId)
            lastInfoAt[deviceId] = nil
            if let i = devices.firstIndex(where: { $0.id == deviceId }), devices[i].status != .offline { devices[i].status = .offline }
            refreshVersionIssue()
            return false
        }
    }

    private func apply(info: [String: Any], to id: String, elapsed: TimeInterval) {
        guard let i = devices.firstIndex(where: { $0.id == id }) else { return }
        let p2p = info["_p2p"] as? [String: Any] ?? [:]
        var d = devices[i]
        d.status = .connected
        d.latencyMs = (p2p["rtt_ms"] as? NSNumber)?.intValue ?? max(1, Int(elapsed * 1000))
        d.transport = p2p["transport"] as? String ?? d.transport
        if let c = info["computer"] as? [String: Any] {
            d.name = c["name"] as? String ?? d.name
            d.hardwareModel = c["model"] as? String ?? d.hardwareModel
            d.osVersion = c["os"] as? String ?? d.osVersion
        }
        if let a = info["agy"] as? [String: Any] { d.agentVersion = a["version"] as? String ?? d.agentVersion }
        let connector = info["connector"] as? [String: Any] ?? [:]
        d.connectorVersion = connector["version"] as? String ?? d.connectorVersion
        // A connector that says nothing about its version or protocol predates protocol 1.
        d.versionMissing = connector["version"] == nil && info["protocol"] == nil
        d.minAppVersion = connector["minAppVersion"] as? String ?? ""
        d.updateNote = (connector["updateNote"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        if let push = connector["push"] as? [String: Any], let relay = push["relay"] as? String, let key = push["key"] as? String,
           PushGrants.endpoint(relay: relay) != nil {
            pushRelays[id] = (relay, key)
        } else {
            pushRelays[id] = nil
        }
        if devices[i] != d { devices[i] = d }
        protocolByHost[id] = (info["protocol"] as? NSNumber)?.intValue
        let newFolders = (info["folders"] as? [[String: Any]] ?? []).compactMap(ProtocolV1.folder).map {
            var f = $0; f.host = id; return f
        }
        if foldersByHost[id] != newFolders { foldersByHost[id] = newFolders }
    }

    /// Whether a computer and this app fit together (protocol, then versions); only while connected.
    func versionIssue(for d: RemoteDevice) -> VersionIssue? {
        guard d.status == .connected else { return nil }
        if let p = protocolByHost[d.id], p > ProtocolV1.version { return .updateApp }
        if let p = protocolByHost[d.id], p < ProtocolV1.version { return .updateConnector }
        if d.appTooOld { return .updateApp }
        if d.connectorTooOld { return .updateConnector }
        return nil
    }

    /// Version check of the selected computer, only while it is connected.
    private func refreshVersionIssue() {
        guard let d = selectedDevice, d.status == .connected else {
            if versionIssue != nil { versionIssue = nil }
            if connectorUpdateNote != nil { connectorUpdateNote = nil }
            return
        }
        if connectorUpdateNote != d.updateNote { connectorUpdateNote = d.updateNote }
        let issue = versionIssue(for: d)
        if versionIssue != issue { versionIssue = issue }
    }

    /// Models, modes and commands come from the selected computer. On a computer's first sync the
    /// saved choices are sent to it; after that the app follows the computer's current choices
    /// while nothing local is running or waiting to be confirmed.
    private func applyMeta(_ info: [String: Any], from id: String) {
        let models = (info["models"] as? [[String: Any]] ?? []).compactMap(ProtocolV1.model)
        let modeList = (info["modes"] as? [[String: Any]] ?? []).compactMap(ProtocolV1.mode)
        let cmds: [SlashCommand] = (info["commands"] as? [[String: Any]] ?? []).compactMap { c in
            guard let n = c["name"] as? String else { return nil }
            let about = c["description"] as? String
            return SlashCommand(name: n, description: about ?? "")
        }
        if !models.isEmpty, models != baseModels { baseModels = models }
        if !modeList.isEmpty, modeList != modes { modes = modeList }
        if !cmds.isEmpty, cmds != commands { commands = cmds }

        let fallback = info["defaults"] as? [String: Any] ?? [:]
        let defModel = fallback["model"] as? String
        let defEffort = fallback["effort"] as? String
        let defMode = fallback["mode"] as? String

        let firstSync = !optionsPushed.contains(id)
        if !firstSync, selectedChatId == nil, optionChangesPending == 0, localRunning.isEmpty {
            adoptChatOptions(fallback)
        }
        let modelIds = baseModels.map(\.id)
        if let current = selectedModelId, modelIds.contains(current) {
            // still offered
        } else {
            selectedModelId = defModel.flatMap { modelIds.contains($0) ? $0 : nil } ?? modelIds.first
        }
        validateEffort(preferring: defEffort)
        let modeIds = modes.map(\.id)
        if let firstMode = modeIds.first, !modeIds.contains(selectedModeId) {
            selectedModeId = defMode.flatMap { modeIds.contains($0) ? $0 : nil } ?? firstMode
        }

        guard firstSync, !baseModels.isEmpty || !modes.isEmpty,
              let target = device(id), let client else { return }
        optionsPushed.insert(id)
        var changes: [(String, String)] = []
        if let m = selectedModelId, m != defModel { changes.append(("model", m)) }
        if let e = selectedEffortValue, e != defEffort { changes.append(("effort", e)) }
        if selectedModeId != defMode { changes.append(("mode", selectedModeId)) }
        guard !changes.isEmpty else { return }
        optionChangesPending += 1
        Task {
            for (option, value) in changes {
                let body: [String: Any] = ["chatId": NSNull(), "option": option, "value": value]
                _ = try? await client.request(ticket: target.ticket, method: "chat.set_option", body: P2PClient.encode(body))
            }
            optionChangesPending -= 1
        }
    }

    /// Keeps the effort valid for the selected model.
    private func validateEffort(preferring preferred: String? = nil) {
        let efforts = availableEffortsForSelectedModel
        if efforts.isEmpty { if selectedEffortValue != nil, !baseModels.isEmpty { selectedEffortValue = nil }; return }
        guard !efforts.contains(where: { $0.value == selectedEffortValue }) else { return }
        selectedEffortValue = efforts.first { $0.value == preferred }?.value
            ?? efforts.first { $0.value == "medium" }?.value ?? efforts[0].value
    }

    static var appVersion: String {
        let info = Bundle.main.infoDictionary ?? [:]
        return (info["CFBundleShortVersionString"] as? String) ?? "1.0"
    }
    static var buildNumber: String {
        let info = Bundle.main.infoDictionary ?? [:]
        return (info["CFBundleVersion"] as? String) ?? "1"
    }

    private func call(_ method: String, _ params: [String: Any] = [:], on target: RemoteDevice? = nil) async throws -> [String: Any] {
        guard let d = target ?? targetDevice, let client else { throw P2PError.message(String(localized: "Not connected")) }
        return try await client.request(ticket: d.ticket, method: method, body: P2PClient.encode(params))
    }

    /// Re-reads the selected computer's chats.
    func refreshChats() async {
        guard let id = selectedDevice?.id else { return }
        await sync(deviceId: id)
    }

    private func applyChats(_ reply: [String: Any], host: String) {
        var list = (reply["chats"] as? [[String: Any]] ?? []).compactMap(ProtocolV1.chat).map { c -> ChatSummary in
            var x = c; x.host = host; return x
        }
        // Chats this phone is running a turn in stay running, and a brand-new one stays listed.
        let previous = chatsByHost[host] ?? []
        for t in turns.values where t.deviceId == host && !t.stopRequested {
            guard let cid = t.chatId else { continue }
            if let i = list.firstIndex(where: { $0.id == cid }) { list[i].state = .running }
            else if let old = previous.first(where: { $0.id == cid }) { list.insert(old, at: 0) }
        }
        guard list != previous || chatsByHost[host] == nil else { return }
        chatsByHost[host] = list
        rebuildLists()
    }

    /// The selected computer's lists (others stay apart until chosen): chats newest first; folders
    /// (listed or used by a chat) by their newest chat, then by name.
    private func rebuildLists() {
        let shown = selectedDevice.map { [$0] } ?? []
        let merged = shown.flatMap { chatsByHost[$0.id] ?? [] }.sorted { $0.updated > $1.updated }
        var newest: [String: Date] = [:]
        for c in merged {
            guard let p = c.folder else { continue }
            let k = c.host + "|" + p
            if newest[k] == nil { newest[k] = c.updated }
        }
        var all: [WorkspaceFolder] = []
        for d in shown {
            var seen = Set<String>()
            for f in foldersByHost[d.id] ?? [] where seen.insert(f.path).inserted { all.append(f) }
            for c in chatsByHost[d.id] ?? [] {
                guard let p = c.folder, seen.insert(p).inserted else { continue }
                let name = (p as NSString).lastPathComponent
                all.append(WorkspaceFolder(path: p, name: name.isEmpty ? p : name, kind: nil, host: d.id, synthesized: true))
            }
        }
        all.sort { a, b in
            let x = newest[a.id] ?? .distantPast, y = newest[b.id] ?? .distantPast
            guard x == y else { return x > y }
            return a.name.localizedStandardCompare(b.name) == .orderedAscending
        }
        rebuildIndex(chats: merged, folders: all)
        if merged != chats { chats = merged }
        if all != folders { folders = all }
        if autoExpandFolders {
            let fresh = Set(newest.keys).subtracting(autoExpanded)
            if !fresh.isEmpty {
                autoExpanded.formUnion(fresh)
                openFolders.formUnion(fresh)
            }
        }
    }

    /// The drawer's lookups: chats per folder, chats in no listed folder, running chats.
    private func rebuildIndex(chats list: [ChatSummary], folders all: [WorkspaceFolder]) {
        var byFolder: [String: [ChatSummary]] = [:]
        var unfiled: [ChatSummary] = []
        var running: Set<String> = []
        let known = Set(all.map(\.id))
        for c in list {
            if c.state == .running { running.insert(c.id) }
            if let p = c.folder, known.contains(c.host + "|" + p) { byFolder[c.host + "|" + p, default: []].append(c) }
            else { unfiled.append(c) }
        }
        chatsByFolderKey = byFolder
        unfiledChats = unfiled
        runningChatIds = running
    }

    private func patchChat(_ id: String, _ change: (inout ChatSummary) -> Void) {
        for host in chatsByHost.keys {
            if let i = chatsByHost[host]?.firstIndex(where: { $0.id == id }) { change(&chatsByHost[host]![i]) }
        }
        if let i = chats.firstIndex(where: { $0.id == id }) {
            var c = chats[i]
            change(&c)
            if c != chats[i] { chats[i] = c; rebuildIndex(chats: chats, folders: folders) }
        }
    }

    /// The chat on screen was released by the agy CLI: say it can be continued here.
    private func noticeCliReleases() {
        let now = Set(chats.filter { $0.state == .openInCli }.map(\.id))
        if let id = selectedChatId, lastOpenInCli.contains(id), !now.contains(id) {
            announceAvailableAgain(chats.first { $0.id == id }?.title ?? "")
            Task { await loadChat(id, quiet: true) }
        }
        lastOpenInCli = now
    }

    /// Re-reads the open chat when it runs (or just stopped), is open in the CLI, or changed.
    private func refreshOpenChat(onHost host: String) async {
        guard let id = selectedChatId, turn(forChat: id) == nil,
              let chat = chatsByHost[host]?.first(where: { $0.id == id }) else { return }
        let cliDue = chat.state == .openInCli && Date().timeIntervalSince(lastCliReload[id] ?? .distantPast) > 2.5
        if cliDue { lastCliReload[id] = Date() }
        let changed = loadedUpdated[id].map { $0 != chat.changeStamp } ?? true
        if chat.state == .running || lastRunning[id] == true || cliDue || changed {
            await loadChat(id, quiet: true)
        }
    }

    // MARK: - Chats

    func startNewChat(in folder: WorkspaceFolder? = nil) {
        Haptics.impact()
        if let folder {
            currentFolderPath = folder.path
            if device(folder.host) != nil { selectDeviceQuietly(folder.host) }
        }
        withAnimation(.easeInOut(duration: 0.25)) {
            selectedChatId = nil
            viewingDraftKey = nil
        }
        isLoadingChat = false
        gradientFlow.setZeroStateActive(true, triggerPulse: true)
    }

    /// Picks the folder (and its computer) for the next new chat.
    func selectFolder(_ folder: WorkspaceFolder) {
        Haptics.select()
        currentFolderPath = folder.path
        openFolders.insert(folder.id)
        if device(folder.host) != nil { selectDeviceQuietly(folder.host) }
        withAnimation(.easeInOut(duration: 0.25)) {
            selectedChatId = nil
            viewingDraftKey = nil
        }
        gradientFlow.setZeroStateActive(true, triggerPulse: true)
    }

    /// Selects a computer without a toast; a change re-reads its info.
    private func selectDeviceQuietly(_ id: String) {
        guard selectedDeviceId != id else { return }
        selectedDeviceId = id
        Task { await sync(deviceId: id, full: true) }
    }

    func openChat(_ requested: String, quiet: Bool = false) async {
        let id = resolveChatId(requested) ?? requested
        if !quiet {
            Haptics.select()
            selectedChatId = id
            viewingDraftKey = nil
            if let chat = listedChat(id) {
                if let f = chat.folder { currentFolderPath = f }
                if device(chat.host) != nil { selectDeviceQuietly(chat.host) }
            }
            gradientFlow.setZeroStateActive(false, triggerPulse: false)
            gradientFlow.glow = isRunning ? .working : .idle
            if turn(forChat: id) != nil { isLoadingChat = false; return }
            isLoadingChat = (messagesByChat[id] ?? []).isEmpty
        }
        await loadChat(id, quiet: quiet)
    }

    /// `chat.open` on the chat's computer, into the cache. A quiet reload is skipped while one is
    /// out; a newer request makes older replies stale, and stale replies are dropped.
    private func loadChat(_ id: String, quiet: Bool) async {
        if quiet, openInFlight.contains(id) { return }
        guard let host = hostDevice(forChat: id), let client else {
            if listedChat(id) == nil { pendingLinkChatId = id }
            if selectedChatId == id { isLoadingChat = false }
            return
        }
        let seq = (openSeq[id] ?? 0) + 1
        openSeq[id] = seq
        openInFlight.insert(id)
        defer {
            if openSeq[id] == seq {
                openInFlight.remove(id)
                if selectedChatId == id { isLoadingChat = false }
            }
        }
        do {
            let opened = try await client.request(ticket: host.ticket, method: "chat.open",
                                                  body: P2PClient.encode(["chatId": id])) { reply in
                OpenedChat(raw: SendableDict(reply), messages: ProtocolV1.messages(reply["messages"], chatId: id))
            }
            guard openSeq[id] == seq else { return }
            let reply = opened.raw.value
            let state = (reply["state"] as? String).flatMap(ChatSummary.State.init(rawValue:)) ?? .idle
            var why: String?
            if reply["readable"] as? Bool == false {
                why = (reply["unreadableReason"] as? String) ?? "desktop_app"
            }
            if unreadableByChat[id] != why { unreadableByChat[id] = why }
            if let ctx = reply["context"] as? [String: Any],
               let used = (ctx["used"] as? NSNumber)?.intValue, let size = (ctx["size"] as? NSNumber)?.intValue {
                let usage = SessionUsage(used: used, size: size)
                if usageByChat[id] != usage { usageByChat[id] = usage }
            }
            if turn(forChat: id) != nil { return }
            if messagesByChat[id] != opened.messages { messagesByChat[id] = opened.messages }
            if let listed = chats.first(where: { $0.id == id }) {
                if listed.state != state { patchChat(id) { $0.state = state } }
                loadedUpdated[id] = (chats.first { $0.id == id } ?? listed).changeStamp
            }
            let wasRunning = lastRunning[id] ?? false
            lastRunning[id] = state == .running
            guard selectedChatId == id else { return }
            if state == .running {
                if gradientFlow.glow != .working { gradientFlow.glow = .working }
            } else if wasRunning {
                gradientFlow.glow = .idle
            }
            if let o = reply["options"] as? [String: Any],
               !optionsAdopted.contains(id) || (optionChangesPending == 0 && localRunning.isEmpty) {
                adoptChatOptions(o)
                optionsAdopted.insert(id)
            }
        } catch {
            guard openSeq[id] == seq else { return }
            if !quiet { showToast(error.localizedDescription) }
        }
    }

    private struct OpenedChat: Sendable {
        let raw: SendableDict
        let messages: [ChatMessage]
    }

    /// The open chat's own model, effort and mode (or the computer's current ones), when known.
    private func adoptChatOptions(_ o: [String: Any]) {
        if let m = o["model"] as? String, m != selectedModelId, baseModels.contains(where: { $0.id == m }) { selectedModelId = m }
        if let e = o["effort"] as? String, e != selectedEffortValue, availableEffortsForSelectedModel.contains(where: { $0.value == e }) {
            selectedEffortValue = e
        }
        validateEffort()
        if let m = o["mode"] as? String, m != selectedModeId, modes.contains(where: { $0.id == m }) { selectedModeId = m }
    }

    func setFolder(_ folder: WorkspaceFolder, for chatId: String) async {
        do { try await assignFolder(folder, to: chatId) } catch { showToast(error.localizedDescription) }
    }

    private func assignFolder(_ folder: WorkspaceFolder, to chatId: String) async throws {
        _ = try await call("chat.set_folder", ["chatId": chatId, "folder": folder.path], on: device(forChat: chatId))
        patchChat(chatId) { $0.folder = folder.path }
        rebuildLists()
        currentFolderPath = folder.path
        openFolders.insert(folder.id)
    }

    // MARK: - Options

    private var optionChatId: Any { selectedChatId.map { $0 as Any } ?? NSNull() }

    /// Sends option changes in order; the reply's options (if any) are applied.
    private func sendOptions(_ changes: [(String, String)], chat: Any, target: RemoteDevice?, onError: ((Error) -> Void)? = nil) {
        guard !changes.isEmpty else { return }
        optionChangesPending += 1
        Task {
            defer { optionChangesPending -= 1 }
            for (option, value) in changes {
                do {
                    let r = try await call("chat.set_option", ["chatId": chat, "option": option, "value": value], on: target)
                    if optionChangesPending == 1, let o = r["options"] as? [String: Any] { adoptChatOptions(o) }
                } catch {
                    onError?(error)
                }
            }
        }
    }

    func selectBaseModel(_ model: AgyBaseModel) {
        Haptics.select()
        selectedModelId = model.id
        if model.efforts.isEmpty { selectedEffortValue = nil }
        else if !model.efforts.contains(where: { $0.value == selectedEffortValue }) {
            selectedEffortValue = model.efforts.first { $0.value == "medium" }?.value ?? model.efforts[0].value
        }
        var changes = [("model", model.id)]
        if let effort = selectedEffortValue { changes.append(("effort", effort)) }
        sendOptions(changes, chat: optionChatId, target: targetDevice)
    }

    func selectEffort(_ effort: AgyEffortOption) {
        Haptics.select()
        selectedEffortValue = effort.value
        sendOptions([("effort", effort.value)], chat: optionChatId, target: targetDevice)
    }

    func selectMode(_ mode: AgyMode) {
        Haptics.select()
        withAnimation { selectedModeId = mode.id }
        let label = mode.name
        let report: (Error) -> Void = { [weak self] problem in
            let why = problem.localizedDescription
            self?.showToast(String(localized: "Couldn't switch to \(label): \(why)"))
        }
        sendOptions([("mode", mode.id)], chat: optionChatId, target: targetDevice, onError: report)
    }

    // MARK: - Plan limits

    /// Re-reads plan limits from the selected computer (it caches them); `force` asks agy again.
    /// Unforced reads happen at most once a minute.
    func refreshQuota(force: Bool) {
        guard !quotaLoading, let d = selectedDevice else { return }
        guard force || Date().timeIntervalSince(lastQuotaAt) >= 60 else { return }
        quotaLoading = true
        lastQuotaAt = Date()
        Task {
            defer { quotaLoading = false }
            guard let r = try? await call("limits.get", ["refresh": force], on: d) else { return }
            let snap = ProtocolV1.limits(r)
            guard !snap.limits.isEmpty else { return }
            if quota?.limits != snap.limits || quota?.credits != snap.credits { quota = snap }
            forgetRefilledDismissals()
        }
    }

    /// Remaining-percent levels at which the low-limit notice shows.
    static let quotaNoticeThresholds: [Double] = [50, 10, 5]

    private static func threshold(of limit: QuotaLimit) -> Double? {
        quotaNoticeThresholds.filter { limit.remaining <= $0 }.min()
    }

    private func dismissedLevel(_ l: QuotaLimit) -> Double? { quotaDismissals[l.id] }

    /// The tightest limit past a level it wasn't dismissed at.
    var quotaNoticeLimit: QuotaLimit? {
        (quota?.limits ?? []).filter { l in
            guard let level = Self.threshold(of: l) else { return false }
            guard let dismissedAt = dismissedLevel(l) else { return true }
            return level < dismissedAt
        }.min { $0.remaining < $1.remaining }
    }

    func dismissQuotaNotice(_ limit: QuotaLimit) {
        guard let level = Self.threshold(of: limit) else { return }
        quotaDismissals[limit.id] = level
        persist(quotaDismissals, forKey: Keys.dismissals)
    }

    /// A limit that refilled past where it was dismissed may warn again later.
    private func forgetRefilledDismissals() {
        guard !quotaDismissals.isEmpty, let limits = quota?.limits else { return }
        var kept = quotaDismissals
        for l in limits {
            if let at = kept[l.id], l.remaining > at { kept[l.id] = nil }
        }
        guard kept != quotaDismissals else { return }
        quotaDismissals = kept
        persist(kept, forKey: Keys.dismissals)
    }

    // MARK: - Composer

    /// Workspace files in the open chat's folder (else the selected folder), on that computer.
    func searchFiles(_ query: String) async -> [WorkspaceFile] {
        let path: String?
        let target: RemoteDevice?
        if let chat = selectedChat, let f = chat.folder { path = f; target = device(chat.host) }
        else { path = currentFolder?.path; target = device(currentFolder?.host) }
        guard let path, let r = try? await call("files.search", ["folder": path, "query": query], on: target) else { return [] }
        return (r["files"] as? [[String: Any]] ?? []).compactMap { f in
            (f["path"] as? String).map { WorkspaceFile(path: $0, size: (f["size"] as? NSNumber)?.intValue ?? 0) }
        }
    }

    func addAttachment(_ attachment: MessageAttachment) {
        if let p = attachment.workspacePath, draftAttachments.contains(where: { $0.workspacePath == p }) { return }
        Haptics.impact()
        withAnimation(.spring(response: 0.27, dampingFraction: 0.86)) { draftAttachments.append(attachment) }
    }

    func addImage(_ image: UIImage, name: String = "photo.jpg") {
        if let a = MessageAttachment.image(image, name: name) { addAttachment(a) }
    }

    func addLocalFile(_ data: Data, name: String) { addAttachment(.localFile(data, name: name)) }

    func addWorkspaceFile(_ file: WorkspaceFile) { addAttachment(.workspaceFile(file)) }

    func removeAttachment(_ attachment: MessageAttachment) {
        Haptics.impact()
        let gone = attachment.id
        withAnimation(.spring(response: 0.24, dampingFraction: 0.88)) {
            draftAttachments.removeAll { item in item.id == gone }
        }
    }

    /// Commands for a leading `/word` in the composer; the first eight for a bare "/".
    var matchingSlashCommands: [SlashCommand] {
        let typed = draft.trimmingCharacters(in: .whitespaces)
        guard typed.hasPrefix("/"), !typed.contains(" ") else { return [] }
        let q = typed.dropFirst().lowercased()
        if q.isEmpty { return Array(commands.prefix(8)) }
        return commands.filter { cmd in
            [cmd.name, cmd.description].contains { $0.lowercased().contains(q) }
        }
    }

    /// Puts `/name ` in front of the draft, replacing a `/prefix` being typed.
    func insertSlashCommand(_ command: SlashCommand) {
        var rest = Substring(draft)
        if rest.hasPrefix("/") {
            rest = rest.drop { !$0.isWhitespace }
            rest = rest.drop { $0 == " " }
        }
        draft = "/" + command.name + " " + rest
        Haptics.select()
    }

    /// An image on the clipboard becomes an attachment; text is appended to the draft.
    func pasteFromClipboard() {
        let board = UIPasteboard.general
        if let image = board.image {
            addImage(image, name: "pasted.jpg")
            showToast(String(localized: "Attached image from clipboard"))
        } else if let text = board.string?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
            draft = draft.isEmpty ? text : draft + "\n" + text
            showToast(String(localized: "Pasted from clipboard"))
        } else {
            showToast(String(localized: "Clipboard is empty"))
        }
    }

    // MARK: - Turns

    var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !draftAttachments.isEmpty
    }

    func send() { submit(draft, attachments: draftAttachments, fromComposer: true) }

    /// Every send goes through here: checks, then a turn on the chat's computer.
    private func submit(_ raw: String, attachments given: [MessageAttachment], fromComposer: Bool) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !(text.isEmpty && given.isEmpty), !isRunning else { return }

        let rejected = given.filter { !$0.isSendable }
        var outgoing = given
        if !rejected.isEmpty {
            let names = rejected.map(\.name).joined(separator: ", ")
            showToast(String(localized: "\(names) can't be sent: only images and text files from the phone are supported."))
            let dropped = Set(rejected.map(\.id))
            outgoing.removeAll { dropped.contains($0.id) }
            if fromComposer { draftAttachments.removeAll { dropped.contains($0.id) } }
            if text.isEmpty && outgoing.isEmpty { return }
        }

        guard let target = targetDevice else { showComputerPicker = true; return }

        if let issue = versionIssue(for: target) {
            Haptics.warning()
            switch issue {
            case .updateApp: showToast(String(localized: "AGY Remote needs an update to work with \(target.name)."))
            case .updateConnector: showToast(String(localized: "Update agy-connector on \(target.name), then restart it."))
            }
            return
        }
        guard !isOpenInCli else {
            Haptics.warning()
            showToast(String(localized: "Close this chat in the agy CLI to continue it here."))
            return
        }
        if selectedChat != nil, selectedChat?.folder == nil {
            folderPickerPending = PendingSend(text: text, attachments: outgoing, fromComposer: fromComposer)
            return
        }

        Haptics.impact()
        if fromComposer {
            draft = ""
            draftAttachments = []
            // The keyboard can commit unconfirmed (marked) text, such as Japanese input, back into
            // the field just after it is cleared; clear it again if what was sent comes back.
            let sent = text
            Task { @MainActor [weak self] in
                for _ in 0..<3 {
                    try? await Task.sleep(for: .milliseconds(60))
                    guard let self else { return }
                    if self.draft == sent || (!sent.isEmpty && sent.hasPrefix(self.draft) && !self.draft.isEmpty) { self.draft = "" }
                }
            }
        }
        var folder = selectedChat?.folder
        if folder == nil, let current = currentFolder, current.host == target.id { folder = current.path }
        startTurn(text: text, attachments: outgoing, chatId: selectedChatId, device: target, folder: folder)
    }

    func continuePending(in folder: WorkspaceFolder) {
        guard let pending = folderPickerPending, let id = selectedChatId else { return }
        folderPickerPending = nil
        Task {
            do {
                try await assignFolder(folder, to: id)
                // Clears the composer only when that's where the message came from.
                submit(pending.text, attachments: pending.attachments, fromComposer: pending.fromComposer)
            } catch {
                showToast(error.localizedDescription)
            }
        }
    }

    /// Retry: sends the chat's last prompt again.
    func rerunLastPrompt() {
        guard let prompt = messages.last(where: { $0.role == .user }) else { return }
        submit(prompt.text, attachments: Self.resendable(prompt.attachments), fromComposer: false)
    }

    func rerun(_ message: ChatMessage) {
        let list = messages
        guard let idx = list.firstIndex(where: { $0.id == message.id }),
              let prompt = list[...idx].last(where: { $0.role == .user }) else { return }
        submit(prompt.text, attachments: Self.resendable(prompt.attachments), fromComposer: false)
    }

    /// Attachments of an earlier prompt that can go out again (those with content or a path).
    private static func resendable(_ list: [MessageAttachment]) -> [MessageAttachment] {
        list.filter { $0.workspacePath != nil || $0.payload != nil || ($0.kind == .image && $0.imageData != nil) }
    }

    /// The JSON for `turn.start` attachments. Phone files that aren't images can't be sent as
    /// files, so text ones are put into the prompt.
    private func wire(_ attachments: [MessageAttachment], text: String) -> ([[String: Any]], String) {
        var items: [[String: Any]] = []
        var prompt = text
        for a in attachments {
            if let path = a.workspacePath ?? (a.kind == .file && a.payload == nil ? a.name : nil) {
                items.append(["kind": "file", "path": path])
            } else if a.kind == .image, let data = a.payload ?? a.imageData {
                let mime = a.mimeType ?? (data.starts(with: [0x89, 0x50, 0x4E, 0x47]) ? "image/png" : "image/jpeg")
                items.append(["kind": "image", "name": a.name, "mime": mime, "data": data.base64EncodedString()])
            } else if let data = a.payload, let body = String(data: data, encoding: .utf8) {
                prompt += "\n\n`\(a.name)`:\n```\n\(body)\n```"
            } else {
                showToast(String(localized: "\(a.name) can't be sent: only images and text files from the phone are supported."))
            }
        }
        return (items, prompt)
    }

    private func startTurn(text: String, attachments: [MessageAttachment], chatId: String?, device: RemoteDevice, folder: String?) {
        let requestId = UUID().uuidString.lowercased()
        let draftKey = "draft-" + requestId
        let key = chatId ?? draftKey
        let thinking = String(localized: "Thinking")
        let reply = ChatMessage(id: UUID().uuidString, role: .agent, text: "", isStreaming: true, activeStatusLabel: thinking)
        withAnimation(.spring(response: 0.32, dampingFraction: 0.9)) {
            let promptId = UUID().uuidString
            var prompt = ChatMessage(id: promptId, role: .user, text: text)
            prompt.attachments = attachments
            messagesByChat[key, default: []] += [prompt, reply]
        }
        turnNoticeByChat[key] = nil
        turns[requestId] = LiveTurn(requestId: requestId, deviceId: device.id, agentMessageId: reply.id, draftKey: draftKey,
                                    chatId: chatId, folder: folder, prompt: text, status: thinking)
        if let chatId {
            patchChat(chatId) { $0.state = .running }
        } else {
            viewingDraftKey = draftKey
        }
        gradientFlow.setZeroStateActive(false, triggerPulse: false)
        gradientFlow.glow = .working
        guard let client else { return }

        let (items, prompt) = wire(attachments, text: text)
        let params: [String: Any] = [
            "chatId": chatId.map { $0 as Any } ?? NSNull(),
            "folder": chatId == nil ? (folder.map { $0 as Any } ?? NSNull()) : NSNull(),
            "text": prompt,
            "attachments": items,
            "options": turnOptions,
        ]
        let body = P2PClient.encode(params)
        Task { [weak self] in
            let code = await client.streamTurn(ticket: device.ticket, requestId: requestId, body: body) { ev in
                self?.handle(event: ev, requestId: requestId)
            }
            self?.streamEnded(requestId, code: code)
        }
    }

    /// Options sent with a turn; unset ones are left out.
    private var turnOptions: [String: Any] {
        var o: [String: Any] = [:]
        if let m = selectedModelId, !m.isEmpty { o["model"] = m }
        if let e = selectedEffortValue, !e.isEmpty { o["effort"] = e }
        if !selectedModeId.isEmpty { o["mode"] = selectedModeId }
        return o
    }

    /// The stream closed. Before the computer named the chat the turn never started: that fails
    /// visibly. After, without a final event, the turn goes on on the computer; syncs pick it up.
    private func streamEnded(_ requestId: String, code: Int32 = 0) {
        guard let current = turns[requestId] else { return }
        if current.chatId == nil, !current.stopRequested {
            fail(requestId, String(localized: "Couldn't start the reply on the computer."))
            return
        }
        guard let t = turns.removeValue(forKey: requestId) else { return }
        if !t.stopRequested, isViewing(t) { showToast(String(localized: "Reconnecting…")) }
        if let cid = t.chatId { lastRunning[cid] = true }
        mutateAgent(t) { $0.isStreaming = false; $0.activeStatusLabel = "" }
        if let d = device(t.deviceId) { Task { await sync(deviceId: d.id) } }
    }

    private func mutateAgent(_ t: LiveTurn, _ change: (inout ChatMessage) -> Void) {
        guard let i = messagesByChat[t.cacheKey]?.lastIndex(where: { $0.id == t.agentMessageId }) else { return }
        change(&messagesByChat[t.cacheKey]![i])
    }

    private func isViewing(_ t: LiveTurn) -> Bool {
        if let cid = t.chatId { return selectedChatId == cid }
        return selectedChatId == nil && viewingDraftKey == t.draftKey
    }

    private func setStatus(_ label: String, of requestId: String) {
        turns[requestId]?.status = label
        if let t = turns[requestId] { mutateAgent(t) { $0.activeStatusLabel = label } }
    }

    /// Applies one stream event to its own turn's chat, wherever the user is looking.
    private func handle(event ev: [String: Any], requestId: String) {
        guard let t = turns[requestId] else { return }
        if ev["ok"] as? Bool == false {
            let err = ev["error"] as? [String: Any] ?? [:]
            fail(requestId, P2PError.remote(code: err["code"] as? String ?? "internal", message: err["message"] as? String ?? "").localizedDescription)
            return
        }
        switch ev["type"] as? String {
        case "connected":
            if let p2p = ev["_p2p"] as? [String: Any], let i = devices.firstIndex(where: { $0.id == t.deviceId }) {
                var d = devices[i]
                d.status = .connected
                d.latencyMs = (p2p["rtt_ms"] as? NSNumber)?.intValue ?? d.latencyMs
                d.transport = p2p["transport"] as? String ?? d.transport
                if d != devices[i] { devices[i] = d }
            }
            return
        case "error":
            // Before the computer named the chat, the turn never started.
            if t.chatId == nil { fail(requestId, ev["message"] as? String ?? String(localized: "Couldn't reach the computer.")) }
            return
        default: break
        }
        let event = ev["event"] as? String
        if t.stopRequested, event != "turn.chat", event != "turn.finished", event != "turn.stopped", event != "turn.failed" { return }
        switch event {
        case "turn.chat":
            guard let cid = ev["chatId"] as? String else { break }
            bindChat(cid, folder: (ev["folder"] as? String).map(WorkspaceFolder.normalize), to: requestId)
        case "turn.thinking":
            gradientFlow.stir()
            setStatus(String(localized: "Thinking"), of: requestId)
            mutateAgent(t) { $0.thoughtSummary += ev["text"] as? String ?? "" }
        case "turn.text":
            if isViewing(t) { gradientFlow.glow = .answering }
            setStatus(String(localized: "Responding"), of: requestId)
            mutateAgent(t) { $0.text += ev["text"] as? String ?? "" }
        case "turn.step":
            guard let s = ev["step"] as? [String: Any], let step = ProtocolV1.step(s) else { break }
            gradientFlow.stir()
            mutateAgent(t) { m in
                if let i = m.toolCalls.firstIndex(where: { $0.id == step.id }) {
                    if s["title"] != nil, !step.title.isEmpty { m.toolCalls[i].title = step.title }
                    if s["kind"] != nil { m.toolCalls[i].kind = step.kind; m.toolCalls[i].name = step.name }
                    if s["status"] != nil { m.toolCalls[i].status = step.status }
                    if let d = step.detail { m.toolCalls[i].detail = d }
                    if let v = step.path { m.toolCalls[i].path = v }
                    if let v = step.added { m.toolCalls[i].added = v }
                    if let v = step.removed { m.toolCalls[i].removed = v }
                } else {
                    var fresh = step
                    if fresh.at == nil { fresh.at = m.text.utf16.count }
                    m.toolCalls.append(fresh)
                }
            }
            if let m = messagesByChat[t.cacheKey]?.last(where: { $0.id == t.agentMessageId }) {
                setStatus(ProtocolV1.liveStatus(of: m), of: requestId)
            }
        case "turn.plan":
            mutateAgent(t) { $0.plan = ProtocolV1.plan(ev["plan"]) }
        case "turn.context":
            if let used = (ev["used"] as? NSNumber)?.intValue, let size = (ev["size"] as? NSNumber)?.intValue {
                usageByChat[t.cacheKey] = SessionUsage(used: used, size: size)
            }
        case "turn.title":
            if let title = ev["title"] as? String, !title.isEmpty, let cid = t.chatId { patchChat(cid) { $0.title = title } }
        case "turn.approval":
            guard let a = ev["approval"] as? [String: Any], let req = ProtocolV1.approval(a) else { break }
            Haptics.warning()
            gradientFlow.stir()
            mutateAgent(t) { m in
                if let i = m.permissions.firstIndex(where: { $0.id == req.id }) { m.permissions[i] = req } else { m.permissions.append(req) }
            }
            setStatus(req.isQuestion ? String(localized: "Waiting for your answer") : String(localized: "Waiting for approval"), of: requestId)
            case "turn.notice":
            guard isViewing(t) else { break }
            if ev["code"] as? String == "cli_touched" {
                showToast(String(localized: "This chat was also opened in the agy CLI while this reply was running. agy keeps only one side's changes, so some messages may be missing."))
            } else if let text = ev["message"] as? String ?? ev["code"] as? String, !text.isEmpty {
                showToast(text)
            }
        case "turn.finished":
            mutateAgent(t) { $0.durationSeconds = (ev["seconds"] as? NSNumber)?.doubleValue }
            finish(requestId)
        case "turn.stopped":
            finish(requestId)
        case "turn.failed":
            let err = ev["error"] as? [String: Any] ?? [:]
            fail(requestId, P2PError.remote(code: err["code"] as? String ?? "internal", message: err["message"] as? String ?? "").localizedDescription)
        default: break
        }
    }

    /// The computer named the chat a turn runs in: file the messages under it and list it.
    private func bindChat(_ cid: String, folder: String?, to requestId: String) {
        guard var t = turns[requestId] else { return }
        if t.chatId == nil {
            let moved = messagesByChat.removeValue(forKey: t.draftKey) ?? []
            messagesByChat[cid] = (messagesByChat[cid] ?? []) + moved
            if let u = usageByChat.removeValue(forKey: t.draftKey) { usageByChat[cid] = u }
            if let n = turnNoticeByChat.removeValue(forKey: t.draftKey) { turnNoticeByChat[cid] = n }
        }
        t.chatId = cid
        t.folder = folder ?? t.folder
        turns[requestId] = t
        var list = chatsByHost[t.deviceId] ?? []
        if let i = list.firstIndex(where: { $0.id == cid }) {
            list[i].state = .running
            list[i].updated = .now
        } else {
            // The whole prompt; the drawer truncates it.
            let title = t.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
            list.insert(ChatSummary(id: cid, title: title, folder: t.folder, updated: .now,
                                    state: .running, host: t.deviceId), at: 0)
        }
        chatsByHost[t.deviceId] = list
        rebuildLists()
        if let f = t.folder { openFolders.insert(t.deviceId + "|" + f) }
        if selectedChatId == nil, viewingDraftKey == t.draftKey {
            selectedChatId = cid
            viewingDraftKey = nil
            AppDelegate.visibleSessionId = cid
        }
        if t.stopRequested, let d = device(t.deviceId) {
            Task { _ = try? await call("turn.stop", ["chatId": cid], on: d) }
        }
    }

    private func fail(_ requestId: String, _ message: String) {
        if let t = turns[requestId] {
            mutateAgent(t) { $0.text = $0.text.isEmpty ? message : $0.text + "\n\n" + message }
            turnNoticeByChat[t.cacheKey] = message
        }
        showToast(message)
        finish(requestId)
    }

    private func finish(_ requestId: String) {
        guard let t = turns.removeValue(forKey: requestId) else { return }
        // The status line gives way to "Worked for …" with a cross-fade, not a jump.
        withAnimation(.smooth(duration: 0.35)) { mutateAgent(t) { $0.isStreaming = false; $0.activeStatusLabel = "" } }
        if let cid = t.chatId {
            patchChat(cid) { if $0.state == .running { $0.state = .idle } }
            lastRunning[cid] = false
        }
        if isViewing(t) { gradientFlow.glow = .idle }
        refreshQuota(force: true)
        let id = t.deviceId
        Task { await sync(deviceId: id) }
    }

    /// Stops the turn on screen. Works before the computer named the chat (the stop is sent as
    /// soon as it does), and for turns started elsewhere.
    func stop() {
        let local = selectedChatId.flatMap(turn(forChat:)) ?? viewingDraftTurn
        if let t = local {
            turns[t.requestId]?.stopRequested = true
            mutateAgent(t) { $0.isStreaming = false; $0.activeStatusLabel = "" }
            if let cid = t.chatId {
                patchChat(cid) { $0.state = .idle }
                let d = device(t.deviceId)
                Task { _ = try? await call("turn.stop", ["chatId": cid], on: d) }
            }
        } else if let id = selectedChatId, chats.first(where: { $0.id == id })?.state == .running {
            let d = device(forChat: id)
            patchChat(id) { $0.state = .idle }
            if let i = messagesByChat[id]?.indices.last, messagesByChat[id]![i].isStreaming {
                messagesByChat[id]![i].isStreaming = false
                messagesByChat[id]![i].activeStatusLabel = ""
            }
            Task {
                _ = try? await call("turn.stop", ["chatId": id], on: d)
                try? await Task.sleep(for: .milliseconds(450))
                if let d { await sync(deviceId: d.id) }
            }
        } else {
            return
        }
        gradientFlow.glow = .idle
    }

    func answer(_ request: PendingPermissionRequest, option: String) {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        var key: String?
        for (k, list) in messagesByChat {
            for i in list.indices {
                guard let j = list[i].permissions.firstIndex(where: { $0.id == request.id }) else { continue }
                key = k
                withAnimation(.smooth) {
                    messagesByChat[k]![i].permissions[j].selectedOptionId = option
                    if messagesByChat[k]![i].isStreaming {
                        messagesByChat[k]![i].activeStatusLabel = ProtocolV1.liveStatus(of: messagesByChat[k]![i])
                    }
                }
            }
        }
        gradientFlow.stir()
        let owner = turns.values.first { $0.cacheKey == key }
        if let owner, let m = messagesByChat[owner.cacheKey]?.last(where: { $0.id == owner.agentMessageId }) {
            turns[owner.requestId]?.status = ProtocolV1.liveStatus(of: m)
        }
        let target = device(owner?.deviceId) ?? key.flatMap { device(forChat: $0) } ?? targetDevice
        Task {
            do { _ = try await call("approval.answer", ["approvalId": request.id, "choice": option], on: target) }
            catch let e as P2PError where e.code == "not_found" { markApprovalStale(request.id) }
            catch {}
        }
    }

    /// Answers an `ask_question` form (`answers` nil: skipped). The card then shows it as done.
    func answerQuestion(_ request: PendingPermissionRequest, answers: [String: Any]?) {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        var key: String?
        for (k, list) in messagesByChat {
            for i in list.indices {
                guard let j = list[i].permissions.firstIndex(where: { $0.id == request.id }) else { continue }
                key = k
                // Already set: a second tap must not answer twice.
                if messagesByChat[k]![i].permissions[j].selectedOptionId != nil { return }
                withAnimation(.smooth) { messagesByChat[k]![i].permissions[j].selectedOptionId = answers == nil ? "skipped" : "answered" }
            }
        }
        gradientFlow.stir()
        let owner = turns.values.first { $0.cacheKey == key }
        let target = device(owner?.deviceId) ?? key.flatMap { device(forChat: $0) } ?? targetDevice
        var params: [String: Any] = ["approvalId": request.id]
        if let answers { params["answers"] = answers } else { params["skip"] = true }
        Task {
            do { _ = try await call("question.answer", params, on: target) }
            catch let e as P2PError where e.code == "not_found" { markApprovalStale(request.id) }
            catch {}
        }
    }

    /// The computer no longer knows the question: the card shows it as gone.
    private func markApprovalStale(_ approvalId: String) {
        for (k, list) in messagesByChat {
            for i in list.indices {
                guard let j = list[i].permissions.firstIndex(where: { $0.id == approvalId }) else { continue }
                messagesByChat[k]![i].permissions[j].isStale = true
            }
        }
    }

    // MARK: - Toasts & banners

    func showToast(_ text: String) {
        withAnimation(.melt) { toast = text }
        let visible = min(8, max(2.1, Double(text.count) / 15))
        Task {
            try? await Task.sleep(for: .seconds(visible))
            if toast == text { withAnimation(.melt) { toast = nil } }
        }
    }

    private func announceAvailableAgain(_ title: String) {
        Haptics.success()
        withAnimation(.melt) { availableAgainChatTitle = title }
        Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard availableAgainChatTitle == title else { return }
            withAnimation(.melt) { availableAgainChatTitle = nil }
        }
    }

    // MARK: - Appearance

    func applyAppearanceToWindows() {
        let style: UIUserInterfaceStyle = switch theme {
        case .system: .unspecified
        case .dark: .dark
        case .light: .light
        }
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        for window in windows {
            window.overrideUserInterfaceStyle = style
            // Sheets already on screen keep the scheme they were shown with
            // unless their controllers are told as well.
            var shown = window.rootViewController?.presentedViewController
            while let sheet = shown {
                sheet.overrideUserInterfaceStyle = style
                shown = sheet.presentedViewController
            }
        }
    }

    // MARK: - Notifications

    /// The switch in Settings / setup: turning on goes through the explainer first.
    func requestNotifications(_ on: Bool) {
        if on {
            if !notificationsEnabled { showNotificationExplainer = true }
        } else {
            notificationsEnabled = false
            let tickets = devices.map(\.ticket)
            pushSignatures = [:]
            if let client {
                Task { for t in tickets { _ = try? await client.request(ticket: t, method: "notifications.unregister") } }
            }
            UIApplication.shared.unregisterForRemoteNotifications()
        }
    }

    /// "Turn On Notifications" in the explainer.
    func confirmNotifications() async {
        showNotificationExplainer = false
        var granted = false
        do {
            granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.badge, .alert, .sound])
        } catch {
            granted = false
        }
        guard granted else {
            showToast(String(localized: "Allow notifications for AGY Remote in Settings to turn this on."))
            return
        }
        notificationsEnabled = true
        UIApplication.shared.registerForRemoteNotifications()
    }

    func setNotificationKind(_ kind: NotificationKind, _ on: Bool) {
        if on { notificationKinds.insert(kind) } else { notificationKinds.remove(kind) }
        registerPushEverywhere()
    }

    private func installPushHooks() {
        AppDelegate.onDeviceToken = { [weak self] token in
            let hex = token.reduce(into: "") { out, byte in out += String(format: "%02x", byte) }
            // Registered again on every launch, even with the same token: a computer may have
            // dropped it meanwhile (a reinstall, a dead-token answer), and nothing else re-sends it.
            guard let me = self else { return }
            me.pushToken = hex
            me.registerPushEverywhere()
        }
        AppDelegate.onOpenSession = { [weak self] target in
            if let link = URL(string: "agy-rem://session/\(target)") { self?.handleIncomingURL(link) }
        }
        AppDelegate.onPermissionAction = { [weak self] question, yes, computer in
            guard let me = self else { return }
            await me.answerFromNotification(question, allow: yes, computer: computer)
        }
        AppDelegate.onGrant = { [weak self] info in self?.receiveGrant(info) }
        AppDelegate.onRegistrationError = { [weak self] failure in
            guard let me = self else { return }
            me.showToast(String(localized: "Couldn't turn on notifications: \(failure.localizedDescription)"))
        }
        if notificationsEnabled { UIApplication.shared.registerForRemoteNotifications() }
    }

    /// Answers only the computer whose key opened the notification; no other computer sees the
    /// approval id or can swallow the answer.
    private func answerFromNotification(_ approvalId: String, allow: Bool, computer: String) async {
        guard let client, let target = devices.first(where: { $0.id == computer }) else { return }
        let body = P2PClient.encode(["approvalId": approvalId, "choice": allow ? "allow" : "deny"])
        _ = try? await client.request(ticket: target.ticket, method: "approval.answer", body: body)
    }

    private func registerPushEverywhere() {
        pushSignatures = [:]
        pushRetryAfter = [:]
        for id in devices.map(\.id) { Task { await registerPush(on: id, force: true) } }
    }

    /// Asks the computer's relay for a grant when there is none, or it runs out soon; not more
    /// often than every ten minutes per computer. Computers registering at about the same time
    /// (all of them, when notifications are turned on) go into one request per relay, so a phone
    /// with many computers asks once. The relay answers with a background push (`receiveGrant`).
    private func askForGrant(on id: String, token: String) {
        guard notificationsEnabled, let relay = pushRelays[id], UIApplication.shared.applicationState == .active else { return }
        let soon = Date().addingTimeInterval(PushGrants.renewBefore)
        if PushGrants.grant(for: id, token: token, relay: relay.relay, now: soon) != nil { return }
        if let asked = grantAskedAt[id], Date().timeIntervalSince(asked) < 600 { return }
        // A relay without grants is asked again only a day later: the phone doesn't send its
        // push token there for nothing.
        if let until = grantlessRelays[relay.relay], Date() < until { return }
        grantWanted.insert(id)
        guard grantFlush == nil else { return }
        grantFlush = Task {
            try? await Task.sleep(for: .seconds(1))
            grantFlush = nil
            await flushGrantRequests()
        }
    }

    private func flushGrantRequests() async {
        let wanted = grantWanted
        grantWanted = []
        guard notificationsEnabled, let token = pushToken else { return }
        var byRelay: [String: [(id: String, key: String)]] = [:]
        for id in wanted.sorted() {
            if let r = pushRelays[id] { byRelay[r.relay, default: []].append((id, r.key)) }
        }
        for (relay, list) in byRelay {
            let now = Date()
            for item in list { grantAskedAt[item.id] = now }
            var keys: [String] = []
            for item in list where !keys.contains(item.key) { keys.append(item.key) }
            for start in stride(from: 0, to: keys.count, by: PushGrants.maxKeysPerRequest) {
                let part = Array(keys[start..<min(start + PushGrants.maxKeysPerRequest, keys.count)])
                let status = await PushGrants.ask(relay: relay, token: token, environment: PushEnvironment.name, connectorKeys: part)
                if status == 404 { grantlessRelays[relay] = Date().addingTimeInterval(24 * 60 * 60); break }
            }
        }
    }

    /// Grants arrived from a relay. Each is kept only if this app asked for that computer in the
    /// last ten minutes; anything else (someone else asking in this phone's name) is dropped.
    private func receiveGrant(_ info: [AnyHashable: Any]) {
        guard let g = PushGrants.parse(info), let token = pushToken, Double(g.expires) > Date().timeIntervalSince1970 else { return }
        for (key, value) in g.grants {
            for (id, relay) in pushRelays where relay.key == key {
                guard let asked = grantAskedAt[id], Date().timeIntervalSince(asked) < 600 else { continue }
                PushGrants.set(.init(value: value, expires: g.expires, token: token, relay: relay.relay), for: id)
                Task { await registerPush(on: id, force: true) }
            }
        }
    }

    /// Sends the token, the end-to-end key and the wanted kinds to one computer. Repeated syncs
    /// skip computers that already have the current set.
    private func registerPush(on id: String, force: Bool) async {
        guard notificationsEnabled, let token = pushToken, let d = device(id), !d.isDemo else { return }
        let key: String
        // Its own key: no computer can make notifications that look like another's.
        key = PushKeyStore.key(for: id).hexString
        let kinds = notificationKinds.map(\.rawValue).sorted()
        // The relay's grant for this computer and token, once the phone has one.
        let grant = pushRelays[id].flatMap { PushGrants.grant(for: id, token: token, relay: $0.relay) }
        let signature = ([token, key, grant?.value ?? ""] + kinds).joined(separator: ",")
        // Before the skip below: the relay's address may have arrived only after the last registration.
        askForGrant(on: id, token: token)
        let now = Date()
        // Same set: again only every ten minutes (the computer may have restarted and lost it).
        let fresh = now.timeIntervalSince(pushRegisteredAt[id] ?? .distantPast) < 600
        guard force || pushSignatures[id] != signature || !fresh else { return }
        if !force, let (until, _) = pushRetryAfter[id], now < until { return }
        pushSignatures[id] = signature
        pushRegisteredAt[id] = now
        do {
            var params: [String: Any] = ["token": token, "environment": PushEnvironment.name, "key": key, "kinds": kinds]
            if let grant {
                params["grant"] = grant.value
                params["grantExp"] = grant.expires
            }
            let reply = try await call("notifications.register", params, on: d)
            pushRetryAfter[id] = nil
            pushFailureToasted = false
            // The relay stopped honouring this grant (the computer found out when it sent one).
            if reply["grantRefused"] as? Bool == true {
                PushGrants.remove(computer: id)
                askForGrant(on: id, token: token)
            }
            // Turned off while this was on its way: take it back.
            if !notificationsEnabled {
                pushSignatures[id] = nil
                _ = try? await call("notifications.unregister", on: d)
            }
        } catch {
            pushSignatures[id] = nil
            pushRegisteredAt[id] = nil
            let n = (pushRetryAfter[id]?.1 ?? 0) + 1
            pushRetryAfter[id] = (now.addingTimeInterval(min(600, 15 * pow(2, Double(n - 1)))), n)
            if !pushFailureToasted {
                pushFailureToasted = true
                showToast(String(localized: "\(d.name): notifications unavailable (\(error.localizedDescription))"))
            }
        }
    }

    // MARK: - Reset

    /// Back to first launch: unpairs everything, cancels turns, clears cached state and every
    /// preference. The phone's P2P identity is kept so pairing again works right away.
    func resetApp() {
        let tickets = devices.map(\.ticket)
        if let client {
            Task { for t in tickets { _ = try? await client.request(ticket: t, method: "notifications.unregister") } }
            for t in turns.values { if let d = device(t.deviceId) { client.detach(ticket: d.ticket, requestId: t.requestId) } }
        }
        connectLoop?.cancel()
        connectLoop = nil
        turns = [:]
        viewingDraftKey = nil
        PushKeyStore.removeAll()
        PushGrants.removeAll()
        pushSignatures = [:]; pushRegisteredAt = [:]; pushRetryAfter = [:]; pushFailureToasted = false
        for t in syncTasks.values { t.cancel() }
        syncTasks = [:]; lastSyncAt = [:]; lastInfoAt = [:]; lastQuotaAt = .distantPast
        failuresByDevice = [:]; errorByDevice = [:]
        openSeq = [:]; openInFlight = []; optionsAdopted = []
        turnNoticeByChat = [:]
        chatsByFolderKey = [:]; unfiledChats = []; runningChatIds = []
        pendingLinkChatId = nil
        folderPickerPending = nil
        isLoadingChat = false
        toast = nil; availableAgainChatTitle = nil
        sidebarQuery = ""; sidebarSearchActive = false; globalSearchQuery = ""; sidebarDragOffset = 0
        showModelPicker = false; showUsage = false; showAddSheet = false; showSearch = false
        showNotificationExplainer = false; showWorkspaceFilePicker = false; showCommandPicker = false
        showRelayConsent = false
        forceLanding = false
        quotaLoading = false

        devices = []
        selectedDeviceId = nil
        chatsByHost = [:]; foldersByHost = [:]; protocolByHost = [:]
        chats = []; folders = []; baseModels = []; modes = []; commands = []
        messagesByChat = [:]; usageByChat = [:]; unreadableByChat = [:]
        loadedUpdated = [:]; lastRunning = [:]; lastCliReload = [:]; lastOpenInCli = []
        selectedChatId = nil
        AppDelegate.visibleSessionId = nil
        quota = nil
        quotaDismissals = [:]
        versionIssue = nil; connectorUpdateNote = nil; lastP2PError = nil; pairingError = nil
        draft = ""; draftAttachments = []
        selectedModelId = nil; selectedEffortValue = nil; selectedModeId = "default"
        currentFolderPath = nil
        openFolders = []; collapsedFolders = []; groupByFolder = true
        folderSort = .recent; projectsCollapsed = false
        autoExpandFolders = true; autoExpanded = []
        optionsPushed = []
        notificationsEnabled = false
        theme = .system
        thinkingStyle = .random
        relayAllowed = false
        relayAsked = false; relayDeclined = false; consecutiveFailures = 0
        onboardingComplete = false
        showSettings = false; showComputerPicker = false; sidebarOpen = false

        let defaults = UserDefaults.standard
        Self.ticketsItem.delete()
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("agy.") {
            defaults.removeObject(forKey: key)
        }
        gradientFlow.glow = .idle
        gradientFlow.setZeroStateActive(true, triggerPulse: true)
    }
}
