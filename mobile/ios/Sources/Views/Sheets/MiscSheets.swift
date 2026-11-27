@preconcurrency import AVFoundation
import SwiftUI
import UIKit

// MARK: - Computer picker

struct ComputerPickerSheetView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @State private var pasteField = ""
    @State private var scannerOpen = false
    @State private var pendingUnpair: RemoteDevice?
    @Environment(\.dismiss) private var closeSheet

    var body: some View {
        NavigationStack {
            List {
                if viewModel.devices.count > 0 { hostsSection }
                pairSection
            }
            .refreshable { await viewModel.refreshActiveConnector() }
            .inlineTitle("Connectors")
            .doneToolbar { closeSheet() }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { refreshButton }
            }
            .alert(unpairTitle, isPresented: unpairAsked, presenting: pendingUnpair, actions: unpairActions) { _ in
                Text("You'll need to scan its pairing code again to reconnect.")
            }
            .sheet(isPresented: $scannerOpen) {
                QRCodeScannerSheet { scanned in pair(scanned) }
            }
        }
    }

    private var refreshButton: some View {
        let vm = viewModel
        return Button(action: { Task { await vm.refreshActiveConnector() } }) {
            Image.sf("arrow.clockwise")
        }
        .accessibilityLabel("Refresh")
    }

    private var hostsSection: some View {
        Section(content: {
            ForEach(viewModel.devices) { device in
                let isCurrent = viewModel.selectedDevice?.id == device.id
                Button(action: { choose(device) }) { HostRow(device: device, current: isCurrent) }
                    .plainTap()
                    .swipeActions(edge: HorizontalEdge.trailing) {
                        Button("Unpair", systemImage: "trash", role: .destructive) { pendingUnpair = device }
                    }
            }
        }, header: { Text.caption("Connected Hosts") })
    }

    private var pairSection: some View {
        Section(content: {
            Button(action: { scannerOpen = true }) {
                Label("Scan QR Code", systemImage: "qrcode.viewfinder")
            }
            Across {
                TextField(text: $pasteField) { Text(verbatim: "agy-rem://pair?…") }
                    .autocorrectionDisabled(true)
                    .textInputAutocapitalization(TextInputAutocapitalization.never)
                    .fontSized(13.5, design: .monospaced)
                    .submitLabel(.go)
                    .onSubmit { if !pasteField.isEmpty { pasteOrPair() } }
                Button(action: pasteOrPair) {
                    Text(pasteField.isEmpty ? "Paste" : "Pair").fontSized(13, .semibold)
                }
                .buttonStyle(BorderlessButtonStyle())
            }
        }, header: { Text.caption("Pair Connector") })
    }

    private var unpairTitle: Text {
        Text("Unpair \(pendingUnpair?.name ?? "")?")
    }

    private var unpairAsked: Binding<Bool> {
        Binding(get: { pendingUnpair != nil }) { stillShown in
            if stillShown == false { pendingUnpair = nil }
        }
    }

    @ViewBuilder private func unpairActions(_ device: RemoteDevice) -> some View {
        Button(role: .cancel, action: {}) { Text("Cancel") }
        Button(role: .destructive, action: { viewModel.removeDevice(device) }) { Text("Unpair") }
    }

    private func choose(_ device: RemoteDevice) {
        viewModel.selectDevice(device)
        closeSheet()
    }

    private func pasteOrPair() {
        guard !pasteField.isEmpty else {
            pasteField = UIPasteboard.general.string ?? ""
            return
        }
        let ticket = pasteField.trimmingCharacters(in: .whitespacesAndNewlines)
        pasteField = ""
        pair(ticket)
    }

    private func pair(_ ticket: String) {
        let vm = viewModel
        Task { await vm.pairConnector(rawTicket: ticket) }
    }
}

private struct HostRow: View {
    let device: RemoteDevice
    let current: Bool

    /// "<hardware> · agy <version>", skipping missing parts.
    private var specs: String {
        let hardware = device.hardwareModel
        let agent = device.agentVersion.isEmpty ? "" : "agy " + device.agentVersion
        return [hardware, agent].filter { $0.count > 0 }.joined(separator: " · ")
    }

    /// Transport and latency when online, else a shortened endpoint id.
    private var addressLine: String {
        if device.status == .connected { return device.transportLabel + " · " + String(device.latencyMs) + "ms" }
        let raw = device.id
        return raw.count <= 16 ? raw : "\(raw.prefix(8))…\(raw.suffix(6))"
    }

    var body: some View {
        Across(14) {
            Image.sf(device.deviceIconName)
                .fontSized(20)
                .ink(current ? AppColor.primary : AppColor.onSurfaceVariant)
                .frame(minWidth: 32, maxWidth: 32)
            Lead(3) {
                Across(6) {
                    Text(verbatim: device.name).typeset(16, .semibold, ink: AppColor.onSurface)
                    if device.isDemo { DemoBadge() }
                    Circle().fill(device.status.tint).frame(width: 7, height: 7)
                }
                if specs.isEmpty == false {
                    Text(verbatim: specs).typeset(13, ink: AppColor.onSurfaceVariant)
                }
                Text(verbatim: addressLine)
                    .truncationMode(.middle)
                    .oneLine()
                    .typeset(11.5, design: .monospaced, ink: AppColor.onSurfaceMuted)
            }
            Spacer()
            if current {
                Image.sf("checkmark.circle.fill").typeset(20, ink: AppColor.primary)
            }
        }
        .gap(.vertical, 4)
        .contentShape(.rect)
    }
}

// MARK: - Search

struct ChatSearchSheetView: View {
    @Environment(\.dismiss) private var closeSheet
    @Bindable var viewModel: RemoteWorkspaceViewModel

    var body: some View {
        let hits = viewModel.globalSearchResults
        NavigationStack {
            List {
                ForEach(hits) { hit in
                    Button(action: { open(hit) }) { label(for: hit) }
                }
                if hits.isEmpty {
                    ContentUnavailableView {
                        Label("No matches", systemImage: "magnifyingglass")
                    }
                    .listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(Visibility.hidden)
            .listStyle(InsetGroupedListStyle())
            .surfaceBackdrop()
            .inlineTitle("Search")
            .drawerSearch($viewModel.globalSearchQuery)
            .doneToolbar { closeSheet() }
        }
    }

    @ViewBuilder private func label(for hit: GlobalSearchResult) -> some View {
        switch hit {
        case let .message(_, message):
            Text(verbatim: message.text).lineLimit(2).typeset(13.5, ink: AppColor.onSurface)
        case let .model(model):
            SearchLine(symbol: "sparkles", title: model.name, detail: model.id, monospacedDetail: true)
        case let .folder(folder):
            SearchLine(symbol: "folder", title: folder.name, detail: folder.path)
        case let .chat(chat, folderName):
            SearchLine(symbol: "bubble.left",
                       title: chat.title.isEmpty ? String(localized: "Untitled chat") : chat.title,
                       detail: folderName)
        }
    }

    private func open(_ hit: GlobalSearchResult) {
        let vm = viewModel
        if case let .model(model) = hit {
            vm.selectBaseModel(model)
            return closeSheet()
        }
        vm.hideSidebar()
        closeSheet()
        switch hit {
        case let .chat(chat, _): vm.openChatAndReveal(chat.id)
        case let .message(chatId, _): vm.openChatAndReveal(chatId)
        case let .folder(folder): vm.selectFolder(folder)
        case .model: break
        }
    }
}

private struct SearchLine: View {
    let symbol: String
    let title: String
    let detail: String
    var monospacedDetail = false

    var body: some View {
        Across(12) {
            Image.sf(symbol).ink(AppColor.primary).frame(minWidth: 24, maxWidth: 24)
            Lead(2) {
                Text(verbatim: title).oneLine().typeset(15, .medium, ink: AppColor.onSurface)
                if detail.isEmpty == false {
                    Text(verbatim: detail)
                        .oneLine()
                        .typeset(12, design: monospacedDetail ? .monospaced : .default, ink: AppColor.onSurfaceVariant)
                }
            }
            Spacer()
        }
    }
}

// MARK: - Folder picker

struct FolderPickerSheetView: View {
    @Environment(\.dismiss) private var closeSheet
    @Bindable var viewModel: RemoteWorkspaceViewModel

    var body: some View {
        NavigationStack {
            List {
                Section(content: {
                    ForEach(viewModel.folders) { folder in
                        Button(action: { pick(folder) }) {
                            Lead(2) {
                                Text(verbatim: folder.name).ink(AppColor.onSurface)
                                Text(verbatim: folder.path)
                                    .truncationMode(.middle)
                                    .oneLine()
                                    .font(.footnote)
                                    .ink(AppColor.onSurfaceVariant)
                            }
                        }
                        .plainTap()
                    }
                }, footer: {
                    Text.caption("This chat was started in the agy CLI, which doesn't tell other apps its folder. The agent will read and edit files in the folder you choose, and this chat will stay there.")
                })
            }
            .inlineTitle("Choose a Folder")
            .closeToolbar {
                viewModel.folderPickerPending = nil
                closeSheet()
            }
        }
    }

    private func pick(_ folder: WorkspaceFolder) {
        // Must run before dismissing: dismissal clears the pending send.
        viewModel.continuePending(in: folder)
        closeSheet()
    }
}

// MARK: - Usage rows

struct UsageBarRow: View {
    var title: String
    var value: String
    var fraction: Double
    var tint: Color
    var caption: String? = nil
    static let barHeight: CGFloat = 4

    var body: some View {
        Lead(7) {
            Across(top: false) {
                Text(verbatim: title).typeset(15, ink: AppColor.onSurface)
                Spacer()
                Text(verbatim: value)
                    .monospacedDigit()
                    .typeset(15, .medium, ink: AppColor.onSurface)
            }
            .alignmentGuide(.leading) { $0[.leading] }
            UsageTrack(fraction: fraction, tint: tint)
            if let caption, caption.isEmpty == false {
                Text(verbatim: caption).typeset(12.5, ink: AppColor.onSurfaceMuted)
            }
        }
        .gap(.vertical, 2)
    }
}

/// A 4 pt capsule track with a leading fill; the fill is never thinner than 4 pt.
private struct UsageTrack: View {
    let fraction: Double
    let tint: Color

    var body: some View {
        let clamped = fraction.isFinite ? Swift.min(1, Swift.max(0, fraction)) : 0
        Capsule()
            .fill(AppColor.onSurface.opacity(0.08))
            .overlay(alignment: .leading) {
                GeometryReader { box in
                    tint.clipShape(Capsule()).frame(width: Swift.max(4, box.size.width * clamped))
                }
            }
            .frame(height: UsageBarRow.barHeight)
    }
}

struct QuotaLimitRow: View {
    var limit: QuotaLimit

    var body: some View {
        let left = limit.remaining
        let color: Color = left < 10 ? AppColor.errorRed : (left < 25 ? AppColor.warningAmber : AppColor.primary)
        let heading = limit.group.isEmpty ? limit.label : limit.group + " · " + limit.label
        UsageBarRow(title: heading,
                    value: Self.percent(left),
                    fraction: left / 100,
                    tint: color,
                    caption: limit.resets.map(Self.resetText))
    }

    private static func percent(_ value: Double) -> String {
        let style = FloatingPointFormatStyle<Double>.Percent().precision(.fractionLength(0))
        return (value / 100).formatted(style)
    }

    private static func resetText(_ date: Date) -> String {
        let remaining = date.timeIntervalSince(Date())
        let when: String
        if remaining > 0 {
            let units = DateComponentsFormatter()
            units.allowedUnits = [.day, .hour, .minute]
            units.unitsStyle = .abbreviated
            units.maximumUnitCount = 2
            let text = units.string(from: Swift.max(remaining, 60)) ?? ""
            when = String(localized: "in \(text)", comment: "When a limit resets, e.g. 'in 5d 3h'")
        } else {
            when = String(localized: "now")
        }
        return String(localized: "Resets \(when)")
    }
}

// MARK: - QR scanner

struct QRCodeScannerSheet: View {
    @Environment(\.dismiss) private var closeSheet
    @State private var failure: String?
    let onScan: (String) -> Void

    init(onScan: @escaping (String) -> Void) {
        self.onScan = onScan
    }

    var body: some View {
        NavigationStack {
            Color.black
                .ignoresSafeArea()
                .overlay { stage }
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(action: { closeSheet() }) { Text("Cancel") }
                    }
                }
                .inlineTitle("Scan PC Connector")
                // The backdrop is always black, so keep text and bar light-on-dark
                // whatever theme the rest of the app uses.
                .toolbarColorScheme(.dark, for: .navigationBar)
        }
        .environment(\.colorScheme, .dark)
    }

    @ViewBuilder private var stage: some View {
        if failure == nil {
            ScannerSurface(found: finish, failed: { failure = $0 })
                .ignoresSafeArea()
                .overlay { viewfinder }
        } else {
            let reason = Text(verbatim: failure ?? "")
            ContentUnavailableView("Camera Unavailable", systemImage: "qrcode.viewfinder", description: reason)
        }
    }

    private var viewfinder: some View {
        Down(0) {
            Spacer(minLength: 0)
            Color.clear
                .frame(width: 240, height: 240)
                .overlay {
                    let corner = RoundedRectangle(cornerRadius: 24, style: .continuous)
                    corner.strokeBorder(AppColor.accentRing, lineWidth: 2.2)
                }
            Spacer(minLength: 0)
            Text("Point at the QR code printed by `agy-connector pair`")
                .typeset(13.5, .medium, ink: Color.white)
                .inset(18, 10)
                .background(Material.ultraThin, in: Capsule())
                .gap(.bottom, 36)
        }
    }

    private func finish(_ code: String) {
        Haptics.success()
        onScan(code)
        closeSheet()
    }
}

/// UIView whose backing layer is the camera preview.
private final class PreviewHost: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

private struct ScannerSurface: UIViewRepresentable {
    let found: (String) -> Void
    let failed: (String) -> Void

    func makeCoordinator() -> ScanEngine { ScanEngine() }

    func makeUIView(context: Context) -> PreviewHost {
        let host = PreviewHost()
        host.backgroundColor = UIColor.black
        host.preview.videoGravity = .resizeAspectFill
        host.preview.session = context.coordinator.session
        context.coordinator.onFound = found
        context.coordinator.onFailure = failed
        context.coordinator.begin()
        return host
    }

    func updateUIView(_ host: PreviewHost, context: Context) {
        context.coordinator.onFound = found
        context.coordinator.onFailure = failed
    }

    static func dismantleUIView(_ host: PreviewHost, coordinator: ScanEngine) {
        coordinator.halt()
    }
}

/// Owns the capture session. Configuration, start and stop all happen on `work`.
private final class ScanEngine: NSObject, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let work = DispatchQueue(label: "moe.n4tsu.agyremote.qr-scan")
    @MainActor var onFound: ((String) -> Void)?
    @MainActor var onFailure: ((String) -> Void)?
    @MainActor private var delivered = false

    @MainActor func begin() {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        if status == .authorized { return startCapture() }
        guard status == .notDetermined else { return refuse() }
        AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
            Task { @MainActor in allowed ? self?.startCapture() : self?.refuse() }
        }
    }

    func halt() {
        let s = session
        work.async { if s.isRunning { s.stopRunning() } }
    }

    @MainActor private func refuse() {
        post(String(localized: "Camera access is turned off for AGY Remote. Allow it in Settings, or paste the pairing code instead."))
    }

    /// Never during a view update: always hops onto the next main-queue turn.
    private func post(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated { self?.onFailure?(message) }
        }
    }

    @MainActor private func startCapture() {
        work.async { [self] in
            if let problem = configure() { return post(problem) }
            session.startRunning()
        }
    }

    private func configure() -> String? {
        guard let camera = AVCaptureDevice.default(for: .video) else {
            return String(localized: "No camera found on this device. Paste the agy-rem://pair code instead.")
        }
        let input: AVCaptureDeviceInput
        do { input = try AVCaptureDeviceInput(device: camera) } catch { return error.localizedDescription }
        let reader = AVCaptureMetadataOutput()
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        guard session.canAddInput(input), session.canAddOutput(reader) else {
            return String(localized: "The camera is in use by something else. Try again in a moment.")
        }
        session.addInput(input)
        session.addOutput(reader)
        reader.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        reader.metadataObjectTypes = [AVMetadataObject.ObjectType.qr]
        return nil
    }

    func metadataOutput(_ captureOutput: AVCaptureMetadataOutput,
                        didOutput objects: [AVMetadataObject],
                        from link: AVCaptureConnection) {
        let codes = objects.compactMap { ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }
        guard let first = codes.first(where: { !$0.isEmpty }) else { return }
        MainActor.assumeIsolated {
            if delivered { return }
            delivered = true
            halt()
            onFound?(first)
        }
    }
}

// MARK: - Sheet styling vocabulary (shared by the sheet files)

extension View {
    /// System font at `size`, tinted with `ink`.
    func typeset(_ size: CGFloat, _ weight: Font.Weight = .regular, design: Font.Design = .default, ink: Color) -> some View {
        fontSized(size, weight, design: design).foregroundStyle(ink)
    }

    func fontSized(_ size: CGFloat, _ weight: Font.Weight = .regular, design: Font.Design = .default) -> some View {
        font(Font.system(size: size, weight: weight, design: design))
    }

    func inset(_ horizontal: CGFloat, _ vertical: CGFloat) -> some View {
        padding(EdgeInsets(top: vertical, leading: horizontal, bottom: vertical, trailing: horizontal))
    }

    func inlineTitle(_ key: LocalizedStringKey) -> some View {
        navigationBarTitleDisplayMode(.inline).navigationTitle(key)
    }

    func inlineTitle(_ text: Text) -> some View {
        navigationBarTitleDisplayMode(.inline).navigationTitle(text)
    }

    /// Trailing text button "Done".
    func doneToolbar(_ action: @escaping () -> Void) -> some View {
        toolbar { ToolbarItem(placement: ToolbarItemPlacement.topBarTrailing) { Button("Done", action: action) } }
    }

    /// Leading close button (`xmark`), spoken as "Close".
    func closeToolbar(_ action: @escaping () -> Void) -> some View {
        toolbar {
            ToolbarItem(placement: ToolbarItemPlacement.topBarLeading) {
                Button(action: action) { Label("Close", systemImage: "xmark").labelStyle(.iconOnly) }
            }
        }
    }

    func stretch() -> some View { frame(maxWidth: CGFloat.infinity) }
    func gap(_ edges: Edge.Set, _ amount: CGFloat) -> some View { padding(edges, amount) }
    func ink(_ color: Color) -> some View { foregroundStyle(color) }
    func oneLine() -> some View { lineLimit(1) }
    func plainTap() -> some View { buttonStyle(PlainButtonStyle()) }

    /// The `surface` colour behind the whole sheet, under the safe areas too.
    func surfaceBackdrop() -> some View { background(AppColor.surface, ignoresSafeAreaEdges: .all) }

    /// Search field in the navigation-bar drawer, always shown.
    func drawerSearch(_ text: Binding<String>, prompt: LocalizedStringKey? = nil) -> some View {
        searchable(text: text, placement: .navigationBarDrawer(displayMode: .always), prompt: prompt.map { Text($0) })
    }
}

extension Image {
    static func sf(_ name: String) -> Image { Image(systemName: name) }
}

extension Text {
    /// Section header or footer text.
    static func caption(_ key: LocalizedStringKey) -> Text { Text(key) }
}

/// Vertical stack with leading alignment.
struct Lead<Content: View>: View {
    private let gap: CGFloat
    private let items: Content
    init(_ spacing: CGFloat, @ViewBuilder content: () -> Content) {
        gap = spacing
        items = content()
    }
    var body: some View { VStack(alignment: HorizontalAlignment.leading, spacing: gap) { items } }
}

/// Horizontal stack, centred or top-aligned.
struct Across<Content: View>: View {
    private let gap: CGFloat?
    private let top: Bool
    private let items: Content
    init(_ spacing: CGFloat? = nil, top: Bool = false, @ViewBuilder content: () -> Content) {
        gap = spacing
        self.top = top
        items = content()
    }
    var body: some View { HStack(alignment: top ? .top : .center, spacing: gap) { items } }
}

/// Vertical stack, centred.
struct Down<Content: View>: View {
    private let gap: CGFloat
    private let items: Content
    init(_ spacing: CGFloat, @ViewBuilder content: () -> Content) {
        gap = spacing
        items = content()
    }
    var body: some View { VStack(spacing: gap) { items } }
}

// MARK: - Pairing confirmation

/// The last step of pairing: the number to type into `agy-connector pair` on the computer.
/// Shown for every way a pairing starts (QR, pasted code, link), so nothing pairs without the user.
struct PairNumberSheet: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel

    private var pending: RemoteWorkspaceViewModel.PendingPairing? { viewModel.pendingPairing }

    var body: some View {
        NavigationStack {
            Form {
                if let p = pending {
                    Section {
                        Lead(4) {
                            Text("Pair with this computer?").fontSized(22, .bold)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(.bottom, 6)
                            Text(verbatim: p.computer).fontSized(17, .semibold)
                            Text(verbatim: shortId(p.ticket.endpointId)).fontSized(12.5, design: .monospaced)
                                .foregroundStyle(.secondary)
                        }
                    } footer: {
                        Text("Type this number into agy-connector pair on the computer. Only someone at that computer's keyboard can finish pairing.")
                    }
                    let number = p.number
                    Section {
                        Text(verbatim: "\(number.prefix(3)) \(number.suffix(3))")
                            .fontSized(34, .semibold, design: .monospaced)
                            .frame(maxWidth: .infinity)
                            .textSelection(.disabled)
                            .accessibilityLabel(Text(verbatim: number.map(String.init).joined(separator: " ")))
                    } footer: {
                        if let e = p.error {
                            Text(verbatim: e).foregroundStyle(.red)
                        } else {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("Waiting for the number to be typed on the computer…")
                            }
                        }
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { viewModel.cancelPairing() } label: { Text("Cancel") }
                }
            }
        }
    }

    private func shortId(_ id: String) -> String {
        id.count <= 16 ? id : "\(id.prefix(8))…\(id.suffix(6))"
    }
}

// MARK: - File viewer

/// A text file an agent reply linked to (an artifact, a source file), read from the computer.
struct FileViewerSheet: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @Environment(\.dismiss) private var closeSheet

    var body: some View {
        NavigationStack {
            Group {
                if let f = viewModel.fileView {
                    if let error = f.error {
                        ContentUnavailableView {
                            Label { Text(verbatim: f.name) } icon: { Image(systemName: "doc.questionmark") }
                        } description: { Text(verbatim: error) }
                    } else if let text = f.text {
                        FileLines(text: text, focus: f.line, truncated: f.truncated)
                    } else {
                        ProgressView()
                    }
                }
            }
            .navigationTitle(Text(verbatim: viewModel.fileView?.name ?? ""))
            .navigationBarTitleDisplayMode(.inline)
            .doneToolbar { closeSheet() }
            .toolbar {
                if let text = viewModel.fileView?.text {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { UIPasteboard.general.string = text } label: { Image(systemName: "doc.on.doc") }
                            .accessibilityLabel(Text("Copy"))
                    }
                }
            }
        }
    }
}

private struct FileLines: View {
    let text: String
    let focus: Int?
    let truncated: Bool

    var body: some View {
        let lines = text.components(separatedBy: "\n")
        let width = String(lines.count).count
        ScrollViewReader { proxy in
            ScrollView([.vertical, .horizontal]) {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(lines.enumerated()), id: \.offset) { n, line in
                        HStack(alignment: .top, spacing: 12) {
                            Text(verbatim: String(n + 1).leftPadded(to: width))
                                .foregroundStyle(.tertiary)
                            Text(verbatim: line.isEmpty ? " " : line)
                        }
                        .fontSized(12.5, design: .monospaced)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 1)
                        .background(n + 1 == focus ? AppColor.primary.opacity(0.14) : .clear)
                        .id(n + 1)
                    }
                    if truncated {
                        Text("The file is longer; only the first 1 MB is shown.")
                            .fontSized(12.5).foregroundStyle(.secondary).padding(14)
                    }
                }
                .padding(.vertical, 10)
                .textSelection(.enabled)
            }
            .onAppear { if let focus { proxy.scrollTo(focus, anchor: .center) } }
        }
    }
}

private extension String {
    func leftPadded(to width: Int) -> String {
        count >= width ? self : String(repeating: " ", count: width - count) + self
    }
}
