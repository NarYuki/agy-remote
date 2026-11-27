import SwiftUI

@main
struct AGYRemoteApp: App {
    @State var workspace = RemoteWorkspaceViewModel()
    @UIApplicationDelegateAdaptor(AppDelegate.self) var bridge
    /// Serves the Apple Watch app over WatchConnectivity.
    @State private var watch = WatchBridge()

    var body: some Scene {
        WindowGroup {
            RootView(viewModel: workspace)
                .task {
                    watch.attach(workspace)
                }
        }
    }
}

struct RootView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel

    /// The relay alert. Closing it without choosing still counts as "Not Now".
    private var relayPrompt: Binding<Bool> {
        let vm = viewModel
        return Binding(get: { vm.showRelayConsent }) { visible in
            guard visible == false, vm.showRelayConsent else { return }
            vm.answerRelayConsent(false)
        }
    }

    @ViewBuilder private var stage: some View {
        if viewModel.showsLanding {
            LandingView(viewModel: viewModel)
                .transition(AnyTransition.opacity)
        } else {
            WorkspaceView(viewModel: viewModel)
                .transition(AnyTransition.opacity)
        }
    }

    /// Setup pages have no toast of their own, so the root shows it there.
    @ViewBuilder private var setupToast: some View {
        let text = viewModel.showsLanding ? viewModel.toast : nil
        ZStack {
            if let text {
                ToastView(text: text).padding(.top, 6).transition(.melt)
            }
        }
        .animation(Animation.melt, value: viewModel.toast)
    }

    var body: some View {
        ZStack {
            AppColor.surface
                .ignoresSafeArea()
            stage
        }
        .overlay(alignment: .top) { setupToast }
        .animation(Animation.smooth(duration: 0.5), value: viewModel.showsLanding)
        .onAppear(perform: viewModel.applyAppearanceToWindows)
        .onOpenURL(perform: viewModel.handleIncomingURL)
        .sheet(item: pairingAsked) { _ in
            PairNumberSheet(viewModel: viewModel).presentationDetents([.medium, .large])
        }
        .alert(Text("Pair with a computer?"), isPresented: pairLinkAsked) {
            Button(role: .cancel) { viewModel.answerPairLink(false) } label: { Text("Cancel") }
            Button { viewModel.answerPairLink(true) } label: { Text("Pair") }
        } message: {
            Text("A link wants to pair AGY Remote with a computer. Continue only if you just ran agy-connector pair on your own computer.")
        }
        .preferredColorScheme(viewModel.theme.colorScheme)
        .alert(Text("Can't reach your computer directly"), isPresented: relayPrompt, actions: relayButtons) {
            Text("Your network may be blocking a direct connection. AGY Remote can pass the connection through a public relay. It stays end-to-end encrypted: the relay can't read it, but it can see that your phone and computer are talking. You can change this in Settings.")
        }
    }

    /// The question before an `agy-rem://pair` link does anything. Closing it counts as "Cancel".
    private var pairLinkAsked: Binding<Bool> {
        let vm = viewModel
        return Binding(get: { vm.pendingPairLink != nil }) { visible in
            if !visible { vm.answerPairLink(false) }
        }
    }

    private var pairingAsked: Binding<RemoteWorkspaceViewModel.PendingPairing?> {
        Binding(get: { viewModel.pendingPairing }) { new in
            if new == nil { viewModel.cancelPairing() }
        }
    }

    @ViewBuilder private func relayButtons() -> some View {
        let answer = viewModel.answerRelayConsent
        Button(role: .cancel) { answer(false) } label: { Text("Not Now") }
        Button { answer(true) } label: { Text("Use Relay") }
    }
}
