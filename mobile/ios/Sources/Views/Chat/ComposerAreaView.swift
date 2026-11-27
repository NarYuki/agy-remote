import SwiftUI
import UIKit

struct ComposerAreaView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel

    @FocusState private var focused: Bool
    @State private var dictation = DictationController()
    /// What dictation last wrote into the draft; typing anything else ends dictation.
    @State private var dictatedDraft: String?
    @State private var suggestions: [WorkspaceFile] = []
    @State private var searchTask: Task<Void, Never>?

    private var canSubmit: Bool { viewModel.isChatAvailable && viewModel.canSend }
    private var running: Bool { viewModel.isRunning }
    private var slashMatches: [SlashCommand] { viewModel.matchingSlashCommands }

    private var showsModes: Bool {
        !viewModel.modes.isEmpty && !running && slashMatches.isEmpty && (viewModel.isEmptyChat || focused)
    }

    private var sideMargin: CGFloat {
        if focused || canSubmit { return AppMetrics.inputPlateFocusedMargin }
        return AppMetrics.inputPlateIdleMargin
    }

    private static let barIn = AnyTransition.move(edge: .bottom).combined(with: .opacity)
    private static let chipsIn = AnyTransition.scale(scale: 0.94, anchor: .bottom).combined(with: .opacity)
    private static let iconSwap = AnyTransition.opacity.combined(with: .scale(scale: 0.7))
    private static let micIn = AnyTransition.opacity.combined(with: .scale(scale: 0.85))

    @State private var planOpen = false

    /// The plan of the turn running in this chat, if any.
    private var livePlan: [AgyPlanEntry] {
        viewModel.messages.last(where: { $0.role == .agent && $0.isStreaming })?.plan ?? []
    }

    var body: some View {
        VStack(spacing: 10) {
            notices
                .padding(.horizontal, sideMargin)

            if !suggestions.isEmpty {
                suggestionPanel
                    .padding(.horizontal, sideMargin)
                    .transition(.melt)
            }

            GlassEffectContainer(spacing: 10) {
                VStack(spacing: 10) {
                    if slashMatches.count > 0 {
                        slashBar.transition(Self.barIn)
                    } else if showsModes {
                        ModeChipsView(viewModel: viewModel).transition(Self.chipsIn)
                    }
                    inputPlate
                    .frame(maxWidth: AppMetrics.inputPlateMaxWidth)
                    .padding(.horizontal, sideMargin)
                }
            }
        }
        .chatInsets(4, 0, AppMetrics.inputPlateBottomMargin, 0)
        .readableColumn(.center)
        .animation(.inputMorph, value: focused)
        .animation(.inputMorph, value: canSubmit)
        .animation(.inputMorph, value: running)
        .animation(.pill, value: showsModes)
        .animation(.melt, value: livePlan.isEmpty)
        .animation(.pill, value: slashMatches.count)
        .animation(.melt, value: viewModel.isOpenInCli)
        .animation(.melt, value: viewModel.versionIssue)
        .animation(.melt, value: viewModel.draftAttachments.count)
        .sheet(isPresented: $viewModel.showWorkspaceFilePicker) { WorkspaceFilePicker(viewModel: viewModel) }
        .sheet(isPresented: $viewModel.showCommandPicker) { SlashCommandPicker(viewModel: viewModel) }
        .onChange(of: focused) { _, now in
            if viewModel.composerFocused != now { viewModel.composerFocused = now }
            viewModel.gradientFlow.keyboardVisible = now
            viewModel.gradientFlow.stir()
        }
        .onChange(of: viewModel.composerFocused) { _, now in
            if !now && focused { focused = false }
        }
        .onChange(of: viewModel.composerFocusRequested, initial: true) { _, now in
            if now { focusSoon() }
        }
        .onChange(of: viewModel.showAddSheet) { _, open in
            if open { focused = false }
        }
        .onChange(of: viewModel.draft) { _, draft in
            viewModel.gradientFlow.keyboardVisible = focused
            viewModel.gradientFlow.stir()
            refreshSuggestions(for: draft)
            if dictation.isListening, let written = dictatedDraft, draft != written {
                dictation.cancel()
                dictatedDraft = nil
            }
        }
        .onChange(of: dictation.transcript) { _, words in
            guard dictation.isListening, !words.isEmpty else { return }
            let next = dictation.prefix + words
            dictatedDraft = next
            viewModel.draft = next
        }
        .onChange(of: dictation.isListening) { _, _ in Haptics.impact() }
        .onChange(of: running) { _, now in if now { dictation.cancel() } }
        .onChange(of: viewModel.selectedChatId) { _, _ in dictation.cancel() }
    }

    // MARK: notices

    @ViewBuilder
    private var notices: some View {
        if !livePlan.isEmpty {
            PlanDockView(entries: livePlan, open: $planOpen).transition(.melt)
        }
        if let issue = viewModel.versionIssue {
            VersionNotice(icon: "arrow.triangle.2.circlepath", text: versionText(issue))
                .transition(.melt)
        }
        if viewModel.isOpenInCli {
            let host = viewModel.selectedDevice?.name
            OpenInCliNotice(hostName: host ?? "").transition(AnyTransition.melt)
        }
        if let limit = viewModel.quotaNoticeLimit, viewModel.quotaNoticeRemaining != nil {
            QuotaLowNotice(limit: limit) {
                withAnimation(.melt) { viewModel.dismissQuotaNotice(limit) }
            }
            .transition(.melt)
        }
    }

    private func versionText(_ issue: RemoteWorkspaceViewModel.VersionIssue) -> Text {
        if issue == .updateApp {
            return Text("Update AGY Remote to use this computer's agy-connector.")
        }
        let name = viewModel.selectedDevice?.name ?? String(localized: "your computer")
        return Text("Update agy-connector on \(name) to use this app.")
    }

    // MARK: input plate

    private var inputPlate: some View {
        VStack(spacing: 8) {
            let pending = viewModel.draftAttachments
            if pending.count > 0 {
                ScrollView(.horizontal) {
                    HStack(spacing: 10) {
                        ForEach(pending) { attachment in
                            ComposerAttachmentCard(attachment: attachment) { viewModel.removeAttachment(attachment) }
                        }
                    }
                    .chatPad(0, 2)
                }
                .scrollIndicators(.hidden)
                .chatInsets(10, 14, 0, 14)
            }
            HStack(spacing: 12) {
                plusButton
                field
                if !running || dictation.isListening {
                    micButton.transition(Self.micIn)
                }
                sendButton
            }
            .chatInsets(pending.isEmpty ? 9 : 2, 14, 9, 10)
        }
        .frame(minHeight: AppMetrics.inputPlateHeight, alignment: .center)
        .chatGlass(chatRound(AppMetrics.inputPlateHeight / 2))
    }

    private var plusButton: some View {
        Button {
            Haptics.impact()
            focused = false
            viewModel.showAddSheet = true
        } label: {
            Image(systemName: "plus")
                .chatType(21, AppColor.onSurface.opacity(0.92), .light)
                .chatSize(34, 38)
                .contentShape(Circle())
        }
        .buttonStyle(PlainButtonStyle())
        .accessibilityLabel(Text("Add"))
    }

    private var field: some View {
        TextField(text: $viewModel.draft, axis: .vertical) {
            Text(viewModel.placeholder).foregroundStyle(AppColor.onSurfaceVariant.opacity(0.88))
        }
        .lineLimit(1...5)
        .foregroundStyle(AppColor.onSurface)
        .font(AppFont.bodyLarge)
        .chatPad(0, 6)
        .focused($focused)
        .disabled(viewModel.isOpenInCli)
        .submitLabel(.send)
        .onSubmit(submit)
        .onKeyPress(.return, phases: .down) { press in
            // Hardware keyboard: Return sends, Shift-Return falls through to a newline.
            if press.modifiers.contains(.shift) { return .ignored }
            submit()
            return .handled
        }
    }

    private var micButton: some View {
        Button {
            if dictation.isListening {
                dictation.stop()
            } else {
                dictation.toggle(current: viewModel.draft)
            }
        } label: {
            ZStack {
                if dictation.isListening {
                    Circle()
                        .fill(AppColor.errorRed.opacity(0.22 + 0.35 * Double(dictation.level)))
                        .chatSize(34, 34)
                        .animation(.easeOut(duration: 0.1), value: dictation.level)
                    Image(systemName: "mic.fill")
                        .chatType(17.5, AppColor.errorRed)
                } else {
                    Image(systemName: "mic")
                        .chatType(17.5, AppColor.onSurface.opacity(0.88))
                }
            }
            .chatSize(34, 38)
            .contentShape(Rectangle())
        }
        .buttonStyle(PlainButtonStyle())
        .accessibilityLabel(Text("Dictate"))
    }

    private var sendButton: some View {
        let tint = running ? AppColor.stopButtonFill : (canSubmit ? AppColor.accentFill : AppColor.accentFillMuted)
        return Button {
            if running { viewModel.stop() } else { submit() }
        } label: {
            ZStack {
                if running {
                    StopIcon(color: AppColor.onSurface).transition(Self.iconSwap)
                } else {
                    let arrowInk = canSubmit ? AppColor.onAccentFill : AppColor.onSurfaceVariant
                    Image(systemName: "arrow.up")
                        .chatType(16, arrowInk, .semibold)
                        .transition(Self.iconSwap)
                }
            }
            .chatSize(38, 38)
            .glassEffect(.regular.tint(tint).interactive(), in: Circle())
            .animation(.inputMorph, value: running)
        }
        .buttonStyle(PlainButtonStyle())
        .disabled(!running && !canSubmit)
        .accessibilityLabel(running ? Text("Stop") : Text("Send"))
    }

    // MARK: slash bar

    private var slashBar: some View {
        let matches = slashMatches
        return ScrollView(.horizontal) {
            HStack(spacing: 6) {
                ForEach(matches) { command in
                    Button { viewModel.insertSlashCommand(command) } label: {
                        HStack(spacing: 6) {
                            Text(verbatim: "/" + command.name)
                                .chatType(13, AppColor.primary, .semibold, mono: true)
                            if !command.description.isEmpty {
                                Text(command.description)
                                    .chatType(12, AppColor.onSurfaceVariant)
                                    .frame(maxWidth: 160, alignment: .leading)
                                    .lineLimit(1)
                            }
                        }
                        .chatPad(12, 0)
                        .frame(minHeight: 30, maxHeight: 30)
                        .chatGlass(Capsule())
                    }
                    .buttonStyle(PlainButtonStyle())
                }
            }
            .padding(.horizontal, 16)
        }
    }

    // MARK: @ suggestions

    private var suggestionPanel: some View {
        VStack(spacing: 0) {
            ForEach(suggestions) { file in
                Button { choose(file) } label: {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(verbatim: (file.path as NSString).lastPathComponent)
                            .chatType(14, AppColor.onSurface, .medium)
                        Text(verbatim: file.path)
                            .chatFont(11.5, mono: true)
                            .foregroundStyle(AppColor.onSurfaceVariant)
                            .truncationMode(.head)
                    }
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .padding(.horizontal, 16)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PlainButtonStyle())
            }
        }
        .padding(.vertical, 6)
        .chatGlass(chatRound(22))
    }

    /// The `@word` being typed at the end of the draft, without the "@".
    private static func atQuery(_ draft: String) -> String? {
        let tail = draft.reversed().prefix { !$0.isWhitespace }
        let word = String(tail.reversed())
        // Japanese keyboards type the full-width sign, so take either one.
        guard let lead = word.first, lead == "@" || lead == "\u{FF20}" else { return nil }
        return String(word.dropFirst())
    }

    private func refreshSuggestions(for draft: String) {
        searchTask?.cancel()
        guard let query = Self.atQuery(draft) else {
            searchTask = nil
            if !suggestions.isEmpty { suggestions = [] }
            return
        }
        searchTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(150))
            guard !Task.isCancelled else { return }
            let found = await viewModel.searchFiles(query)
            guard !Task.isCancelled, Self.atQuery(viewModel.draft) == query else { return }
            suggestions = Array(found.prefix(6))
        }
    }

    private func choose(_ file: WorkspaceFile) {
        var draft = viewModel.draft
        while let last = draft.last, !last.isWhitespace { draft.removeLast() }
        draft += "@" + file.path + " "
        searchTask?.cancel()
        viewModel.draft = draft
        viewModel.addWorkspaceFile(file)
        suggestions = []
    }

    // MARK: actions

    private func submit() {
        guard canSubmit, !running else { return }
        dictation.stop()
        dictatedDraft = nil
        viewModel.send()
    }

    private func focusSoon() {
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(150))
            focused = true
            viewModel.composerFocusRequested = false
        }
    }
}

private struct ComposerAttachmentCard: View {
    let attachment: MessageAttachment
    let onRemove: () -> Void

    var body: some View {
        if attachment.kind == .image {
            ZStack(alignment: .topTrailing) {
                Group {
                    if let image = ThumbnailCache.image(for: attachment) {
                        Image(uiImage: image).resizable().scaledToFill()
                    } else {
                        ZStack {
                            AppColor.surfaceContainerHigh
                            Image(systemName: "photo").foregroundStyle(AppColor.onSurfaceVariant)
                        }
                    }
                }
                .chatSize(58, 58)
                .clipShape(chatRound(13))
                removeButton.offset(x: 4, y: -4)
            }
        } else if let path = attachment.workspacePath {
            HStack(spacing: 6) {
                let atInk = AppColor.primary
                Image(systemName: "at").chatType(12, atInk, Font.Weight.bold)
                Text(verbatim: path)
                    .chatType(12, AppColor.onSurface, .medium, mono: true)
                    .lineLimit(1)
                removeButton
            }
            .chatInsets(7, 10, 7, 6)
            .chatCapsule(AppColor.surfaceContainerHigh)
        } else {
            HStack(spacing: 8) {
                let fileGreen = AppColor.connectedGreen
                Image(systemName: "doc.fill").chatType(14, fileGreen)
                VStack(alignment: HorizontalAlignment.leading, spacing: 1) {
                    Text(verbatim: attachment.name).lineLimit(1)
                        .chatType(12.5, AppColor.onSurface, .medium)
                    Text(sizeLabel)
                        .chatType(10.5, AppColor.onSurfaceMuted)
                }
                removeButton
            }
            .chatInsets(7, 10, 7, 6)
            .background(chatRound(13).fill(AppColor.surfaceContainerHigh))
        }
    }

    private var sizeLabel: String {
        let bytes = attachment.byteCount ?? attachment.payload?.count ?? 0
        return Int64(bytes).formatted(.byteCount(style: .file))
    }

    private var removeButton: some View {
        Button(action: onRemove) {
            Image(systemName: "xmark.circle.fill")
                .chatFont(16)
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, .black.opacity(0.75))
        }
        .buttonStyle(PlainButtonStyle())
        .accessibilityLabel(Text("Remove"))
    }
}
