import SwiftUI
import UIKit

/// The scrolling list of messages. It follows new output while the reader is near the end and
/// offers a jump button (with an unseen-output dot) once they scroll away.
struct TranscriptView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    var scrollState: TranscriptScrollState? = nil
    var centerRequest: Int = 0

    @State private var follow = FollowState()
    /// Scroll geometry changes every frame; kept out of view state so scrolling doesn't re-run
    /// this body (and compare every message) on each frame.
    @State private var track = ScrollTrack()
    @State private var position = ScrollPosition(edge: .bottom)

    private static let rowGap: CGFloat = 22

    private func row(_ item: ChatMessage) -> some View {
        MessageRow(message: item, onAnswer: answer, onQuestion: answerQuestion, onRerun: rerun).equatable().id(item.id)
    }

    var body: some View {
        let rows = viewModel.messages
        ScrollView(.vertical) {
            // A plain stack: a lazy one guesses row heights, and after a very tall reply the
            // bottom-anchored scroll can land on rows it never laid out, leaving the view blank.
            VStack(alignment: .leading, spacing: Self.rowGap) {
                ForEach(rows, id: \.id, content: row)
                Color.clear.frame(height: 16)
            }
            .padding(EdgeInsets(top: 14, leading: 0, bottom: 16, trailing: 0))
            .readableColumn(.leading)
            .contentShape(Rectangle())
            .onTapGesture(perform: dropFocus)
        }
        .scrollPosition($position)
        .defaultScrollAnchor(.bottom)
        .scrollDismissesKeyboard(.interactively)
        .scrollEdgeEffectStyle(.soft, for: .vertical)
        .onScrollGeometryChange(for: FollowState.Geometry.self, of: FollowState.Geometry.init, action: geometryChanged)
        .onScrollPhaseChange(phaseChanged)
        .onChange(of: rows.last?.id) { lastChanged() }
        .onChange(of: viewModel.selectedChatId ?? viewModel.viewingDraftKey, initial: true) { jump(animated: false) }
        .onChange(of: Self.stamp(rows)) {
            if !follow.pinned { follow.unseen = true }
        }
        .onChange(of: centerRequest) { centre() }
        .modifier(JumpLink(state: scrollState, shown: follow.jumpShown, unseen: follow.unseen) { jump(animated: true) })
        // Links in replies never go to the system directly (see RemoteWorkspaceViewModel.classify).
        .environment(\.openURL, OpenURLAction { url in
            viewModel.handleChatLink(url)
            return .handled
        })
        .alert(Text("Open this link?"), isPresented: webLinkAsked, presenting: viewModel.pendingWebLink) { url in
            Button(role: .cancel) { viewModel.pendingWebLink = nil } label: { Text("Cancel") }
            Button {
                viewModel.pendingWebLink = nil
                UIApplication.shared.open(url)
            } label: { Text("Open in Browser") }
            Button {
                UIPasteboard.general.string = url.absoluteString
                viewModel.pendingWebLink = nil
            } label: { Text("Copy Link") }
        } message: { url in
            Text(verbatim: url.absoluteString)
        }
        .sheet(item: $viewModel.fileView) { _ in
            FileViewerSheet(viewModel: viewModel).presentationDetents([.large])
        }
    }

    private var webLinkAsked: Binding<Bool> {
        Binding(get: { viewModel.pendingWebLink != nil }) { if !$0 { viewModel.pendingWebLink = nil } }
    }

    // MARK: callbacks

    private func answer(_ request: PendingPermissionRequest, _ option: String) {
        viewModel.answer(request, option: option)
    }

    private func answerQuestion(_ request: PendingPermissionRequest, _ answers: [String: Any]?) {
        viewModel.answerQuestion(request, answers: answers)
    }

    private func rerun() { viewModel.rerunLastPrompt() }

    private func dropFocus() { viewModel.composerFocused = false }

    /// Following the end is one state: on at the end, off only when a finger takes the view up.
    /// The keyboard coming or going (and the drag that dismisses it) moves the geometry for a
    /// moment; that alone never stops following, so the jump button, its unseen dot and the
    /// dimmed glow don't appear while the user is at the end.
    private func geometryChanged(_ before: FollowState.Geometry, _ now: FollowState.Geometry) {
        track.geometry = now
        if now.fromBottom <= FollowState.slack {
            setPinned(true)
        } else if track.touching {
            setPinned(false)
        }
        refresh()
        guard !track.touching else { return }
        let resized = before.content != now.content || before.viewport != now.viewport
        if follow.pinned && resized { toEnd() }
    }

    private func setPinned(_ on: Bool) {
        if follow.pinned != on { follow.pinned = on }
        if on && follow.unseen { follow.unseen = false }
    }

    /// Writes the button and the glow's distance only when they change.
    private func refresh() {
        let g = track.geometry
        let shown = !follow.pinned && g.fromBottom > max(160, g.viewport * 0.4)
        if follow.jumpShown != shown { follow.jumpShown = shown }
        guard g.viewport > 0 else { return }
        scrollState?.report(follow.pinned ? 0 : Double(g.fromBottom / g.viewport))
    }

    private func phaseChanged(_ before: ScrollPhase, _ now: ScrollPhase) {
        track.touching = FollowState.isHands(now)
        guard now == .idle else { return }
        let g = track.geometry
        if FollowState.isHands(before) {
            setPinned(g.fromBottom <= max(FollowState.slack, g.viewport * 0.2))
            refresh()
            if follow.pinned, g.fromBottom > 1 { toEnd(animated: true) }
        } else if follow.pinned, g.fromBottom > 1 {
            // A programmatic scroll stopped short of the end (the reply grew meanwhile).
            toEnd()
        }
    }

    private func lastChanged() {
        let all = viewModel.messages
        guard let newest = all.last else { return }
        let before = all.dropLast().last
        let userSent = newest.role == .user
        let replyStarted = newest.role == .agent && newest.isStreaming && before?.role == .user
        if userSent || replyStarted { jump(animated: true) }
    }

    private func centre() {
        let all = viewModel.messages
        if all.count < 2 { return }
        setPinned(false)
        let target = all[all.count / 2].id
        withAnimation(Animation.easeInOut(duration: 0.25)) { position.scrollTo(id: target, anchor: .center) }
    }

    private func jump(animated: Bool) {
        setPinned(true)
        refresh()
        // A newly opened chat: its geometry isn't measured yet, so name the edge.
        guard animated else { position.scrollTo(edge: .bottom); return }
        toEnd(animated: true)
    }

    /// Scrolls to the newest row. Asking for `.bottom` again once the position already names that
    /// edge does nothing, so the view would stay where the reader left it.
    private func toEnd(animated: Bool = false) {
        guard let last = viewModel.messages.last?.id else { return }
        if animated {
            withAnimation(Animation.smooth(duration: 0.35)) { position.scrollTo(id: last, anchor: .bottom) }
        } else {
            position.scrollTo(id: last, anchor: .bottom)
        }
    }

    /// Changes whenever the visible output grows.
    private static func stamp(_ list: [ChatMessage]) -> Int {
        var hasher = Hasher()
        hasher.combine(list.count)
        let tail = list.last
        hasher.combine(tail?.text.count ?? 0)
        hasher.combine(tail?.toolCalls.count ?? 0)
        hasher.combine(tail?.toolCalls.last?.status ?? "")
        hasher.combine(tail?.permissions.count ?? 0)
        return hasher.finalize()
    }
}

/// Mirrors the jump button's state into the bottom bar, which shows it, and runs its taps.
private struct JumpLink: ViewModifier {
    let state: TranscriptScrollState?
    let shown: Bool
    let unseen: Bool
    let onJump: () -> Void

    func body(content: Content) -> some View {
        content
            .onChange(of: state?.jumpRequest ?? 0) { onJump() }
            .onChange(of: shown, initial: true) { _, on in state?.jumpShown = on }
            .onChange(of: unseen) { _, on in state?.unseen = on }
            .onDisappear { state?.jumpShown = false }
    }
}

/// The latest scroll geometry and whether a finger is on the view. A plain reference, so
/// writing it doesn't redraw anything.
private final class ScrollTrack {
    var geometry = FollowState.Geometry()
    var touching = false
}

/// Follow-the-end bookkeeping for the transcript.
private struct FollowState {
    static let slack: CGFloat = 48

    struct Geometry: Equatable {
        var fromTop: CGFloat = 0
        var fromBottom: CGFloat = 0
        var viewport: CGFloat = 0
        var content: CGFloat = 0

        init() {}

        init(_ g: ScrollGeometry) {
            let inset = g.contentInsets
            // Measured from what's on screen: the reported offset and insets don't agree with the
            // bars' insets, so an end worked out from them can lie past where the view can go.
            fromBottom = max(0, g.contentSize.height + inset.bottom - g.visibleRect.maxY)
            fromTop = (min(18, max(0, g.contentOffset.y + inset.top))).rounded()
            viewport = g.containerSize.height
            content = g.contentSize.height
        }
    }

    var pinned = true
    var unseen = false
    var jumpShown = false

    static func isHands(_ phase: ScrollPhase) -> Bool {
        switch phase {
        case .interacting, .decelerating, .tracking: true
        default: false
        }
    }
}

struct JumpToLatestButton: View {
    let dot: Bool
    let onTap: @MainActor () -> Void

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: "arrow.down")
                    .chatType(15, AppColor.onSurface, .semibold)
                    .chatSize(40, 40)
                    .chatGlass(Circle())
                if dot {
                    Circle()
                        .fill(AppColor.primary)
                        .chatSize(9, 9)
                        .offset(CGSize(width: 1, height: -1))
                        .transition(AnyTransition.scale.combined(with: AnyTransition.opacity))
                }
            }
            .animation(Animation.spring(response: 0.3, dampingFraction: 0.7), value: dot)
        }
        .buttonStyle(PlainButtonStyle())
        .accessibilityLabel(Text("Scroll to the latest message"))
    }
}

// MARK: - Rows

struct MessageRow: View, Equatable {
    let message: ChatMessage
    let onAnswer: (PendingPermissionRequest, String) -> Void
    /// Answers to an `ask_question` form; nil when skipped.
    let onQuestion: (PendingPermissionRequest, [String: Any]?) -> Void
    let onRerun: () -> Void

    nonisolated static func == (lhs: MessageRow, rhs: MessageRow) -> Bool { lhs.message == rhs.message }

    var body: some View {
        if message.role == .user {
            UserRowContent(message: message)
        } else {
            agentColumn
        }
    }

    private var agentColumn: some View {
        let m = message
        let showsActions = !m.isStreaming && !m.text.isEmpty
        var parts = ReplyPart.split(m)
        if !m.thoughtSummary.isEmpty, !parts.contains(where: { if case .steps = $0 { true } else { false } }) {
            parts.insert(.steps(id: -1, []), at: 0)
        }
        let firstGroup = parts.firstIndex { if case .steps = $0 { true } else { false } }
        return VStack(alignment: .leading, spacing: 12) {
            // While the turn runs its plan sits in the composer (PlanDockView); afterwards, here.
            if !m.plan.isEmpty && !m.isStreaming { PlanCardView(entries: m.plan) }
            ForEach(Array(parts.enumerated()), id: \.element.id) { index, part in
                let last = index == parts.count - 1
                switch part {
                case .text(_, let text):
                    StreamingTextView(text: text, isStreaming: m.isStreaming && last)
                case .steps(_, let calls):
                    StepGroupView(calls: calls, thought: index == firstGroup ? m.thoughtSummary : "",
                                  live: m.isStreaming && last)
                }
            }
            ForEach(m.permissions) { req in
                // A question left unanswered when its turn ended (stopped, failed, the connector restarted)
                // can't be answered any more: show it as gone instead of with live buttons.
                let ended = !m.isStreaming && req.selectedOptionId == nil
                if req.isQuestion {
                    QuestionCardView(request: ended ? req.markedStale() : req, onSend: { onQuestion(req, $0) })
                } else {
                    PermissionCardView(request: ended ? req.markedStale() : req, onSelect: { onAnswer(req, $0) })
                }
            }
            if m.isStreaming {
                LiveStatusLine(message: m)
            } else if let secs = m.durationSeconds {
                Text("Worked for \(StepGroupView.duration(secs))")
                    .chatType(13, AppColor.onSurfaceMuted)
            }
            if showsActions {
                MessageActionsView(text: m.text, onCopy: copyText, onRerun: onRerun)
            }
        }
        .padding(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
        .chatWide(.leading)
    }

    private func copyText() { UIPasteboard.general.string = message.text }
}

private struct UserRowContent: View {
    let message: ChatMessage

    var body: some View {
        let files = message.attachments
        VStack(alignment: .trailing, spacing: 8) {
            if files.count > 0 {
                HStack(spacing: 8) {
                    Spacer(minLength: 40.0)
                    ForEach(files, id: \.id) { AttachmentThumb(attachment: $0) }
                }
            }
            if !message.text.isEmpty { bubble }
        }
        .padding(.horizontal, 16)
        .chatWide(.trailing)
    }

    private var bubble: some View {
        let shape = chatRound(AppMetrics.userBubbleCornerRadius)
        return HStack(spacing: 0) {
            Spacer(minLength: 56)
            Text(message.text)
                .foregroundStyle(AppColor.onSurface)
                .font(AppFont.bodyLarge)
                .chatPad(20, 13)
                .frame(minWidth: 58, idealWidth: nil, minHeight: 48)
                .background(shape.fill(AppColor.userBubble))
        }
    }
}

struct AttachmentThumb: View {
    let attachment: MessageAttachment

    var body: some View {
        switch (attachment.kind, attachment.workspacePath) {
        case (.image, _):
            if let picture = ThumbnailCache.image(for: attachment) {
                Image(uiImage: picture)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .chatSize(124, 124)
                    .clipShape(chatRound(18))
            }
        case (_, let path?):
            SentFilePill(symbol: "at", symbolSize: 12, symbolColor: AppColor.primary, symbolWeight: .bold,
                         label: path, labelSize: 12, mono: true)
        default:
            SentFilePill(symbol: "doc.fill", symbolSize: 13, symbolColor: AppColor.connectedGreen, symbolWeight: .regular,
                         label: attachment.name, labelSize: 12.5, mono: false)
        }
    }
}

private struct SentFilePill: View {
    let symbol: String
    let symbolSize: CGFloat
    let symbolColor: Color
    let symbolWeight: Font.Weight
    let label: String
    let labelSize: CGFloat
    let mono: Bool

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: symbol).chatType(symbolSize, symbolColor, symbolWeight)
            Text(verbatim: label).chatType(labelSize, AppColor.onSurface, .medium, mono: mono)
        }
        .lineLimit(1)
        .chatPad(12, 8)
        .chatCapsule(AppColor.surfaceContainerHigh)
    }
}

/// Decoded attachment previews, so scrolling doesn't decode JPEGs again.
@MainActor
enum ThumbnailCache {
    private static let decoded: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 120
        return cache
    }()

    static func image(for attachment: MessageAttachment) -> UIImage? {
        let key = NSString(string: attachment.id)
        if let known = decoded.object(forKey: key) { return known }
        guard let bytes = attachment.imageData ?? attachment.payload, let raw = UIImage(data: bytes) else { return nil }
        let ready = raw.preparingForDisplay() ?? raw
        decoded.setObject(ready, forKey: key)
        return ready
    }
}
