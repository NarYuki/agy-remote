import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

private let tileShape = RoundedRectangle(cornerRadius: 20, style: .continuous)

/// File-type symbols by lowercase extension.
private let fileIcons: [(symbol: String, extensions: Set<String>)] = [
    ("swift", "swift"),
    ("photo", "png jpg jpeg gif heic svg webp"),
    ("curlybraces", "json yml yaml toml plist"),
    ("doc.plaintext", "md txt rtf"),
].map { pair in (pair.0, Set(pair.1.split(separator: " ").map(String.init))) }

/// Dimmed full-screen veil with a glass "Attaching…" card.
private struct AttachingVeil: View {
    var body: some View {
        ProgressView("Attaching…")
            .inset(20, 14)
            .glassEffect(Glass.regular, in: .rect(cornerRadius: 16, style: .continuous))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.opacity(0.35).ignoresSafeArea())
    }
}

struct AddSheetView: View {
    @Environment(\.dismiss) private var closeSheet
    @Bindable var viewModel: RemoteWorkspaceViewModel

    private enum FollowUp { case files, commands }

    @State private var cameraOpen = false
    @State private var tookPhoto = false
    @State private var photosOpen = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var importerOpen = false
    @State private var attaching = false
    @State private var followUp: FollowUp?

    var body: some View {
        NavigationStack {
            ScrollView {
                Lead(22) {
                    tiles
                    contextSection
                    if !viewModel.commands.isEmpty { quickCommands }
                }
                .gap(.horizontal, 18)
                .gap(.top, 8)
                .gap(.bottom, 28)
            }
            .surfaceBackdrop()
            .scrollIndicators(.hidden)
            .inlineTitle("Add to Agy")
            .doneToolbar { closeSheet() }
        }
        .overlay { if attaching { AttachingVeil() } }
        .fullScreenCover(isPresented: $cameraOpen, onDismiss: {
            if tookPhoto {
                tookPhoto = false
                closeSheet()
            }
        }) {
            CameraPicker { image in
                let stamp = Int(Date().timeIntervalSince1970)
                viewModel.addImage(image, name: "camera_\(stamp).jpg")
                tookPhoto = true
            }
            .ignoresSafeArea()
        }
        .photosPicker(isPresented: $photosOpen, selection: $photoItems, maxSelectionCount: 8, matching: .images)
        .onChange(of: photoItems) { _, items in
            guard !items.isEmpty else { return }
            Task { await attachPhotos(items) }
        }
        .fileImporter(isPresented: $importerOpen, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let picked) where picked.count > 0:
                Task { await attachFiles(picked) }
            default:
                break
            }
        }
        .onDisappear {
            switch followUp {
            case .files: viewModel.showWorkspaceFilePicker = true
            case .commands: viewModel.showCommandPicker = true
            case nil: break
            }
            followUp = nil
        }
    }

    // MARK: Sections

    private var tiles: some View {
        GlassEffectContainer(spacing: 10) {
            Across(10) {
                tile("camera.fill", "Camera", AppColor.brandSky) {
                    let hasCamera = UIImagePickerController.isSourceTypeAvailable(.camera)
                    if !hasCamera {
                        return viewModel.showToast(String(localized: "Camera is not available on this device"))
                    }
                    cameraOpen = true
                }
                tile("photo.on.rectangle.angled", "Photos", AppColor.brandViolet) { photosOpen = true }
                tile("folder.fill", "Files", AppColor.connectedGreen) { importerOpen = true }
                tile("doc.on.clipboard.fill", "Paste", AppColor.warningAmber) {
                    viewModel.pasteFromClipboard()
                    closeSheet()
                }
            }
        }
    }

    private func tile(_ symbol: String, _ title: LocalizedStringKey, _ tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            TileFace(symbol: symbol, title: title, tint: tint)
        }
        .plainTap()
        .glassEffect(Glass.regular.interactive(), in: tileShape)
    }

    private var contextSection: some View {
        Lead(10) {
            sectionTitle("Agy Context & Tools")
            Down(8) {
                let subtitle: String = {
                    if let folder = viewModel.currentFolder {
                        return String(localized: "Attach files from \(folder.name)")
                    }
                    return String(localized: "Attach files from your computer")
                }()
                contextRow(symbol: "at", size: 16.5, tint: AppColor.primary,
                           title: "Workspace File Context", subtitle: subtitle) {
                    followUp = .files
                    closeSheet()
                }
                if !viewModel.commands.isEmpty {
                    contextRow(symbol: "slash.circle.fill", size: 17.5, tint: AppColor.brandViolet,
                               title: "Slash Commands & Skills",
                               subtitle: String(localized: "\(viewModel.commands.count) commands & skills from connected agy")) {
                        followUp = .commands
                        closeSheet()
                    }
                }
            }
        }
    }

    private func contextRow(symbol: String, size: CGFloat, tint: Color, title: LocalizedStringKey,
                            subtitle: String, action: @escaping () -> Void) -> some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        return Button(action: action) {
            Across(14) {
                Image.sf(symbol)
                    .font(.system(size: size, weight: .semibold))
                    .foregroundStyle(tint)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(tint.opacity(0.16)))
                Lead(2) {
                    Text(title)
                        .typeset(15.5, .medium, ink: AppColor.onSurface)
                    Text(verbatim: subtitle)
                        .typeset(12.5, ink: AppColor.onSurfaceVariant)
                        .oneLine()
                }
                Spacer(minLength: 4)
                Image.sf("chevron.right")
                    .typeset(12.5, .semibold, ink: AppColor.onSurfaceMuted)
            }
            .inset(14, 11)
            .background(shape.fill(AppColor.surfaceContainerLow))
            .contentShape(shape)
        }
        .plainTap()
    }

    private var quickCommands: some View {
        Lead(10) {
            sectionTitle("Quick Commands")
            ScrollView(.horizontal) {
                Across(8) {
                    ForEach(viewModel.commands.prefix(9)) { command in
                        Button {
                            viewModel.insertSlashCommand(command)
                            closeSheet()
                        } label: {
                            Text(verbatim: "/" + command.name)
                                .typeset(13.5, .medium, design: .monospaced, ink: AppColor.onSurface)
                                .inset(13, 8)
                                .background(AppColor.surfaceContainerHigh, in: Capsule(style: .circular))
                        }
                        .plainTap()
                    }
                }
            }
            .scrollIndicators(.hidden)
        }
    }

    private func sectionTitle(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .typeset(13.5, .medium, ink: AppColor.onSurfaceVariant)
            .gap(.horizontal, 4)
    }

    // MARK: Importing

    private func attachPhotos(_ items: [PhotosPickerItem]) async {
        attaching = true
        for (index, item) in items.enumerated() {
            let loaded: Data? = try? await item.loadTransferable(type: Data.self)
            guard let data = loaded else { continue }
            let decoded = await Task.detached { UIImage(data: data) }.value
            guard let image = decoded,
                  var attachment = MessageAttachment.image(image, name: "photo_\(index + 1).jpg") else { continue }
            attachment.byteCount = data.count
            viewModel.addAttachment(attachment)
        }
        attaching = false
        photoItems = []
        closeSheet()
    }

    private enum ImportOutcome: Sendable {
        case skipped
        case tooLarge(String)
        case attachment(MessageAttachment)
    }

    private func attachFiles(_ urls: [URL]) async {
        attaching = true
        for url in urls {
            let outcome = await Task.detached { Self.importFile(url) }.value
            switch outcome {
            case .skipped: break
            case .tooLarge(let name): viewModel.showToast(String(localized: "\(name) exceeds 10 MB limit"))
            case .attachment(let a): viewModel.addAttachment(a)
            }
        }
        attaching = false
        closeSheet()
    }

    nonisolated private static func importFile(_ url: URL) -> ImportOutcome {
        let opened = url.startAccessingSecurityScopedResource()
        defer {
            if opened { url.stopAccessingSecurityScopedResource() }
        }

        let name = url.lastPathComponent
        let ext = url.pathExtension
        let fileType = UTType(filenameExtension: ext)
        let isImage = fileType?.conforms(to: .image) ?? false
        let megabyte = 1024 * 1024
        let generalLimit = 10 * megabyte
        let cap = isImage ? 40 * megabyte : generalLimit

        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let size = (attrs[.size] as? NSNumber)?.intValue, size > cap {
            return .tooLarge(name)
        }
        guard let data = try? Data(contentsOf: url), data.count > 0 else { return ImportOutcome.skipped }

        if isImage, let picture = UIImage(data: data), var a = MessageAttachment.image(picture, name: name) {
            a.byteCount = data.count
            return .attachment(a)
        }
        if data.count > generalLimit { return .tooLarge(name) }
        var file = MessageAttachment(kind: .file, name: name, payload: data,
                                     mimeType: fileType?.preferredMIMEType ?? "application/octet-stream")
        file.byteCount = data.count
        return .attachment(file)
    }
}

private struct TileFace: View {
    let symbol: String
    let title: LocalizedStringKey
    var tint: Color = AppColor.primary

    var body: some View {
        Down(9) {
            Image.sf(symbol)
                .typeset(21, .medium, ink: tint)
                .frame(height: 26)
            Text(title)
                .typeset(12.5, .medium, ink: AppColor.onSurface)
                .oneLine()
        }
        .frame(height: 78)
        .stretch()
        .contentShape(tileShape)
    }
}

// MARK: - Workspace files

struct WorkspaceFilePicker: View {
    @Environment(\.dismiss) private var closeSheet
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @State private var query = ""
    @State private var results: [WorkspaceFile] = []
    @State private var loading = false

    private var heading: String {
        if let folder = viewModel.currentFolder { return "@ " + folder.name }
        return String(localized: "Workspace Files")
    }

    var body: some View {
        NavigationStack {
            content
                .background(AppColor.surface.ignoresSafeArea())
                .inlineTitle(Text(verbatim: heading))
                .drawerSearch($query, prompt: "Filter files…")
                .doneToolbar { closeSheet() }
                .task(id: query) { await runSearch(query) }
        }
    }

    @ViewBuilder private var content: some View {
        if loading && results.isEmpty {
            Down(12) {
                ProgressView()
                Group {
                    if let device = viewModel.selectedDevice {
                        Text("Loading workspace files from \(device.name)…")
                    } else {
                        Text("Loading workspace files…")
                    }
                }
                .font(AppFont.bodyMedium)
                .ink(AppColor.onSurfaceVariant)
            }
            .frame(maxHeight: .infinity)
            .stretch()
        } else if results.isEmpty {
            ContentUnavailableView {
                Label("No matching files", systemImage: "doc.text.magnifyingglass")
            }
        } else {
            List {
                ForEach(results) { file in
                    Button(action: { toggle(file) }) { fileRow(file) }
                }
            }
            .scrollContentBackground(Visibility.hidden)
            .listStyle(PlainListStyle())
        }
    }

    private func fileRow(_ file: WorkspaceFile) -> some View {
        Across(12) {
            Image.sf(Self.icon(for: file.path))
                .typeset(16, ink: AppColor.primary)
                .frame(width: 24)
            Lead(2) {
                Text(verbatim: (file.path as NSString).lastPathComponent)
                    .typeset(15, .medium, ink: AppColor.onSurface)
                    .oneLine()
                Text(verbatim: file.path)
                    .typeset(12, design: .monospaced, ink: AppColor.onSurfaceVariant)
                    .oneLine()
                    .truncationMode(.head)
            }
            Spacer(minLength: 8)
            if isAttached(file) {
                Image.sf("checkmark.circle.fill")
                    .typeset(19, ink: AppColor.connectedGreen)
            }
        }
    }

    private func isAttached(_ file: WorkspaceFile) -> Bool {
        viewModel.draftAttachments.contains { $0.workspacePath == file.path }
    }

    private func toggle(_ file: WorkspaceFile) {
        if let existing = viewModel.draftAttachments.first(where: { $0.workspacePath == file.path }) {
            viewModel.removeAttachment(existing)
        } else {
            viewModel.addWorkspaceFile(file)
        }
    }

    private func runSearch(_ text: String) async {
        if !text.isEmpty {
            try? await Task.sleep(for: .milliseconds(160))
            if Task.isCancelled { return }
        }
        loading = true
        let found = await viewModel.searchFiles(text)
        guard !Task.isCancelled else { return }
        results = found
        loading = false
    }

    private static func icon(for path: String) -> String {
        let ext = (path as NSString).pathExtension.lowercased()
        return fileIcons.first { $0.extensions.contains(ext) }?.symbol ?? "doc.text"
    }
}

// MARK: - Slash commands

struct SlashCommandPicker: View {
    @Environment(\.dismiss) private var closeSheet
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @State private var query = ""

    private var matching: [SlashCommand] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if needle.isEmpty { return viewModel.commands }
        return viewModel.commands.filter {
            $0.name.localizedCaseInsensitiveContains(needle) || $0.description.localizedCaseInsensitiveContains(needle)
        }
    }

    var body: some View {
        NavigationStack {
            List(matching) { command in
                Button {
                    viewModel.insertSlashCommand(command)
                    closeSheet()
                } label: {
                    Lead(4) {
                        Text(verbatim: "/" + command.name)
                            .typeset(15.5, .semibold, design: .monospaced, ink: AppColor.primary)
                        if !command.description.isEmpty {
                            Text(verbatim: command.description)
                                .typeset(13, ink: AppColor.onSurfaceVariant)
                                .lineLimit(3)
                        }
                    }
                    .gap(.vertical, 4)
                }
            }
            .scrollContentBackground(Visibility.hidden)
            .listStyle(InsetGroupedListStyle())
            .surfaceBackdrop()
            .inlineTitle("Slash Commands & Skills")
            .drawerSearch($query, prompt: "Search commands & skills…")
            .doneToolbar { closeSheet() }
        }
    }
}

// MARK: - Camera

struct CameraPicker: UIViewControllerRepresentable {
    @Environment(\.dismiss) private var closeSheet
    let onImage: (UIImage) -> Void

    init(onImage: @escaping (UIImage) -> Void) {
        self.onImage = onImage
    }

    func makeCoordinator() -> Coordinator { Coordinator(owner: self) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let controller = UIImagePickerController()
        controller.sourceType = .camera
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {
        context.coordinator.owner = self
    }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        var owner: CameraPicker
        init(owner: CameraPicker) {
            self.owner = owner
            super.init()
        }

        func imagePickerController(_ controller: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            let taken = info[UIImagePickerController.InfoKey.originalImage]
            if let shot = taken as? UIImage { owner.onImage(shot) }
            owner.closeSheet()
        }

        func imagePickerControllerDidCancel(_ controller: UIImagePickerController) {
            owner.closeSheet()
        }
    }
}
