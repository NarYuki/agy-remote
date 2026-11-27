import SwiftUI

/// The workspace's top bar: drawer button, model title, and the trailing cluster that morphs
/// between the empty-chat pair and the in-chat capsule.
struct WorkspaceHeaderView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @Namespace private var morph
    @Environment(\.sidebarPinned) private var sidebarPinned

    private let side = AppMetrics.headerButtonSize

    var body: some View {
        let row = HStack(spacing: 8) {
            if !sidebarPinned { drawerButton }
            modelTitle.layoutPriority(1)
            Spacer(minLength: 4)
            trailingCluster.animation(.inputMorph, value: viewModel.isEmptyChat)
        }
        let shell = GlassEffectContainer(spacing: 8) { row }
        return shell.frame(height: AppMetrics.headerHeight).padding(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
    }

    // MARK: Leading

    private var drawerButton: some View {
        let open = {
            agyResignFirstResponder()
            viewModel.showSidebar()
        }
        return Button(action: open) { MenuIcon().agyBox(side, side, hit: Circle()) }
            .accessibilityLabel(Text("Open sidebar"))
            .buttonStyle(.plain)
            .agyGlass(Circle(), morph: "hdr.menu", in: morph)
    }

    private var modelTitle: some View {
        let model = viewModel.selectedBaseModel
        let short = model?.compactTitle ?? ""
        let lift: CGFloat = 6.2
        let pick = { viewModel.showModelPicker = true }
        let brand = Text(verbatim: "Agy").agyText(17.5, .medium, AppColor.onSurface)
        return Button(action: pick) {
            HStack(alignment: VerticalAlignment.firstTextBaseline, spacing: 5) {
                brand
                if !short.isEmpty {
                    Text(verbatim: short)
                        .minimumScaleFactor(0.8)
                        .lineLimit(1)
                        .font(AppFont.headerModel)
                        .foregroundStyle(AppColor.onSurfaceVariant)
                }
                Image(systemName: "chevron.down")
                    .padding(.leading, 1)
                    .agyText(10.5, .medium, AppColor.onSurfaceVariant)
                    .alignmentGuide(.firstTextBaseline) { dim in lift + dim.height * 0.5 }
                if viewModel.isConnected {
                    Circle().foregroundStyle(AppColor.accentDot).agyBox(6, 6)
                        .alignmentGuide(.firstTextBaseline) { dim in lift + dim.height * 0.5 }
                }
            }
            .padding(.vertical, 6)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Model: \(model?.name ?? "")"))
    }

    // MARK: Trailing

    @ViewBuilder private var trailingCluster: some View {
        if viewModel.isEmptyChat {
            HStack(spacing: 10) {
                Button { viewModel.showSearch = true } label: {
                    Image(systemName: "magnifyingglass")
                        .agyText(16.5, .regular, AppColor.onSurface)
                        .agyBox(side, side, hit: Circle())
                }
                .accessibilityLabel(Text("Search"))
                .buttonStyle(.plain)
                .agyGlass(Circle(), morph: "hdr.trailing.a", in: morph)

                ComputerButton(viewModel: viewModel).glassEffectID("hdr.trailing.b", in: morph)
            }
        } else {
            HStack(spacing: 0) {
                if viewModel.quota != nil || viewModel.selectedSessionUsage != nil {
                    PlanLimitGauge(limit: viewModel.headerPlanLimit, action: { viewModel.showUsage = true })
                        .frame(width: 40, height: 42)
                }
                Button(action: newChat) { ComposeIcon(size: 19).agyBox(44, 42) }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("New chat"))
                overflow
            }
            .agyGlass(Capsule(), morph: "hdr.trailing.a", in: morph)
        }
    }

    private func newChat() { viewModel.startNewChat(in: viewModel.currentFolder) }

    private var modeChoice: Binding<String> {
        let vm = viewModel
        return Binding(get: { vm.selectedMode?.id ?? "" }) { picked in
            if let hit = vm.modes.first(where: { $0.id == picked }) { vm.selectMode(hit) }
        }
    }

    private var overflowGlyph: some View {
        Image(systemName: "ellipsis").agyBox(44, 42).agyText(16, .medium, AppColor.onSurface)
    }

    private var overflow: some View {
        Menu {
            if !viewModel.modes.isEmpty {
                Picker(selection: modeChoice) {
                    ForEach(viewModel.modes) { Label($0.name, systemImage: $0.symbol).tag($0.id) }
                } label: { Text("Mode") }
                .pickerStyle(.inline)
            }
            Divider()
            Button("New chat", systemImage: "square.and.pencil", action: newChat)
            Button("Search", systemImage: "magnifyingglass") { viewModel.showSearch = true }
            Button("Settings", systemImage: "gearshape") { viewModel.showSettings = true }
        } label: { overflowGlyph }
        .menuOrder(.fixed)
        .accessibilityLabel(Text("More"))
    }
}

/// The computer's icon inside a thin status ring: green when connected, grey otherwise.
struct ComputerRing: View {
    let device: RemoteDevice?
    var size: CGFloat = 42
    var glyphSize: CGFloat = 14
    var ringInset: CGFloat = 1.5
    var offlineOpacity: Double = 0.85

    private var ringTone: Color {
        let online = device?.status == .connected
        return online ? AppColor.connectedGreen : AppColor.accentRing.opacity(offlineOpacity)
    }

    var body: some View {
        Image(systemName: device?.deviceIconName ?? "desktopcomputer")
            .agyText(glyphSize, .medium, AppColor.onSurface)
            .frame(width: size, height: size)
            .background {
                Circle().inset(by: ringInset).strokeBorder(ringTone, lineWidth: 1.8)
            }
    }
}

/// Opens the computer picker; shows the selected computer's ring.
struct ComputerButton: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel

    var body: some View {
        let name = viewModel.selectedDevice?.name
        Button(action: { viewModel.showComputerPicker = true }) {
            ComputerRing(device: viewModel.selectedDevice).contentShape(.circle)
        }
        .accessibilityLabel(name.map { Text(verbatim: $0) } ?? Text("Computer"))
        .buttonStyle(.plain)
        .agyGlass(Circle())
    }
}
