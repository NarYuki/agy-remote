import SwiftUI
import UIKit

/// First-run setup. Pages: 0 welcome, 1 demo, 2 connect, and once something is paired
/// 3 ready and 4 limits. Shown until the view model says onboarding is done.
struct LandingView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel

    @State private var index = 0
    @State private var scannerUp = false
    @State private var manualOpen = false
    @State private var manualText = ""
    @State private var busyPairing = false
    @State private var pairFailure: String?
    @FocusState private var manualFocus: Bool

    private var paired: Bool { viewModel.pairedTickets.isEmpty == false }
    private var total: Int { paired ? 5 : 3 }

    var body: some View {
        pager
            .background { WelcomeBackdropView(flow: viewModel.gradientFlow).ignoresSafeArea() }
            .safeAreaInset(edge: .bottom) {
                controls
                    .frame(maxWidth: 520)
                    .padding(EdgeInsets(top: 0, leading: 24, bottom: 12, trailing: 24))
            }
            .onAppear(perform: arrive)
            .onChange(of: index) { Feel.select() }
            .onChange(of: paired) { _, nowPaired in
                guard nowPaired else { return }
                withAnimation(.spring(response: 0.5, dampingFraction: 0.9)) { index = 3 }
            }
            .onChange(of: viewModel.setupConnection) { _, link in
                if paired && link == .connected { Feel.success() }
            }
            .sheet(isPresented: $scannerUp) {
                QRCodeScannerSheet { value in
                    manualText = value
                    submitCode()
                }
            }
            .notificationExplainer(viewModel)
    }

    private func arrive() {
        Feel.warmUp()
        viewModel.gradientFlow.setZeroStateActive(true, triggerPulse: true)
    }

    private var pager: some View {
        TabView(selection: $index) {
            IntroPage().tag(0)
            StoryPage(live: index == 1).tag(1)
            SetupPage(live: index == 2).tag(2)
            if paired {
                StatusPage(viewModel: viewModel).tag(3)
                NotesPage().tag(4)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
    }

    // MARK: Bottom controls

    private var controls: some View {
        let onConnect = index == 2
        return VStack(spacing: 14) {
            if onConnect && manualOpen {
                manualEntry.transition(riseIn)
            }
            if onConnect, let pairFailure {
                Label(pairFailure, systemImage: "exclamationmark.triangle.fill")
                    .agyText(13.5, .medium, AppColor.errorRed)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.updatesFrequently)
                    .transition(.opacity)
            }
            navRow
            Button(action: toggleManual) {
                Text(manualOpen ? "Hide pairing code" : "Enter a pairing code instead")
                    .agyText(14.5, .medium, AppColor.onSurfaceVariant)
                    .frame(maxWidth: .infinity, minHeight: 32, maxHeight: 32)
            }
            .buttonStyle(.plain)
            .allowsHitTesting(onConnect)
            .opacity(onConnect ? 1 : 0)
            // The welcome page uses the same quiet slot for the privacy policy.
            .overlay {
                Link(destination: AppLinks.privacyPolicy) {
                    Text("Privacy Policy")
                        .agyText(13.5, .medium, AppColor.onSurfaceMuted)
                        .frame(maxWidth: .infinity, minHeight: 32, maxHeight: 32)
                }
                .allowsHitTesting(index == 0)
                .opacity(index == 0 ? 1 : 0)
            }
        }
        .animation(.spring(response: 0.36, dampingFraction: 0.88), value: index)
    }

    private func toggleManual() {
        withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) { manualOpen.toggle() }
        if manualOpen { manualFocus = true }
    }

    private var manualEntry: some View {
        let empty = manualText.isEmpty
        return HStack(spacing: 8) {
            TextField(text: $manualText) { Text(verbatim: "agy-rem://pair?…") }
                .agyText(14, .regular, mono: true)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .focused($manualFocus)
                .submitLabel(.go)
                .onSubmit(submitCode)
                .onChange(of: manualText) {
                    guard pairFailure != nil else { return }
                    withAnimation { pairFailure = nil }
                }
            Button(action: { if empty { pasteCode() } else { submitCode() } }) {
                Text(empty ? "Paste" : "Pair").agyText(14, .semibold, AppColor.primary)
            }
            .buttonStyle(.plain)
        }
        .padding(EdgeInsets(top: 0, leading: 18, bottom: 0, trailing: 18))
        .frame(height: 52)
        .agyGlass(Capsule(), still: true)
    }

    private func pasteCode() { manualText = UIPasteboard.general.string ?? "" }

    private var navRow: some View {
        let showsNext = [0, 1, 3].contains(index)
        return HStack(spacing: 12) {
            PageDots(current: index, count: total)
            Spacer()
            Button(action: advance) {
                ArrowCaption(index == 0 ? "Get started" : "Next", size: 16.5)
                    .padding(EdgeInsets(top: 0, leading: 22, bottom: 0, trailing: 22))
                    .frame(height: 50)
                    .background(AppColor.onSurface, in: .capsule)
            }
            .buttonStyle(.plain)
        }
        .frame(height: 54)
        .allowsHitTesting(showsNext)
        .opacity(showsNext ? 1 : 0)
        .overlay {
            switch index {
            case 2: scanAction
            case 4: finishAction
            default: EmptyView()
            }
        }
    }

    private func advance() {
        Feel.press()
        let next = Swift.min(total - 1, index + 1)
        withAnimation(.spring(response: 0.42, dampingFraction: 0.9)) { index = next }
    }

    private var scanAction: some View {
        Button {
            Feel.press()
            scannerUp = true
        } label: {
            HStack(spacing: 10) {
                if busyPairing {
                    ProgressView().tint(AppColor.surface)
                    Text("Connecting…")
                } else {
                    Image(systemName: "qrcode.viewfinder").face(18, .semibold)
                    Text("Scan QR code")
                }
            }
            .modifier(FullPill())
        }
        .buttonStyle(.plain)
        .disabled(busyPairing)
    }

    private var finishAction: some View {
        Button {
            Feel.press()
            viewModel.completeOnboarding()
        } label: {
            ArrowCaption("Start", size: 17).modifier(FullPill())
        }
        .buttonStyle(.plain)
    }

    private func submitCode() {
        let raw = manualText.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty || busyPairing { return }
        manualFocus = false
        pairFailure = nil
        busyPairing = true
        Task {
            await viewModel.pairConnector(rawTicket: raw)
            busyPairing = false
            guard let problem = viewModel.pairingError else {
                manualText = ""
                // Already paired before (a link, an earlier code): `paired` doesn't change, so move on here.
                if paired { withAnimation(.spring(response: 0.5, dampingFraction: 0.9)) { index = 3 } }
                return
            }
            withAnimation { pairFailure = problem }
        }
    }
}

// MARK: - Bits shared by the bottom bar

private struct PageDots: View {
    let current: Int
    let count: Int

    var body: some View {
        HStack(spacing: 7) {
            ForEach(0..<count, id: \.self) { n in
                let here = n == current
                AppColor.onSurface.opacity(here ? 1 : 0.18)
                    .frame(width: here ? 18 : 6, height: 6)
                    .clipShape(.capsule)
            }
        }
        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: current)
        .accessibilityElement()
        .accessibilityLabel(Text("Page \(current + 1) of \(count)"))
    }
}

/// A label followed by a small arrow, drawn in the surface colour.
private struct ArrowCaption: View {
    let title: LocalizedStringKey
    let size: CGFloat

    init(_ title: LocalizedStringKey, size: CGFloat) {
        self.title = title
        self.size = size
    }

    var body: some View {
        HStack(spacing: 8) {
            Text(title).face(size, .semibold)
            Image(systemName: "arrow.right").face(14, .semibold)
        }
        .foregroundStyle(AppColor.surface)
    }
}

/// Full-width 54 pt capsule in the text colour, content in the surface colour.
private struct FullPill: ViewModifier {
    func body(content: Content) -> some View {
        content
            .agyText(17, .semibold, AppColor.surface)
            .frame(maxWidth: .infinity, minHeight: 54, maxHeight: 54)
            .background(Capsule().fill(AppColor.onSurface))
    }
}

// MARK: - Haptics for this flow

@MainActor
private enum Feel {
    private static let softGen = UIImpactFeedbackGenerator(style: .soft)
    private static let lightGen = UIImpactFeedbackGenerator(style: .light)
    private static let rigidGen = UIImpactFeedbackGenerator(style: .rigid)
    private static let mediumGen = UIImpactFeedbackGenerator(style: .medium)
    private static let selectGen = UISelectionFeedbackGenerator()
    private static let noticeGen = UINotificationFeedbackGenerator()

    static func warmUp() {
        for g in [softGen, lightGen, rigidGen, mediumGen] { g.prepare() }
        selectGen.prepare()
        noticeGen.prepare()
    }

    static func arrive() { softGen.impactOccurred(intensity: 0.7) }
    static func tick() { lightGen.impactOccurred(intensity: 0.55) }
    static func key() { rigidGen.impactOccurred(intensity: 0.28) }
    static func attention() { rigidGen.impactOccurred(intensity: 0.8) }
    static func press() { mediumGen.impactOccurred(intensity: 0.75) }
    static func select() { selectGen.selectionChanged() }
    static func success() { noticeGen.notificationOccurred(.success) }
}

private var riseIn: AnyTransition { AnyTransition.move(edge: .bottom).combined(with: .opacity) }
private var dropIn: AnyTransition { AnyTransition.move(edge: .top).combined(with: .opacity) }

private extension View {
    func face(_ pt: CGFloat, _ w: Font.Weight) -> some View { font(Font.system(size: pt, weight: w)) }
}

// MARK: - Page scaffolding

/// Leading-aligned content, centred vertically, at most 520 wide.
private struct Centered<Inner: View>: View {
    @ViewBuilder let inner: Inner

    var body: some View {
        VStack(alignment: .leading, spacing: .zero) {
            Spacer(minLength: 0)
            inner
            Spacer(minLength: 0)
        }
        .padding(EdgeInsets(top: 0, leading: 28, bottom: 0, trailing: 28))
        .frame(maxWidth: 520, alignment: .leading)
        .frame(maxWidth: .infinity)
    }
}

private extension View {
    /// Never squeezed vertically when space runs short.
    func keepsHeight() -> some View { fixedSize(horizontal: false, vertical: true) }

    func headline(_ pt: CGFloat) -> some View {
        agyText(pt, .bold, AppColor.onSurface).tracking(-0.8)
    }

    func bodyCopy(_ pt: CGFloat) -> some View {
        agyText(pt, .regular, AppColor.onSurfaceVariant)
    }
}

// MARK: - 0 Welcome

/// Small GitHub mark beside the mono wordmark; opens the public repository.
private struct RepoMarkButton: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        Button {
            Feel.tick()
            openURL(AppLinks.sourceRepo)
        } label: {
            Image("GitHubMark")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(width: 11, height: 11)
                .foregroundStyle(AppColor.onSurfaceVariant)
                .frame(width: 44, height: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Open on GitHub"))
    }
}

private struct IntroPage: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var settled = false

    var body: some View {
        Centered {
            blinkingMark
                .padding(.bottom, 30)
                .offset(y: settled ? 0 : 10)
                .opacity(settled ? 1 : 0)
            Text("agy, from anywhere.")
                .headline(40)
                .lineSpacing(-2)
                .minimumScaleFactor(0.7)
                .lineLimit(2)
                .offset(y: settled ? 0 : 14)
                .opacity(settled ? 1 : 0)
            HStack(alignment: .center, spacing: 2) {
                Text("agy remote")
                    .agyText(15, .medium, mono: true, AppColor.onSurfaceVariant)
                RepoMarkButton()
            }
            .padding(.top, 18 - 13)
            .opacity(settled ? 1 : 0)
        }
        .onAppear {
            withAnimation(.spring(response: 0.8, dampingFraction: 0.9).delay(0.2)) { settled = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.38) { Feel.arrive() }
        }
    }

    private var blinkingMark: some View {
        TimelineView(.periodic(from: .now, by: 0.53)) { ctx in
            let phase = Int(ctx.date.timeIntervalSinceReferenceDate / 0.53) & 1
            let lit = phase == 0 || scenePhase != .active
            BrandMarkView(size: 58, cursorOpacity: lit ? 1 : 0)
                .animation(.easeInOut(duration: 0.12), value: lit)
        }
    }
}

// MARK: - 1 Demo

private struct StoryPage: View {
    let live: Bool
    @State private var beat = 0

    private static let dwell: [Double] = [0.7, 1.3, 1.1, 1.6, 1.0, 2.6]

    var body: some View {
        Centered {
            Text("It runs there.\nYou review here.").headline(40).padding(.bottom, 10)
            Text("Send a task, follow along as it runs, and approve each change before it happens — from the couch, the train, anywhere.")
                .bodyCopy(16)
                .padding(.bottom, 28)
            conversation
                .frame(maxWidth: .infinity, alignment: .topLeading)
                .frame(height: 250, alignment: .top)
        }
        .task(id: live) { if live { await play() } }
    }

    private var conversation: some View {
        VStack(alignment: .leading, spacing: 12) {
            if beat >= 1 {
                HStack(spacing: 0) {
                    Spacer(minLength: 60)
                    Text("Add a dark mode to the settings screen")
                        .agyText(15, .regular, AppColor.onSurface)
                        .padding(EdgeInsets(top: 10, leading: 15, bottom: 10, trailing: 15))
                        .background(AppColor.userBubble, in: .rect(cornerRadius: 18, style: .continuous))
                }
                .transition(riseIn)
            }
            if beat == 2 {
                HStack(spacing: 8) {
                    ThinkingIndicatorView(style: .ring)
                    Text("Thinking").agyText(13.5, .medium, AppColor.onSurfaceVariant)
                }
                .transition(.opacity)
            }
            if beat >= 3 {
                ToolStrip(symbol: "pencil", command: "Edited  settings/theme.css", result: "+38 \u{2212}6")
            }
            if (3...4).contains(beat) { PermissionMock(granted: beat == 4) }
            if beat >= 5 {
                ToolStrip(symbol: "terminal", command: "npm run build", result: "Build succeeded", resultTone: AppColor.connectedGreen)
                Text("Done — Settings has a dark mode now, and the build passes.")
                    .agyText(15, .regular, AppColor.onSurface)
                    .transition(.opacity)
            }
        }
    }

    private func play() async {
        var round = 0
        while !Task.isCancelled {
            for b in Self.dwell.indices {
                withAnimation(.spring(response: 0.45, dampingFraction: 0.86)) { beat = b }
                if round == 0 { cue(for: b) }
                guard (try? await Task.sleep(for: .seconds(Self.dwell[b]))) != nil else { return }
            }
            round += 1
        }
    }

    private func cue(for b: Int) {
        switch b {
        case 1: Feel.tick()
        case 3: Feel.attention()
        case 4: Feel.press()
        case 5: Feel.success()
        default: break
        }
    }
}

private struct ToolStrip: View {
    let symbol: String
    let command: String
    let result: String
    var resultTone: Color = AppColor.onSurfaceVariant

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: symbol).agyText(12, .semibold, AppColor.onSurfaceVariant).frame(width: 16)
            Text(verbatim: command).agyText(13.5, .regular, mono: true, AppColor.onSurface)
            Spacer(minLength: 8)
            Text(verbatim: result).agyText(12.5, .medium, mono: true, resultTone)
        }
        .padding(EdgeInsets(top: 9, leading: 12, bottom: 9, trailing: 12))
        .background(AppColor.surfaceContainer.opacity(0.85), in: .rect(cornerRadius: 12))
    }
}

private struct PermissionMock: View {
    let granted: Bool

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: HorizontalAlignment.leading, spacing: 2) {
                Text("Run command?").agyText(12.5, .medium, AppColor.onSurfaceVariant)
                Text(verbatim: "npm run build").agyText(14, .medium, mono: true, AppColor.onSurface)
            }
            Spacer()
            Text("Deny")
                .agyText(13.5, .semibold, AppColor.onSurfaceVariant)
                .padding(EdgeInsets(top: 0, leading: 12, bottom: 0, trailing: 12))
                .frame(height: 32)
            allowPill
        }
        .padding(EdgeInsets(top: 11, leading: 14, bottom: 11, trailing: 14))
        .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(AppColor.outlineVariant, lineWidth: 1) }
    }

    private var allowPill: some View {
        HStack(spacing: 5) {
            if granted { Image(systemName: "checkmark").face(12, .bold) }
            Text(granted ? "Allowed" : "Allow")
        }
        .agyText(13.5, .semibold, AppColor.surface)
        .padding(EdgeInsets(top: 0, leading: 14, bottom: 0, trailing: 14))
        .frame(height: 32)
        .background((granted ? AppColor.connectedGreen : AppColor.onSurface), in: .capsule)
        .scaleEffect(granted ? 0.96 : 1)
    }
}

// MARK: - 2 Connect

private enum HostOS: String, CaseIterable, Identifiable {
    case unix, windows

    var id: Self { self }
    var label: String { self == .unix ? "macOS / Linux" : "Windows" }

    /// The shell's prompt in the console drawing.
    var prompt: String { self == .unix ? "$ " : "PS> " }

    var installCommand: String {
        // One installer for macOS and Linux: it picks the right build for the machine.
        self == .unix ? "curl -fsSL https://n4t.su/agy/ | sh" : "irm https://n4t.su/agy/ps | iex"
    }

    var doneLine: String {
        self == .unix ? "✓ agy-connector installed to ~/.local/bin" : "✓ agy-connector installed"
    }
}

private let consoleBlack = Color(red: 0.06, green: 0.07, blue: 0.08)
private let upCommand = "agy-connector pair"

private struct SetupPage: View {
    let live: Bool
    @State private var os: HostOS = .unix
    @State private var explainerUp = false
    @State private var copiedFlag = false
    @State private var typed1 = 0
    @State private var typed2 = 0
    @State private var installed = false
    @State private var printed = false
    @State private var runner: Task<Void, Never>?

    var body: some View {
        Centered {
            Text("Two commands\non your computer.")
                .headline(38)
                .minimumScaleFactor(0.7)
                .lineLimit(2)
                .keepsHeight()
                .padding(.bottom, 10)
            Text("Install the connector, start it, then scan the code it prints. Your phone and computer talk directly.")
                .bodyCopy(15.5)
                .keepsHeight()
                .padding(.bottom, 8)
            Button {
                Feel.press()
                explainerUp = true
            } label: {
                HStack(spacing: 4) {
                    Text("How it works")
                    Image(systemName: "chevron.right").face(11, .semibold)
                }
                .agyText(15, .medium, AppColor.primary)
                .padding(EdgeInsets(top: 6, leading: 0, bottom: 6, trailing: 0))
            }
            .buttonStyle(.plain)
            .padding(.bottom, 12)
            Picker("Platform", selection: $os) {
                ForEach(HostOS.allCases) { Text(verbatim: $0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .frame(width: 260)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 12)
            console
            // Room for the bottom bar; it gives way first when the pairing field
            // opens, so the copy above never gets clipped.
            Color.clear.frame(minHeight: 0, maxHeight: 170)
        }
        .sheet(isPresented: $explainerUp) { ExplainerSheet().presentationDetents([.large]) }
        .onChange(of: os) {
            Feel.select()
            replay()
        }
        .onChange(of: live) { _, isLive in
            guard isLive else { runner?.cancel(); return }
            if !printed { replay() }
        }
        .onAppear { if live && !printed { replay() } }
        .onDisappear { runner?.cancel() }
    }

    private var console: some View {
        VStack(alignment: .leading, spacing: .zero) {
            titleBar
            VStack(alignment: .leading, spacing: 8) {
                if os == .windows {
                    Text("Run in PowerShell").foregroundStyle(Color.white.opacity(0.55)).transition(.opacity)
                }
                PromptLine(prompt: os.prompt, typed: String(os.installCommand.prefix(typed1)), cursor: !installed)
                if installed {
                    Text(verbatim: os.doneLine).foregroundStyle(Color.white.opacity(0.55)).transition(.opacity)
                    PromptLine(prompt: os.prompt, typed: String(upCommand.prefix(typed2)), cursor: !printed).padding(.top, 4)
                }
                if printed {
                    HStack(spacing: 16) {
                        DecorativeQR().frame(width: 84, height: 84)
                        VStack(alignment: .leading, spacing: 5) {
                            Text("Connector ready").foregroundStyle(AppColor.brandMint)
                            Text("Scan to pair →").foregroundStyle(Color.white.opacity(0.7))
                        }
                        .padding(.top, 4)
                    }
                    .transition(dropIn)
                }
            }
            .agyText(13.5, .regular, mono: true)
            .padding(EdgeInsets(top: 0, leading: 16, bottom: 16, trailing: 16))
        }
        .frame(maxWidth: .infinity, minHeight: 236, alignment: .topLeading)
        .background { RoundedRectangle(cornerRadius: 16).fill(consoleBlack) }
        .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(Color.white.opacity(0.08), lineWidth: 1) }
        .environment(\.colorScheme, .dark)
    }

    private var titleBar: some View {
        let lights = [Color(red: 1, green: 0.37, blue: 0.34), Color(red: 1, green: 0.74, blue: 0.18),
                      Color(red: 0.16, green: 0.79, blue: 0.25)]
        return HStack(spacing: 7) {
            ForEach(lights.indices, id: \.self) { i in lights[i].frame(width: 10, height: 10).clipShape(.circle) }
            Spacer()
            Button(action: copyCommands) {
                Label(copiedFlag ? "Copied" : "Copy", systemImage: copiedFlag ? "checkmark" : "doc.on.doc")
                    .contentTransition(.symbolEffect(.replace))
                    .agyText(12, .medium, Color.white.opacity(0.6))
            }
            .accessibilityLabel(Text("Copy install and start commands"))
            .buttonStyle(.plain)
        }
        .padding(EdgeInsets(top: 11, leading: 14, bottom: 11, trailing: 14))
    }

    private func copyCommands() {
        UIPasteboard.general.string = os.installCommand + "\n" + upCommand
        Feel.success()
        copiedFlag = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { copiedFlag = false }
    }

    private func replay() {
        runner?.cancel()
        (typed1, typed2, installed, printed) = (0, 0, false, false)
        let count = os.installCommand.count
        runner = Task { @MainActor in
            do {
                try await Task.sleep(for: .seconds(0.35))
                Feel.key()
                for _ in 0..<count {
                    try await Task.sleep(for: .milliseconds(Int.random(in: 28...70)))
                    typed1 += 1
                }
                try await Task.sleep(for: .seconds(0.45))
                withAnimation(.easeOut(duration: 0.2)) { installed = true }
                Feel.tick()
                try await Task.sleep(for: .seconds(0.35))
                Feel.key()
                for _ in upCommand {
                    try await Task.sleep(for: .milliseconds(Int.random(in: 40...90)))
                    typed2 += 1
                }
                try await Task.sleep(for: .seconds(0.35))
                withAnimation(.spring(response: 0.5, dampingFraction: 0.9)) { printed = true }
                Feel.success()
            } catch {
                return
            }
        }
    }
}

private struct PromptLine: View {
    let prompt: String
    let typed: String
    let cursor: Bool

    var body: some View {
        HStack(spacing: 0) {
            Text(verbatim: prompt).foregroundStyle(AppColor.brandMint)
            Text(verbatim: typed).foregroundStyle(Color.white)
            if cursor { Color.white.opacity(0.85).frame(width: 8, height: 16) }
        }
        .minimumScaleFactor(0.75)
        .lineLimit(1)
    }
}

/// Looks like a QR code; encodes nothing. Same pattern every time (fixed seed).
private struct DecorativeQR: View {
    var body: some View {
        Canvas { gc, area in
            let unit = Swift.min(area.width, area.height) / 21
            var state: UInt64 = 0x2545_F491_4F6C_DD1D
            func reserved(_ c: Int, _ r: Int) -> Bool { (c < 8 || c > 12) && r < 8 || c < 8 && r > 12 }
            for r in 0..<21 {
                for c in 0..<21 {
                    state ^= state << 13; state ^= state >> 7; state ^= state << 17
                    if reserved(c, r) || state % 100 >= 47 { continue }
                    let box = CGRect(x: CGFloat(c) * unit, y: CGFloat(r) * unit, width: unit, height: unit)
                    gc.fill(Path(box), with: .color(.white))
                }
            }
            let corners = [CGPoint.zero, CGPoint(x: 14, y: 0), CGPoint(x: 0, y: 14)]
            let rings: [(inset: CGFloat, round: CGFloat, paint: Color)] = [(0, 1.4, .white), (1, 1, consoleBlack), (2, 0.8, .white)]
            for corner in corners {
                for ring in rings {
                    let edge = (7 - ring.inset * 2) * unit
                    let box = CGRect(x: (corner.x + ring.inset) * unit, y: (corner.y + ring.inset) * unit, width: edge, height: edge)
                    gc.fill(Path(roundedRect: box, cornerRadius: ring.round * unit), with: .color(ring.paint))
                }
            }
        }
        .accessibilityHidden(true)
    }
}

private struct ExplainerSheet: View {
    @Environment(\.dismiss) private var close

    private static let points: [(title: LocalizedStringKey, body: LocalizedStringKey)] = [
        ("The connector makes an identity",
         "On first run, agy-connector creates a key pair that stays on your computer. Its public half is the computer's address."),
        ("The QR code carries the address and a one-time secret",
         "`agy-connector pair` shows it. Scanning gives your phone the computer's address and a secret that works once, for ten minutes. Nothing is sent anywhere while you scan."),
        ("Your phone finds the computer",
         "The phone looks up where the computer currently is through a public lookup network (the BitTorrent DHT), then opens a direct path between the two, even across different networks."),
        ("Encrypted and checked both ways",
         "The connection uses QUIC with TLS 1.3 and only completes if the computer proves it owns that key. The computer then shows a 6-digit number made from both devices' keys; typing it on the phone finishes pairing, and the phone gets its own token. A photo of the QR code alone pairs nothing."),
        ("If a direct path isn't possible",
         "AGY Remote asks before it passes the connection through a public relay. If you allow it, it stays end-to-end encrypted: the relay passes along bytes it can't read."),
    ]

    var body: some View {
        VStack(spacing: 0) {
            ScrollView { content }
                .scrollBounceBehavior(.basedOnSize)
                .scrollIndicators(.hidden)
            Button(action: { close() }) {
                Text("Got It")
                    .agyText(17, .semibold, AppColor.onAccentFill)
                    .frame(height: 54).frame(maxWidth: .infinity)
                    .background(AppColor.accentFill, in: .capsule)
            }
            .frame(maxWidth: 520)
            .buttonStyle(.plain)
            .padding(EdgeInsets(top: 0, leading: 28, bottom: 12, trailing: 28))
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: .zero) {
            Image(systemName: "point.3.connected.trianglepath.dotted")
                .face(32, .regular)
                .padding(EdgeInsets(top: 44, leading: 0, bottom: 18, trailing: 0))
            Text("How it works").tracking(-0.6).agyText(32, .bold).padding(EdgeInsets(top: 0, leading: 0, bottom: 8, trailing: 0))
            Text("AGY Remote has no server of its own and no account. Your phone connects straight to agy-connector on your computer.")
                .bodyCopy(16)
                .padding(.bottom, 30)
            VStack(alignment: .leading, spacing: 22) {
                ForEach(Array(Self.points.enumerated()), id: \.offset) { pair in
                    numbered(pair.offset + 1, pair.element.title, pair.element.body)
                }
            }
            Text("agy runs on your computer as usual. The connector only passes your messages to it and its replies back.")
                .bodyCopy(14)
                .padding(.top, 28)
        }
        .frame(maxWidth: 520, alignment: .leading)
        .padding(EdgeInsets(top: 0, leading: 28, bottom: 12, trailing: 28))
        .frame(maxWidth: .infinity)
    }

    private func numbered(_ n: Int, _ title: LocalizedStringKey, _ text: LocalizedStringKey) -> some View {
        let badge = Text(verbatim: String(n))
        return HStack(alignment: .top, spacing: 14) {
            badge
                .agyText(13, .semibold, mono: true, AppColor.primary)
                .agyBox(26, 26)
                .background(Circle().strokeBorder(AppColor.primary.opacity(0.5), lineWidth: 1))
            VStack(alignment: HorizontalAlignment.leading, spacing: 3) {
                Text(title).agyText(16, .semibold, AppColor.onSurface)
                Text(text).bodyCopy(14.5)
            }
        }
    }
}

// MARK: - 3 Ready

private struct StatusPage: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel

    private var machine: RemoteDevice? { viewModel.selectedDevice ?? viewModel.devices.first }
    private var link: RemoteWorkspaceViewModel.SetupConnection { viewModel.setupConnection }

    var body: some View {
        Centered {
            heading
                .padding(.bottom, 10)
                .animation(.smooth(duration: 0.45), value: link)
            Text("Your phone talks to this computer directly. Turn on notifications to hear back when the agent finishes or needs you.")
                .bodyCopy(15.5)
                .padding(.bottom, 24)
            card
        }
    }

    @ViewBuilder private var heading: some View {
        switch link {
        case .connected: Text("You're connected.").headline(38)
        case .failed: Text("Can't reach \(machine?.name ?? "")").headline(38)
        case .connecting: Text("Connecting…").headline(38)
        }
    }

    private var card: some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        return VStack(spacing: 0) {
            if let machine {
                machineRow(machine)
                AppColor.outlineVariant.opacity(0.6).frame(height: 1)
            }
            Toggle(isOn: Binding(get: { viewModel.notificationsEnabled }, set: { on in viewModel.requestNotifications(on) })) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Notifications").agyText(16, .medium)
                    Text("Only your phone can read them. You can change this later in Settings.")
                        .bodyCopy(13)
                }
            }
            .tint(AppColor.connectedGreen)
            .padding(16)
        }
        .background(shape.fill(AppColor.surfaceContainerLow.opacity(0.85)))
        .overlay { shape.stroke(AppColor.outlineVariant.opacity(0.6), lineWidth: 1) }
    }

    private var ringTone: Color {
        [RemoteWorkspaceViewModel.SetupConnection.connected: AppColor.connectedGreen,
         .failed: AppColor.errorRed][link] ?? AppColor.warningAmber
    }

    private func machineRow(_ m: RemoteDevice) -> some View {
        let failed = link == .failed
        return HStack(spacing: 14) {
            Image(systemName: m.deviceIconName)
                .face(17, .medium)
                .frame(width: 46, height: 46)
                .overlay { Circle().strokeBorder(ringTone, lineWidth: 1.8) }
                .animation(.smooth(duration: 0.45), value: link)
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: m.name).agyText(17, .semibold)
                Text(verbatim: summary(m, failed: failed)).bodyCopy(13.5).fixedSize(horizontal: false, vertical: true)
                if failed { failureBits }
            }
            Spacer(minLength: 0)
        }
        .padding(16)
    }

    @ViewBuilder private var failureBits: some View {
        if let message = viewModel.setupError {
            Text(verbatim: message).agyText(13.5, .regular, AppColor.errorRed).fixedSize(horizontal: false, vertical: true)
        }
        Button {
            Feel.press()
            viewModel.retrySetupConnection()
        } label: {
            Text("Retry").agyText(15, .semibold, AppColor.primary)
        }
        .buttonStyle(.plain)
        .padding(.top, 4)
    }

    private func summary(_ m: RemoteDevice, failed: Bool) -> String {
        var parts = [m.hardwareModel, m.agentVersion.isEmpty ? "" : "agy " + m.agentVersion].filter { !$0.isEmpty }
        if !failed {
            switch (m.status, m.latencyMs) {
            case (.connected, let ms) where ms > 0: parts.append(String(localized: "Online · \(ms) ms"))
            case (.connected, _): parts.append(String(localized: "Online"))
            default: parts.append(String(localized: "Connecting…"))
            }
        }
        return parts.joined(separator: " · ")
    }
}

// MARK: - 4 Good to know

private struct NotesPage: View {
    private static let rows: [(icon: String, title: LocalizedStringKey, detail: LocalizedStringKey)] = [
        ("terminal", "Chats open in the agy CLI are read-only here",
         "They update live. Close the chat in the CLI to reply from your phone."),
        ("exclamationmark.triangle", "Use a chat in one place at a time",
         "If the CLI and this app write to the same chat at once, agy keeps only one side's messages."),
        ("folder", "CLI chats need a folder",
         "The first time you reply to a chat started in the interactive CLI, you pick the folder it works in."),
        ("macwindow", "Antigravity app chats can't be opened",
         "Only chats from the agy CLI can be shown and continued."),
        ("power", "Keep your computer on",
         "agy-connector must be running. If a direct connection isn't possible, you're asked before a relay is used."),
    ]

    var body: some View {
        Centered {
            Text("Good to know.").headline(38).padding(.bottom, 24)
            VStack(alignment: .leading, spacing: 18) {
                ForEach(Self.rows.indices, id: \.self) { i in note(Self.rows[i]) }
            }
            Spacer(minLength: 40)
        }
    }

    private func note(_ row: (icon: String, title: LocalizedStringKey, detail: LocalizedStringKey)) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: row.icon)
                .agyText(16, .medium, AppColor.onSurfaceVariant)
                .frame(width: 24)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 3) {
                Text(row.title).agyText(15.5, .semibold, AppColor.onSurface)
                Text(row.detail).bodyCopy(14)
            }
        }
    }
}
