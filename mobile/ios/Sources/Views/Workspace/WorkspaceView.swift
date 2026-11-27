import SwiftUI
import UIKit

/// Whether the transcript sits at its end, and its jump-to-latest button. The button lives in
/// the bottom bar: placed over the transcript, a finger on it reached the scroll view instead.
@MainActor
@Observable
final class TranscriptScrollState {
    var away: Double = 0
    var jumpShown = false
    var unseen = false
    /// Bumped by the button; the transcript scrolls to the end when it changes.
    var jumpRequest = 0

    func report(_ value: Double) {
        // Only "at the end or not" is read (the glow), so only that change redraws the screen.
        let stepped: Double = value > 0 ? 1 : 0
        if stepped != away { away = stepped }
    }
}

/// Whether the entrance swell already played in this process.
@MainActor private var entranceSwellPlayed = false

struct WorkspaceView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @Environment(\.horizontalSizeClass) private var widthClass

    @ViewBuilder private var layout: some View {
        if widthClass == .regular {
            PinnedSidebarLayout(viewModel: viewModel) { WorkspaceStage(viewModel: viewModel) }
        } else {
            DrawerContainer(viewModel: viewModel) { WorkspaceStage(viewModel: viewModel) }
        }
    }

    var body: some View {
        layout
        .background { WorkspaceShortcuts(viewModel: viewModel) }
        .onChange(of: widthClass) { _, now in
            if now == .regular, viewModel.sidebarOpen { viewModel.sidebarOpen = false }
        }
        .overlay(alignment: .top) { notices }
        .animation(.melt, value: viewModel.availableAgainChatTitle)
        .animation(.melt, value: viewModel.toast)
        .sheet(isPresented: $viewModel.showModelPicker) {
            ModelPickerSheetView(viewModel: viewModel).halfOrFull()
        }
        .sheet(isPresented: $viewModel.showUsage) {
            UsageSheetView(viewModel: viewModel).halfOrFull()
        }
        .sheet(isPresented: $viewModel.showSettings, onDismiss: viewModel.runSettingsHandoff) {
            SettingsSheetView(viewModel: viewModel).sheetSizing([.large])
        }
        .sheet(isPresented: $viewModel.showAddSheet) {
            AddSheetView(viewModel: viewModel).halfOrFull()
        }
        .sheet(isPresented: $viewModel.showComputerPicker) {
            ComputerPickerSheetView(viewModel: viewModel).halfOrFull()
        }
        .sheet(isPresented: $viewModel.showSearch) {
            ChatSearchSheetView(viewModel: viewModel).halfOrFull()
        }
        .sheet(item: $viewModel.folderPickerPending) { _ in
            FolderPickerSheetView(viewModel: viewModel).halfOrFull()
        }
        .notificationExplainer(viewModel)
    }

    private var notices: some View {
        VStack(spacing: 8) {
            if let toast = viewModel.toast {
                ToastView(text: toast).transition(.melt)
            }
            if let title = viewModel.availableAgainChatTitle {
                AvailableAgainBanner(chatTitle: title).transition(.melt)
            }
        }
        .padding(EdgeInsets(top: 56, leading: 0, bottom: 0, trailing: 0))
    }
}

private extension View {
    /// Phone: the given detents. iPad: a form sheet (detents don't apply there).
    func sheetSizing(_ stops: Set<PresentationDetent>) -> some View {
        presentationDragIndicator(.visible).presentationDetents(stops).presentationSizing(.form)
    }

    func halfOrFull() -> some View { sheetSizing([.medium, .large]) }
}

// MARK: - Stage

private struct WorkspaceStage: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @State private var scrollState = TranscriptScrollState()
    @State private var headerBottom: CGFloat = 0
    @State private var composerTop: CGFloat = 0

    private var waitingDevice: RemoteDevice? {
        guard let d = viewModel.selectedDevice, d.status != .connected,
              viewModel.isEmptyChat || viewModel.messages.isEmpty else { return nil }
        return d
    }

    private var connectingOverChat: RemoteDevice? {
        guard waitingDevice == nil, let d = viewModel.selectedDevice, d.id == viewModel.newlyPaired else { return nil }
        return d
    }

    private func connectingCard(_ d: RemoteDevice) -> some View {
        VStack(spacing: 12) {
            ThinkingIndicatorView(size: 22).frame(width: 30, height: 30)
            Text("Connecting to \(d.name)…").typeset(14, .medium, ink: AppColor.onSurface)
        }
        .padding(.horizontal, 28).padding(.vertical, 22)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    var body: some View {
        let flow = viewModel.gradientFlow
        stage
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .barEdgeFade(topEdge: headerBottom, bottomEdge: composerTop > 0 ? composerTop : nil)
            // In front of the transcript (behind the bars), so the messages never cover it.
            // Only on screen while it glows: an empty full-screen layer here still sits under the
            // glass controls and stops their animations (the mode chips' slide).
            .overlay(alignment: .top) {
                if flow.glowVisible && scrollState.away == 0 { workingGlow(flow).transition(.opacity) }
            }
            .animation(.easeInOut(duration: 0.3), value: flow.glowVisible && scrollState.away == 0)
            .safeAreaBar(edge: .top, spacing: 0) { topBar }
            .safeAreaBar(edge: .bottom, spacing: 0) { bottomBar }
            .background(ambient(flow))
            // A computer just paired, until it first answers, while a chat is open: in the middle
            // of the screen. With no chat open the stage itself says so (ConnectionStatusView).
            .overlay { if let d = connectingOverChat { connectingCard(d).transition(.melt) } }
            .animation(.melt, value: connectingOverChat?.id)
            .animation(Animation.easeInOut(duration: 0.36), value: viewModel.isEmptyChat)
            .animation(.melt, value: waitingDevice?.status)
            .onAppear { swell(empty: viewModel.isEmptyChat) }
            .onChange(of: viewModel.isEmptyChat) { _, empty in swell(empty: empty) }
    }

    /// Header strip; its lower edge drives the fade under it.
    private var topBar: some View {
        let header = WorkspaceHeaderView(viewModel: viewModel)
        return header
            .padding([.top], 4).padding([.bottom], 6)
            .reportGlobalEdge(.bottom, into: $headerBottom)
    }

    /// Composer strip; its upper edge drives the fade above it.
    private var bottomBar: some View {
        VStack(spacing: 10) {
            if scrollState.jumpShown && waitingDevice == nil && !viewModel.isEmptyChat {
                JumpToLatestButton(dot: scrollState.unseen) {
                    Haptics.impact()
                    scrollState.jumpRequest += 1
                }
                .transition(.melt)
            }
            ComposerAreaView(viewModel: viewModel)
                .reportGlobalEdge(.top, into: $composerTop)
        }
        .animation(.melt, value: scrollState.jumpShown)
    }

    private func ambient(_ flow: GradientFlow) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                AppColor.surface
                if viewModel.isEmptyChat {
                    WelcomeBackdropView(flow: flow).transition(.opacity)
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    /// The waves hanging from the top edge while the agent works.
    private func workingGlow(_ flow: GradientFlow) -> some View {
        // Shown only while the reader is at the newest output; reading back through the chat, it stays off.
        GeometryReader { geo in
            HeaderGlowView(flow: flow)
                .frame(height: max(geo.size.height * 0.608, 432))
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    @ViewBuilder private var stage: some View {
        if let waiting = waitingDevice {
            ConnectionStatusView(device: waiting).transition(AnyTransition.melt)
        } else if viewModel.isEmptyChat {
            ZeroStateView(viewModel: viewModel)
                .contentShape(.rect)
                .onTapGesture {
                    let flow = viewModel.gradientFlow
                    if flow.keyboardVisible {
                        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                    } else {
                        flow.stir()
                    }
                }
        } else if let reason = viewModel.unreadableReason, viewModel.messages.isEmpty {
            UnavailableHistoryView(cliUnsaved: reason == "cli_unsaved")
        } else if viewModel.isLoadingChat, viewModel.messages.isEmpty {
            CenterStatusView(text: String(localized: "Loading chat…"))
        } else {
            TranscriptView(viewModel: viewModel, scrollState: scrollState, centerRequest: viewModel.scrollToMiddleTick)
        }
    }

    private func swell(empty: Bool) {
        let flow = viewModel.gradientFlow
        if !entranceSwellPlayed {
            entranceSwellPlayed = true
            if empty, !viewModel.skipEntranceSplash {
                flow.setZeroStateActive(true, triggerPulse: true)
                return
            }
        }
        if empty != flow.zeroStateActive {
            flow.setZeroStateActive(empty, triggerPulse: empty)
        }
    }
}

// MARK: - Small status views

struct CenterStatusView: View {
    let text: String

    var body: some View {
        VStack(spacing: 10) {
            ThinkingIndicatorView(size: 20).agyBox(28, 28)
            Text(text).agyText(14, .regular, AppColor.onSurfaceVariant)
        }
        .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity, alignment: .center)
    }
}

struct ToastView: View {
    let text: String

    var body: some View {
        // Errors and warnings come through here: always the whole text, on as many lines as it needs.
        Text(text)
            .agyText(13, .medium, AppColor.onSurface)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(EdgeInsets(top: 8, leading: 14, bottom: 8, trailing: 14))
            .agyGlass(RoundedRectangle(cornerRadius: 18, style: .continuous), still: true)
            .padding(.horizontal, 16)
    }
}

// MARK: - Drawer

private struct DrawerContainer<Content: View>: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @ViewBuilder var content: Content
    /// Finger travel of the drag in progress (0 when idle).
    @State private var pull: CGFloat = 0
    @State private var dragging = false
    /// For a drag on the open menu: whether it began sideways (nil until decided). A drag that
    /// began as a vertical scroll never moves or closes the menu, however it wanders later.
    @State private var sideways: Bool?

    var body: some View {
        GeometryReader { geo in
            let w = max(geo.size.width, 1)
            let resting: CGFloat = viewModel.sidebarOpen ? 1 : 0
            let shown = min(1, max(0, resting + pull / w))
            ZStack(alignment: .leading) {
                content
                    .disabled(viewModel.sidebarOpen)
                    .offset(x: w * 0.28 * shown)
                    .overlay {
                        if shown > 0.001 {
                            Color.black.opacity(0.48 * shown)
                                .ignoresSafeArea()
                                .allowsHitTesting(false)
                        }
                    }
                    .overlay(alignment: .leading) {
                        if !viewModel.sidebarOpen {
                            Rectangle().fill(.clear)
                                .contentShape(.rect)
                                .frame(width: 24)
                                .gesture(openDrag(width: w))
                        }
                    }

                if shown > 0.001 || viewModel.sidebarOpen {
                    SidebarView(viewModel: viewModel)
                        .frame(width: w)
                        .offset(x: -w * (1 - shown))
                        .allowsHitTesting(viewModel.sidebarOpen || dragging)
                        .simultaneousGesture(closeDrag(width: w))
                        .zIndex(1)
                }
            }
        }
        .background(AppColor.surface)
    }

    private func openDrag(width w: CGFloat) -> some Gesture {
        let drag = DragGesture(minimumDistance: 10)
        return drag
            .onChanged { g in
                if !dragging {
                    dragging = true
                    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                }
                pull = min(w, max(0, g.translation.width))
            }
            .onEnded { g in
                dragging = false
                if g.translation.width > w * 0.24 || g.velocity.width > 380 {
                    withAnimation(.drawerOpen) { pull = 0 }
                    viewModel.showSidebar()
                } else {
                    withAnimation(.drawerOpen) { pull = 0 }
                }
            }
    }

    private func closeDrag(width w: CGFloat) -> some Gesture {
        let drag = DragGesture(minimumDistance: 14)
        return drag
            .onChanged { g in
                if sideways == nil {
                    let dx = g.translation.width, dy = g.translation.height
                    sideways = dx < 0 && abs(dx) > abs(dy) * 1.8
                }
                guard sideways == true else { return }
                dragging = true
                pull = max(-w, min(0, g.translation.width))
            }
            .onEnded { g in
                defer { sideways = nil }
                guard sideways == true else { return }
                dragging = false
                if g.translation.width < -w * 0.22 || g.velocity.width < -380 {
                    withAnimation(.drawerClose) { pull = 0 }
                    viewModel.hideSidebar()
                } else {
                    withAnimation(.drawerOpen) { pull = 0 }
                }
            }
    }
}
