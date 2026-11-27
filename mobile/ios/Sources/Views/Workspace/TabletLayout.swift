import SwiftUI

/// Layout numbers for wide windows (iPad full screen, Stage Manager, wide split view).
enum TabletMetrics {
    /// Width of the pinned sidebar column.
    static let sidebarColumn: CGFloat = 320
    /// Widest the transcript and composer grow before centring.
    static let readable: CGFloat = 720
}

private struct PinnedSidebarKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// True when the sidebar sits beside the chat as a fixed column instead of a drawer.
    var sidebarPinned: Bool {
        get { self[PinnedSidebarKey.self] }
        set { self[PinnedSidebarKey.self] = newValue }
    }
}

extension View {
    /// Caps content at the readable width and centres it in whatever room is left.
    func readableColumn(_ alignment: Alignment = .leading) -> some View {
        frame(maxWidth: TabletMetrics.readable, alignment: alignment).frame(maxWidth: .infinity)
    }
}

/// Sidebar column on the left, chat column on the right, split by a hairline.
struct PinnedSidebarLayout<Content: View>: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @ViewBuilder var content: Content

    var body: some View {
        HStack(spacing: 0) {
            SidebarView(viewModel: viewModel)
                .frame(width: TabletMetrics.sidebarColumn)
            Rectangle()
                .fill(AppColor.onSurface.opacity(0.12))
                .frame(width: 1 / UIScreen.main.scale)
                .ignoresSafeArea()
            content
                .frame(maxWidth: .infinity)
        }
        .environment(\.sidebarPinned, true)
        .background(AppColor.surface)
    }
}

/// Hidden buttons that carry the hardware-keyboard shortcuts of the workspace.
struct WorkspaceShortcuts: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel

    var body: some View {
        ZStack {
            Button("New chat") { viewModel.startNewChat(in: viewModel.currentFolder) }
                .keyboardShortcut("n", modifiers: .command)
            Button("Search") { viewModel.showSearch = true }
                .keyboardShortcut("k", modifiers: .command)
        }
        .opacity(0)
        .frame(width: 0, height: 0)
        .accessibilityHidden(true)
    }
}
