import SwiftUI

// MARK: - Folder presentation

private let kindTable: [String: (glyph: String, label: String)] = [
    "swift": ("swift", "Swift"),
    "rust": ("gearshape.2.fill", "Rust"),
    "node": ("cube.box.fill", "Node"),
    "python": ("terminal.fill", "Python"),
    "go": ("square.stack.3d.up.fill", "Go"),
]

extension WorkspaceFolder {
    private var kindEntry: (glyph: String, label: String)? { kind.flatMap { kindTable[$0.lowercased()] } }
    var glyph: String { kindEntry?.glyph ?? "folder.fill" }
    var stackLabel: String? { kindEntry?.label }
}

// MARK: - Row style

/// Pill rows of the drawer: filled when highlighted, a faint wash and a tiny shrink while pressed.
struct DrawerRowStyle: ButtonStyle {
    var highlighted: Bool = false

    func makeBody(configuration: Configuration) -> some View {
        let down = configuration.isPressed
        let wash = highlighted ? AppColor.sidebarActivePill : AppColor.onSurface.opacity(down ? 0.06 : 0)
        return configuration.label
            .padding(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
            .frame(height: 46)
            .background(Capsule().fill(wash))
            .contentShape(.capsule)
            .scaleEffect(down ? 0.985 : 1)
            .animation(.listPress, value: down)
    }
}

/// The reveal tick the drawer last acted on; survives the drawer being rebuilt.
@MainActor private var lastRevealTick = 0

private let drawerSpring = Animation.spring(response: 0.26, dampingFraction: 0.88)

// MARK: - Drawer

struct SidebarView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @Namespace private var space
    @FocusState private var searchFocus: Bool
    @Environment(\.sidebarPinned) private var pinned
    @State private var barBottom: CGFloat = 0
    @State private var footTop: CGFloat = 0

    private var needle: String { viewModel.sidebarQuery.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        ScrollViewReader { reader in
            scroller
                .task(id: viewModel.sidebarRevealTick) { await revealSelected(reader) }
        }
        .background(AppColor.surface.ignoresSafeArea())
        .onAppear { searchFocus = viewModel.sidebarSearchActive }
        .onChange(of: viewModel.sidebarSearchActive) { searchFocus = viewModel.sidebarSearchActive }
    }

    private var scroller: some View {
        let live = LiveIndex(viewModel)
        return ScrollView {
            VStack(alignment: .leading, spacing: 20) { sections(live) }
                .padding(EdgeInsets(top: 8, leading: 12, bottom: 20, trailing: 12))
        }
        .scrollEdgeEffectStyle(.soft, for: .vertical)
        .scrollDismissesKeyboard(.interactively)
        .scrollIndicators(.hidden)
        .barEdgeFade(topEdge: barBottom, bottomEdge: footTop > 0 ? footTop : nil)
        .safeAreaBar(edge: .top, spacing: 0) { topBar.reportGlobalEdge(.bottom, into: $barBottom) }
        .safeAreaBar(edge: .bottom, spacing: 0) { bottomBar.reportGlobalEdge(.top, into: $footTop) }
    }

    @ViewBuilder private func sections(_ live: LiveIndex) -> some View {
        let groups = viewModel.sidebarFolders
        let loose = viewModel.sidebarUnfiledChats
        let hits = viewModel.sidebarModels
        if needle.isEmpty {
            DrawerShortcuts(viewModel: viewModel, startChat: begin)
        }
        if !needle.isEmpty && groups.isEmpty && loose.isEmpty && hits.isEmpty {
            EmptySearchNote()
        } else {
            listing(groups: groups, loose: loose, live: live)
            if !needle.isEmpty && !hits.isEmpty { modelHits(hits) }
        }
    }

    // MARK: Top bar

    private var topBar: some View {
        let searching = viewModel.sidebarSearchActive
        return GlassEffectContainer(spacing: 10) {
            HStack(spacing: 10) {
                if searching {
                    queryField
                } else {
                    Text(verbatim: "AGY Remote")
                        .lineLimit(1)
                        .agyText(22, .regular, AppColor.onSurface)
                        .transition(AnyTransition.opacity)
                        .padding(.leading, 8)
                    Spacer()
                    barDisc("magnifyingglass", 17, morph: "drawer.search", spoken: Text("Search")) {
                        withAnimation(drawerSpring) { viewModel.sidebarSearchActive = true }
                    }
                }
                if searching || !pinned {
                    barDisc("xmark", 16.5, morph: "drawer.close",
                            spoken: searching ? Text("Close search") : Text("Close"), tap: closeTapped)
                }
            }
        }
        .padding(EdgeInsets(top: 8, leading: 14, bottom: 8, trailing: 14))
    }

    private func closeTapped() {
        guard viewModel.sidebarSearchActive else { return viewModel.hideSidebar() }
        withAnimation(drawerSpring) {
            viewModel.sidebarQuery = ""
            viewModel.sidebarSearchActive = false
        }
    }

    private func barDisc(_ symbol: String, _ pt: CGFloat, morph: String, spoken: Text, tap: @escaping () -> Void) -> some View {
        let edge = AppMetrics.sidebarHeaderButtonSize
        return Button(action: tap) {
            Image(systemName: symbol).agyText(pt, .regular, AppColor.onSurface).agyBox(edge, edge, hit: Circle())
        }
        .accessibilityLabel(spoken)
        .buttonStyle(.plain)
        .agyGlass(Circle(), morph: morph, in: space)
    }

    private var queryField: some View {
        let lens = Image(systemName: "magnifyingglass").agyText(16, .regular, AppColor.onSurfaceVariant)
        return HStack(spacing: 8) {
            lens
            TextField("Search", text: $viewModel.sidebarQuery)
                .focused($searchFocus)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .agyText(16.5, .regular, AppColor.onSurface)
            if !viewModel.sidebarQuery.isEmpty {
                Button(action: { viewModel.sidebarQuery = "" }) {
                    Image(systemName: "xmark.circle.fill").agyText(16, .regular, AppColor.onSurfaceMuted)
                }
                .accessibilityLabel(Text("Clear search"))
                .buttonStyle(.plain)
            }
        }
        .padding(EdgeInsets(top: 0, leading: 14, bottom: 0, trailing: 14))
        .frame(height: 44)
        .agyGlass(Capsule(), morph: "drawer.search", in: space)
    }

    // MARK: Listing

    private func listing(groups: [WorkspaceFolder], loose: [ChatSummary], live: LiveIndex) -> some View {
        let grouped = viewModel.groupByFolder
        // Searching shows every match, folded or not.
        let folded = grouped && viewModel.projectsCollapsed && needle.isEmpty
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 2) {
                Text(grouped ? "Projects" : "All").agyText(14.5, .regular, AppColor.onSurfaceVariant)
                Spacer()
                sortMenu
                if grouped {
                    Button(action: { withAnimation(drawerSpring) { viewModel.projectsCollapsed.toggle() } }) {
                        Image(systemName: "chevron.right")
                            .agyText(12.5, .medium, AppColor.onSurfaceVariant)
                            .rotationEffect(folded ? .zero : .degrees(90))
                            .agyBox(34, 34)
                    }
                    .accessibilityLabel(Text("Projects"))
                    .accessibilityValue(folded ? Text("Collapsed") : Text("Expanded"))
                    .buttonStyle(.plain)
                }
            }
            .padding(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 8))

            if viewModel.folders.isEmpty && viewModel.chats.isEmpty {
                if viewModel.isRefreshingP2P { connectingNote }
            } else if !grouped && needle.isEmpty {
                VStack(spacing: 2) {
                    ForEach(viewModel.sidebarFlatChats) { c in row(c, live: live, indent: false, badge: badge(for: c)) }
                }
            } else {
                if !folded {
                    VStack(spacing: 8) {
                        ForEach(groups, id: \.hostPathKey) { f in folderSection(f, live: live) }
                    }
                }
                if !loose.isEmpty {
                    Text("Sessions without a project")
                        .agyText(14.5, .regular, AppColor.onSurfaceVariant)
                        .padding(EdgeInsets(top: 10, leading: 16, bottom: 0, trailing: 16))
                    VStack(spacing: 2) {
                        ForEach(loose) { c in row(c, live: live, indent: false, badge: nil) }
                    }
                }
            }
        }
    }

    /// Order of the projects (recent / name), and the switch to one flat list of every chat.
    private var sortMenu: some View {
        Menu {
            Picker(selection: Binding(get: { viewModel.folderSort },
                                      set: { s in withAnimation(drawerSpring) { viewModel.folderSort = s } })) {
                Text("Recently used").tag(FolderSort.recent)
                Text("Name").tag(FolderSort.name)
            } label: { Text("Sort projects") }
            Divider()
            Toggle(isOn: Binding(get: { !viewModel.groupByFolder },
                                 set: { flat in withAnimation(.easeInOut(duration: 0.2)) { viewModel.groupByFolder = !flat } })) {
                Text("Show all chats")
            }
        } label: {
            Image(systemName: "arrow.up.arrow.down")
                .agyText(15, .regular, AppColor.onSurfaceVariant)
                .agyBox(34, 34)
        }
        .accessibilityLabel(Text("Sort projects"))
        .buttonStyle(.plain)
    }

    private var connectingNote: some View {
        let spinner = ProgressView().controlSize(.small)
        return HStack(spacing: 10) {
            spinner
            Text("Connecting to agy…").agyText(14.5, .regular, AppColor.onSurfaceVariant)
        }
        .padding(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
    }

    private func badge(for chat: ChatSummary) -> WorkspaceFolder? {
        let all = viewModel.folders
        if let key = chat.folderKey, let byKey = all.first(where: { $0.hostPathKey == key }) { return byKey }
        return chat.folder.flatMap { p in all.first { $0.path == p } }
    }

    private func row(_ chat: ChatSummary, live: LiveIndex, indent: Bool, badge: WorkspaceFolder?) -> some View {
        DrawerChatRow(chat: chat, indent: indent, badge: badge,
                      running: live.running.contains(chat.id), status: live.status[chat.id] ?? "",
                      selected: chat.id == viewModel.selectedChatId) {
            Task { await viewModel.openChat(chat.id) }
            viewModel.hideSidebar()
        }
    }

    private func folderSection(_ folder: WorkspaceFolder, live: LiveIndex) -> some View {
        let kids = viewModel.chats(in: folder)
        let key = folder.hostPathKey
        let expanded = !needle.isEmpty || viewModel.openFolders.contains(key)
        let isCurrent = viewModel.selectedChatId == nil && viewModel.isEmptyChat
            && viewModel.currentFolderPath != nil && viewModel.currentFolder?.hostPathKey == key
        return VStack(spacing: 2) {
            FolderHeader(folder: folder, hasChats: !kids.isEmpty, expanded: expanded,
                         busy: live.busyFolders.contains(key), current: isCurrent,
                         onMain: { kids.isEmpty ? begin(folder) : flip(folder) },
                         onPlus: { begin(folder) })
            if expanded {
                ForEach(kids) { c in row(c, live: live, indent: true, badge: nil) }
            }
        }
    }

    private func modelHits(_ list: [AgyBaseModel]) -> some View {
        let heading = Text("Models").agyText(14.5, .regular, AppColor.onSurfaceVariant)
        return VStack(alignment: .leading, spacing: 4) {
            heading
                .padding(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
            ForEach(list) { m in
                Button(action: {
                    viewModel.selectBaseModel(m)
                    viewModel.hideSidebar()
                }) {
                    HStack(alignment: .center, spacing: 14) {
                        Image(systemName: "sparkles").frame(width: 24).agyText(17)
                        Text(verbatim: m.name).agyText(16.5)
                        Spacer()
                    }
                    .foregroundStyle(AppColor.onSurface)
                }
                .buttonStyle(DrawerRowStyle(highlighted: viewModel.selectedBaseModel?.id == m.id))
            }
        }
    }

    // MARK: Bottom bar

    private var bottomBar: some View {
        let device = viewModel.selectedDevice
        return GlassEffectContainer(spacing: 12) {
            HStack(spacing: 12) {
                Button(action: { viewModel.showComputerPicker = true }) {
                    HStack(spacing: 12) {
                        ComputerRing(device: device, size: 38, glyphSize: 14, ringInset: 1, offlineOpacity: 1)
                            .agyGlass(Circle())
                        VStack(alignment: .leading, spacing: 1) {
                            Text(verbatim: device?.name ?? "").lineLimit(1).agyText(15, .medium, AppColor.onSurface)
                            if let line = machineLine(device) {
                                Text(verbatim: line).lineLimit(1).agyText(12.5, .regular, AppColor.onSurfaceVariant)
                            }
                        }
                    }
                    .contentShape(.rect)
                }
                .accessibilityLabel(Text("Computer: \(device?.name ?? "")"))
                .buttonStyle(.plain)

                Spacer()

                Button(action: { viewModel.showSettings = true }) {
                    Image(systemName: "gearshape").agyText(19, .regular, AppColor.onSurface).agyBox(40, 40, hit: Circle())
                }
                .accessibilityLabel(Text("Settings"))
                .buttonStyle(.plain)
                .agyGlass(Circle())
            }
        }
        .padding(EdgeInsets(top: 10, leading: 20, bottom: 12, trailing: 20))
    }

    private func machineLine(_ d: RemoteDevice?) -> String? {
        guard let d else { return nil }
        if d.status != .connected { return d.status.rawValue }
        let head = d.agentVersion.isEmpty ? d.status.rawValue : "agy " + d.agentVersion
        return head + " · " + String(d.latencyMs) + "ms"
    }

    // MARK: Actions

    private func flip(_ folder: WorkspaceFolder) {
        Haptics.select()
        let key = folder.hostPathKey
        let wasOpen = viewModel.openFolders.contains(key)
        withAnimation(drawerSpring) {
            if wasOpen {
                viewModel.openFolders.remove(key)
            } else {
                viewModel.openFolders.insert(key)
            }
        }
    }

    private func begin(_ folder: WorkspaceFolder?) {
        if let key = folder?.hostPathKey { viewModel.openFolders.insert(key) }
        viewModel.startNewChat(in: folder)
        viewModel.hideSidebar()
    }

    private func revealSelected(_ reader: ScrollViewProxy) async {
        let tick = viewModel.sidebarRevealTick
        guard lastRevealTick != tick, let target = viewModel.selectedChatId else { return }
        lastRevealTick = tick
        if let c = viewModel.chats.first(where: { $0.id == target }), let key = c.folderKey {
            let known = viewModel.openFolders.contains(key)
            if !known { viewModel.openFolders.insert(key) }
        }
        let wait: Duration = .milliseconds(viewModel.sidebarOpen ? 380 : 120)
        guard (try? await Task.sleep(for: wait)) != nil else { return }
        withAnimation(.easeInOut(duration: 0.25)) { reader.scrollTo("drawer.chat." + target, anchor: .center) }
    }
}

// MARK: - Running lookups

/// Running chats, their status lines and busy folders, worked out once per render.
@MainActor
private struct LiveIndex {
    var running: Set<String> = []
    var status: [String: String] = [:]
    var busyFolders: Set<String> = []

    init(_ vm: RemoteWorkspaceViewModel) {
        for c in vm.chats where vm.chatIsRunning(c) {
            running.insert(c.id)
            status[c.id] = vm.liveStatus(for: c.id)
            if let k = c.folderKey { busyFolders.insert(k) }
        }
    }
}

// MARK: - Pieces

private struct DrawerShortcuts: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    let startChat: (WorkspaceFolder?) -> Void

    var body: some View {
        VStack(spacing: 2) {
            Button(action: { startChat(viewModel.currentFolder) }) {
                HStack(spacing: 14) {
                    ComposeIcon(size: 19).frame(width: 24)
                    Text("New chat").agyText(16.5, .regular, AppColor.onSurface)
                    Spacer()
                    if viewModel.currentFolderPath != nil, let f = viewModel.currentFolder { chosenFolder(f) }
                }
            }
            .buttonStyle(DrawerRowStyle(highlighted: viewModel.isEmptyChat))
            if !viewModel.modes.isEmpty { modes }
            if !viewModel.baseModels.isEmpty { models }
        }
    }

    private func chosenFolder(_ f: WorkspaceFolder) -> some View {
        HStack(spacing: 5) {
            Image(systemName: f.glyph).agyText(11.5, .semibold, AppColor.onSurfaceVariant)
            Text(verbatim: f.name).lineLimit(1).agyText(12.5, .regular, AppColor.onSurfaceMuted)
        }
    }

    private func pickRow(_ caption: String, @ViewBuilder icon: () -> some View) -> some View {
        HStack(spacing: 14) {
            icon().frame(width: 24)
            Text(verbatim: caption).lineLimit(1).agyText(16.5, .regular, AppColor.onSurface)
            Spacer()
        }
        .padding(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
        .frame(height: 46)
        .contentShape(.capsule)
    }

    private var modes: some View {
        let now = viewModel.selectedMode
        let tone = now?.bypassesPermissions == true ? AppColor.warningAmber : AppColor.onSurface
        return Menu {
            ForEach(viewModel.modes) { m in
                Button(m.name, systemImage: m.id == now?.id ? "checkmark" : m.symbol) { viewModel.selectMode(m) }
            }
        } label: {
            pickRow(now?.name ?? "") {
                Image(systemName: now?.symbol ?? "slider.horizontal.3").agyText(17, .regular, tone)
            }
        }
        .menuOrder(.fixed)
        .accessibilityLabel(Text("Mode: \(now?.name ?? "")"))
    }

    /// A menu item that shows a checkmark when it is the current choice.
    @ViewBuilder private func ticked(_ title: String, _ on: Bool, _ act: @escaping () -> Void) -> some View {
        if on {
            Button(title, systemImage: "checkmark", action: act)
        } else {
            Button(title, action: act)
        }
    }

    private var models: some View {
        let model = viewModel.selectedBaseModel
        let effort = viewModel.selectedEffortOption
        let name = model?.name ?? ""
        let caption = effort == nil ? name : name + " · " + (effort?.displayName ?? "")
        let efforts = viewModel.availableEffortsForSelectedModel
        return Menu {
            if !efforts.isEmpty {
                Section(String(localized: "Effort — \(model?.compactTitle ?? "")")) {
                    ForEach(efforts) { e in
                        ticked(e.displayName, e.value == effort?.value) { viewModel.selectEffort(e) }
                    }
                }
            }
            Section(String(localized: "Model")) {
                ForEach(viewModel.baseModels, id: \.id) { m in
                    ticked(m.name, m.id == model?.id) { viewModel.selectBaseModel(m) }
                }
            }
        } label: {
            pickRow(caption) { Image(systemName: "sparkles").agyText(17.5, .regular, AppColor.onSurface) }
        }
        .menuOrder(.fixed)
        .accessibilityLabel(Text("Model: \(name)"))
    }
}

private struct FolderHeader: View {
    let folder: WorkspaceFolder
    let hasChats: Bool
    let expanded: Bool
    let busy: Bool
    let current: Bool
    let onMain: () -> Void
    let onPlus: () -> Void

    private var stateValue: Text {
        guard hasChats else { return Text(verbatim: "") }
        return expanded ? Text("Expanded") : Text("Collapsed")
    }

    var body: some View {
        HStack(spacing: 6) {
            Button(action: onMain) { mainLabel }
                .accessibilityValue(stateValue)
                .buttonStyle(.plain)
            Button(action: onPlus) {
                Image(systemName: "plus").agyText(14.5, .medium, AppColor.onSurfaceVariant).agyBox(32, 32)
            }
            .accessibilityLabel(Text("New chat in \(folder.name)"))
            .buttonStyle(.plain)
        }
        .padding(EdgeInsets(top: 0, leading: 14, bottom: 0, trailing: 8))
        .background(current ? AppColor.sidebarActivePill.opacity(0.65) : Color.clear, in: .capsule)
    }

    private var mainLabel: some View {
        HStack(spacing: 10) {
            Image(systemName: folder.glyph)
                .agyText(15.5, .semibold, AppColor.onSurface.opacity(0.86))
                .frame(width: 24)
            Text(verbatim: folder.name).lineLimit(1).agyText(16, .medium, AppColor.onSurface)
            if let tag = folder.stackLabel {
                Text(verbatim: tag)
                    .agyText(11, .medium, AppColor.onSurfaceMuted)
                    .padding(EdgeInsets(top: 2, leading: 6, bottom: 2, trailing: 6))
                    .background(AppColor.surfaceContainerHigh, in: .capsule)
            }
            Spacer(minLength: 4)
            if busy && !expanded { RunningActivityIcon(size: 13.5) }
            if hasChats {
                Image(systemName: "chevron.right")
                    .agyText(11.5, .medium, AppColor.onSurfaceMuted)
                    .rotationEffect(expanded ? .degrees(90) : .zero)
            }
        }
        .padding(EdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0))
        .contentShape(.rect)
    }
}

private struct DrawerChatRow: View {
    let chat: ChatSummary
    let indent: Bool
    let badge: WorkspaceFolder?
    let running: Bool
    let status: String
    let selected: Bool
    let open: () -> Void

    var body: some View {
        let target = open
        return Button(action: target) {
            HStack(spacing: 8) {
                Text(chat.title.isEmpty ? String(localized: "Untitled chat") : chat.title)
                    .lineLimit(1)
                    .agyText(16.5, .regular, AppColor.onSurface)
                Spacer(minLength: 6)
                if running { activity }
                if let badge { folderTag(badge) }
            }
            .padding(.leading, indent ? 28 : 0)
        }
        .buttonStyle(DrawerRowStyle(highlighted: selected))
        .accessibilityValue(running ? Text("Working") : Text(verbatim: ""))
        .id("drawer.chat." + chat.id)
    }

    private var showsStatus: Bool { badge == nil && !status.isEmpty }

    private var activity: some View {
        HStack(spacing: 6) {
            if showsStatus {
                Text(status)
                    .agyText(11.5, .medium, AppColor.accentDot)
                    .frame(minWidth: 0, maxWidth: 88, alignment: .trailing)
                    .lineLimit(1)
            }
            RunningActivityIcon(size: 14, tint: AppColor.accentDot)
        }
    }

    private func folderTag(_ f: WorkspaceFolder) -> some View {
        HStack(spacing: 4) {
            Image(systemName: f.glyph).agyText(10.5, .semibold, AppColor.onSurfaceVariant)
            Text(verbatim: f.name).lineLimit(1).agyText(12, .regular, AppColor.onSurfaceMuted)
        }
    }
}

private struct EmptySearchNote: View {
    var body: some View {
        let lens = Image(systemName: "magnifyingglass").agyText(26, .light, AppColor.onSurfaceMuted)
        return VStack(spacing: 10) {
            lens
            Text("No matches").agyText(14.5, .regular, AppColor.onSurfaceVariant)
        }
        .padding(EdgeInsets(top: 48, leading: 0, bottom: 48, trailing: 0))
        .frame(maxWidth: .infinity)
    }
}
