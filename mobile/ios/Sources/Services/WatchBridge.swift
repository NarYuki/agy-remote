// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Observation
import WatchConnectivity

/// Serves the Apple Watch app. The watch can't run the P2P stack, so it asks the phone:
/// this keeps the watch's application context in step with the workspace and carries out
/// what the watch requests through the regular view-model calls.
@MainActor
final class WatchBridge: NSObject {
    private weak var workspace: RemoteWorkspaceViewModel?
    private var lastSent: WatchSnapshot?
    private var flushPending = false
    private var primed: [String: Date] = [:]
    private var runStarts: [String: Double] = [:]
    private static let pushDelay: Duration = .milliseconds(900)
    private static let chatLimit = 14

    func attach(_ vm: RemoteWorkspaceViewModel) {
        guard workspace == nil, WCSession.isSupported() else { return }
        workspace = vm
        let session = WCSession.default
        session.delegate = self
        session.activate()
        track()
    }

    // MARK: Snapshot

    private func track() {
        withObservationTracking { _ = makeSnapshot() } onChange: { [weak self] in
            Task { @MainActor in
                self?.scheduleFlush()
                self?.track()
            }
        }
    }

    private func scheduleFlush() {
        guard !flushPending else { return }
        flushPending = true
        Task {
            try? await Task.sleep(for: Self.pushDelay)
            flushPending = false
            flush()
        }
    }

    private func flush(force: Bool = false) {
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled else { return }
        var snap = makeSnapshot()
        guard force || snap != lastSent else { return }
        lastSent = snap
        snap.stamp = Date().timeIntervalSince1970
        try? session.updateApplicationContext(WatchLink.pack(snap))
        loadRunningTranscripts(snap)
    }

    /// Approvals only show up in transcripts, so running chats get loaded in the background.
    private func loadRunningTranscripts(_ snap: WatchSnapshot) {
        guard let vm = workspace else { return }
        let now = Date()
        for chat in snap.running where vm.messagesByChat[chat.id] == nil {
            if let t = primed[chat.id], now.timeIntervalSince(t) < 30 { continue }
            primed[chat.id] = now
            Task { await vm.openChat(chat.id, quiet: true) }
        }
    }

    private func makeSnapshot() -> WatchSnapshot {
        guard let vm = workspace else { return WatchSnapshot() }
        var snap = WatchSnapshot()
        if let d = vm.selectedDevice {
            snap.computer = .init(id: d.id, name: d.name, online: d.status == .connected)
        }
        snap.computers = vm.devices.map { .init(id: $0.id, name: $0.name, online: $0.status == .connected) }
        let ordered = vm.chats.sorted { a, b in
            let ra = vm.chatIsRunning(a), rb = vm.chatIsRunning(b)
            return ra != rb ? ra : a.updated > b.updated
        }
        snap.chats = ordered.prefix(Self.chatLimit).map { c in
            let running = vm.chatIsRunning(c)
            return WatchSnapshot.Chat(id: c.id, title: c.title, folder: vm.folderName(for: c),
                                      status: vm.liveStatus(for: c.id), isRunning: running,
                                      runningSince: running ? runStart(c.id) : nil,
                                      lastLine: Self.lastLine(vm.messagesByChat[c.id]))
        }
        runStarts = runStarts.filter { id, _ in snap.chats.contains { $0.id == id && $0.isRunning } }
        snap.approvals = openApprovals(in: vm)
        if let left = vm.tightestLimit ?? vm.quota?.limits.map(\.remaining).min() { snap.quotaPercent = Int(left.rounded()) }
        return snap
    }

    /// The phone has no turn start time, so the first time it is seen running stands in.
    private func runStart(_ id: String) -> Double {
        if let t = runStarts[id] { return t }
        let now = Date().timeIntervalSince1970
        runStarts[id] = now
        return now
    }

    private static func lastLine(_ list: [ChatMessage]?) -> String? {
        guard let m = list?.last(where: { $0.role == .agent && !$0.text.isEmpty }) else { return nil }
        let first = m.text.split(whereSeparator: \.isNewline).first.map(String.init) ?? m.text
        // Markdown marks read as noise on a wrist.
        let plain = ["**", "`"].reduce(first) { text, mark in text.replacingOccurrences(of: mark, with: "") }
        return WatchLink.clip(plain, to: 140)
    }

    private func openApprovals(in vm: RemoteWorkspaceViewModel) -> [WatchSnapshot.Approval] {
        var found: [WatchSnapshot.Approval] = []
        for (chatId, list) in vm.messagesByChat {
            let title = vm.chats.first { $0.id == chatId }?.title ?? ""
            for message in list where message.isStreaming || message.permissions.contains(where: { $0.selectedOptionId == nil }) {
                // Questions with options and written answers are answered on the phone.
                for ask in message.permissions where ask.selectedOptionId == nil && !ask.isStale && !ask.isQuestion {
                    let (summary, whole) = Self.summary(of: ask)
                    // Allowed from the wrist only when the watch shows all of it.
                    let choices = ask.options.map { WatchSnapshot.Choice(id: $0.optionId, label: $0.name, kind: $0.kind) }
                        .filter { whole || !$0.allows }
                    found.append(.init(id: ask.id, chatId: chatId, chatTitle: title,
                                       summary: summary, choices: choices))
                }
            }
        }
        return found.sorted { $0.id < $1.id }
    }

    /// The command when the request carries one, else its title, and whether all of it fits.
    private static func summary(of ask: PendingPermissionRequest) -> (String, Bool) {
        let fields = (try? JSONSerialization.jsonObject(with: Data(ask.detail.utf8))) as? [String: Any]
        let full = (fields?["command"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? ask.title
        let clipped = WatchLink.clip(full, to: 300)
        return (clipped, clipped == full)
    }

    // MARK: Requests

    private func perform(_ request: WatchRequest) async -> WatchReply {
        guard let vm = workspace else { return WatchReply(ok: false) }
        switch request {
        case .refresh:
            return WatchReply(ok: true, snapshot: makeSnapshot())

        case .transcript(let id):
            // Live turns update the cache on their own, so a poll reads it; it only loads what isn't there.
            if vm.messagesByChat[id] == nil { await vm.openChat(id, quiet: true) }
            return transcript(of: id, in: vm)

        case .prompt(let id, let text):
            if let id {
                if vm.selectedChatId != id { await vm.openChat(id) }
            } else {
                vm.startNewChat()
            }
            guard !vm.isRunning else { return WatchReply(ok: false, note: "busy") }
            let kept = (vm.draft, vm.draftAttachments)
            vm.draft = text
            vm.draftAttachments = []
            vm.send()
            let accepted = vm.draft.isEmpty
            vm.draft = kept.0
            vm.draftAttachments = kept.1
            guard accepted else { return WatchReply(ok: false) }
            if let id { return transcript(of: id, in: vm) }
            return WatchReply(ok: true)

        case .approve(let chatId, let approvalId, let choiceId):
            if let ask = pending(approvalId, in: vm) {
                vm.answer(ask, option: choiceId)
            } else {
                await vm.openChat(chatId, quiet: true)
                guard let ask = pending(approvalId, in: vm) else { return WatchReply(ok: false) }
                vm.answer(ask, option: choiceId)
            }
            scheduleFlush()
            return WatchReply(ok: true)

        case .quickAnswer(let approvalId, let allow, let computer):
            if let ask = pending(approvalId, in: vm),
               let option = ask.options.first(where: { $0.kind.hasPrefix(allow ? "allow_once" : "deny") })
                ?? ask.options.first(where: { $0.kind.hasPrefix(allow ? "allow" : "deny") }) {
                vm.answer(ask, option: option.optionId)
            } else {
                await AppDelegate.onPermissionAction?(approvalId, allow, computer)
            }
            scheduleFlush()
            return WatchReply(ok: true)

        case .selectComputer(let id):
            vm.devices.first { $0.id == id }.map(vm.selectDevice)
            return WatchReply(ok: true, snapshot: makeSnapshot())

        case .stop(let id):
            if vm.selectedChatId != id { await vm.openChat(id) }
            vm.stop()
            scheduleFlush()
            return transcript(of: id, in: vm)
        }
    }

    private func pending(_ approvalId: String, in vm: RemoteWorkspaceViewModel) -> PendingPermissionRequest? {
        for list in vm.messagesByChat.values {
            for m in list {
                if let p = m.permissions.first(where: { $0.id == approvalId }) { return p }
            }
        }
        return nil
    }

    private func transcript(of id: String, in vm: RemoteWorkspaceViewModel) -> WatchReply {
        let recent = (vm.messagesByChat[id] ?? []).suffix(WatchLink.transcriptDepth)
        let lines = recent.compactMap { m -> WatchLine? in
            var text = m.text
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                // A streaming reply's status already shows under the transcript.
                text = m.isStreaming ? "" : (m.toolCalls.last?.title ?? "")
            }
            if text.isEmpty && !m.attachments.isEmpty { text = m.attachments.map(\.name).joined(separator: ", ") }
            guard !text.isEmpty else { return nil }
            return WatchLine(id: m.id, fromUser: m.role == .user, text: m.role == .user ? WatchLink.clip(text, to: 1000) : WatchLink.capMarkdown(text))
        }
        let running = vm.chats.first { $0.id == id }.map(vm.chatIsRunning) ?? false
        return WatchReply(ok: true, lines: Array(lines), isRunning: running || (vm.selectedChatId == id && vm.isRunning),
                          chatId: id, snapshot: makeSnapshot())
    }
}

/// Hands a non-Sendable reply callback across to the main actor.
private struct ReplyBox: @unchecked Sendable {
    let send: ([String: Any]) -> Void
}

extension WatchBridge: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        Task { @MainActor in self.flush(force: true) }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }

    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in self.flush(force: true) }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any],
                             replyHandler: @escaping ([String: Any]) -> Void) {
        let box = ReplyBox(send: replyHandler)
        guard let request = WatchLink.unpack(WatchRequest.self, from: message) else {
            box.send(WatchLink.pack(WatchReply(ok: false)))
            return
        }
        Task { @MainActor in
            let reply = await self.perform(request)
            box.send(WatchLink.pack(reply))
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        guard let request = WatchLink.unpack(WatchRequest.self, from: userInfo) else { return }
        Task { @MainActor in _ = await self.perform(request) }
    }
}
