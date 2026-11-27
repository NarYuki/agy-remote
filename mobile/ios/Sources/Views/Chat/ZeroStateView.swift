import SwiftUI

private typealias Tap = () -> Void

/// The empty chat: the current project and a few recent chats, sitting low on the page.
/// The aurora behind it is drawn by the workspace, not here.
struct ZeroStateView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var entered = false

    var body: some View {
        let hasDraft = !viewModel.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let shown = entered || reduceMotion || viewModel.skipEntranceSplash
        VStack(spacing: 0) {
            Spacer(minLength: 40)
            if hasDraft == false {
                ZeroStateList(viewModel: viewModel, shown: shown)
                    .transition(Self.listTransition)
            }
        }
        .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity, alignment: .bottomLeading)
        .animation(Self.listMotion, value: hasDraft)
        .onAppear(perform: enter)
    }

    private static let listTransition = AnyTransition.opacity.combined(with: .offset(y: 6))
    private static let listMotion = Animation.easeOut(duration: 0.18)

    private func enter() {
        if entered { return }
        withAnimation(Animation.spring(response: 0.52, dampingFraction: 0.92)) { entered = true }
    }
}

private struct ZeroStateList: View {
    let viewModel: RemoteWorkspaceViewModel
    let shown: Bool

    private var picks: ArraySlice<ChatSummary> {
        let all = viewModel.chats
        let path = viewModel.currentFolder?.path
        let local = path == nil ? [] : all.filter { $0.folder == path }
        return (local.isEmpty ? all : local).prefix(4)
    }

    var body: some View {
        VStack(alignment: HorizontalAlignment.leading, spacing: 20) {
            if let folder = viewModel.currentFolder {
                ProjectChip(folder: folder) { viewModel.showSidebar() }
                    .opacity(shown ? 1 : 0)
            }
            ForEach(picks) { summary in
                RecentChatRow(title: summary.title) {
                    Task { await viewModel.openChat(summary.id) }
                }
                .modifier(RiseIn(shown: shown))
            }
        }
        .padding(EdgeInsets(top: 0, leading: 34, bottom: 30, trailing: 34))
    }
}

/// Before the entrance settles, rows sit a little lower, smaller and invisible.
private struct RiseIn: ViewModifier {
    let shown: Bool
    func body(content: Content) -> some View {
        content
            .scaleEffect(shown ? 1 : 0.96, anchor: .bottomLeading)
            .offset(y: shown ? 0 : 10)
            .opacity(shown ? 1 : 0)
    }
}

private struct ProjectChip: View {
    let folder: WorkspaceFolder
    let onPress: Tap

    var body: some View {
        Button(action: onPress) {
            HStack(spacing: 8) {
                Image(systemName: folder.glyph).chatType(14, folder.glyphTint, .semibold)
                Text(folder.name).chatType(14.5, AppColor.onSurfaceVariant, .medium)
                if let tag = folder.stackLabel, !tag.isEmpty {
                    Text(tag)
                        .chatType(11, AppColor.onSurfaceMuted, .medium)
                        .chatPad(6, 2)
                        .chatCapsule(AppColor.surfaceContainerHigh)
                }
            }
        }
        .buttonStyle(PlainButtonStyle())
    }
}

private struct RecentChatRow: View {
    let title: String
    let onPress: Tap

    var body: some View {
        Button(action: onPress) {
            HStack(spacing: 16) {
                let iconInk = AppColor.onSurface.opacity(0.86)
                Image(systemName: "clock.arrow.circlepath")
                    .chatType(17.5, iconInk)
                    .chatSize(24, 24)
                Text(title).lineLimit(1).chatType(16.5, AppColor.onSurface.opacity(0.92))
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(DimOnPress())
    }
}

private struct DimOnPress: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        let down = configuration.isPressed
        return configuration.label
            .scaleEffect(down ? 0.985 : 1, anchor: .leading)
            .opacity(down ? 0.55 : 1)
            .animation(Animation.easeOut(duration: 0.14), value: down)
    }
}

// MARK: - Mode chips

struct ModeChipsView: View {
    let viewModel: RemoteWorkspaceViewModel
    @Namespace private var slider

    var body: some View {
        let current = viewModel.selectedMode?.id
        let all = viewModel.modes
        HStack(spacing: 1.5) {
            ForEach(all) { mode in
                ModeChip(mode: mode, active: mode.id == current, slider: slider) {
                    withAnimation(.pill) { viewModel.selectMode(mode) }
                }
            }
        }
        .padding(3)
        .chatGlass(Capsule())
    }
}

private struct ModeChip: View {
    let mode: AgyMode
    let active: Bool
    let slider: Namespace.ID
    let tap: Tap

    var body: some View {
        Button(action: tap) {
            label
                .padding(.horizontal, active ? 10.5 : 8.5)
                .frame(height: 28)
                .background { thumb }
                .contentShape(Capsule())
        }
        .buttonStyle(PlainButtonStyle())
    }

    private var label: some View {
        HStack(spacing: 4) {
            if active {
                Image(systemName: mode.symbol)
                    .chatType(10.5, mode.bypassesPermissions ? AppColor.warningAmber : Color.white, .semibold)
            }
            Text(mode.name)
                .chatType(12.4, active ? Color.white : AppColor.onSurface.opacity(0.84), active ? .medium : .regular)
                .lineLimit(1)
                .fixedSize()
        }
    }

    @ViewBuilder private var thumb: some View {
        if active {
            let dark = Color.black.opacity(0.86)
            Capsule()
                .fill(dark)
                .matchedGeometryEffect(id: "mode-thumb", in: slider)
        }
    }
}

extension WorkspaceFolder {
    var glyphTint: Color {
        let table: [String: Color] = [
            "swift": Color(red: 0.94, green: 0.32, blue: 0.22),
            "square.stack.3d.up.fill": AppColor.brandViolet,
            "gearshape.2.fill": AppColor.warningAmber,
            "terminal.fill": AppColor.connectedGreen,
            "cube.box.fill": AppColor.brandSky,
        ]
        return table[glyph] ?? AppColor.primary
    }
}
