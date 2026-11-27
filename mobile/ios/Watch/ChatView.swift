// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

/// One chat: messages oldest to newest, scrolled to the end, with the reply field under them.
/// Stop sits in the toolbar while a turn runs.
struct ChatDetail: View {
    let link: PhoneLink
    let chatId: String
    @State private var lines: [WatchLine] = []
    @State private var running = false
    @State private var loaded = false
    @State private var failed = false
    /// What was just sent, drawn at once until the phone's transcript carries it.
    @State private var outbox: (line: WatchLine, at: Date)?
    /// Bumped on send so the poll restarts and reads the transcript right away.
    @State private var kick = 0

    private var chat: WatchSnapshot.Chat? { link.snapshot?.chats.first { $0.id == chatId } }
    private var isRunning: Bool { running || chat?.isRunning == true || outbox != nil }
    private var shown: [WatchLine] { outbox.map { lines + [$0.line] } ?? lines }

    var body: some View {
        GeometryReader { box in
            ScrollViewReader { reader in
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        // Messages and the reply field fill at least one screen, bottom-aligned, so
                        // the quick replies under them always start below the fold.
                        VStack(alignment: .leading, spacing: 10) {
                            if !loaded {
                                ProgressView().frame(maxWidth: .infinity)
                            } else if failed && lines.isEmpty {
                                Text("Couldn't reach your iPhone.").font(.footnote).foregroundStyle(.secondary)
                            }
                            ForEach(shown) { line in Bubble(line: line) }
                            if isRunning { runningRow }
                            Composer(link: link, chatId: chatId, running: isRunning, inline: true,
                                     part: .field, onStop: stopTurn, posted: posted) { reply in apply(reply) }
                                .padding(.top, 2)
                        }
                        .frame(maxWidth: .infinity, minHeight: box.size.height, alignment: .bottomLeading)
                        Composer(link: link, chatId: chatId, running: isRunning, inline: true,
                                 part: .quickReplies, posted: posted, sent: { reply in apply(reply) })
                    }
                    .padding(.horizontal, 2)
                    .padding(.bottom, 4)
                }
                .onAppear { reader.scrollTo(Composer.fieldID, anchor: .bottom) }
                .onChange(of: shown) { reader.scrollTo(Composer.fieldID, anchor: .bottom) }
                .onChange(of: loaded) { reader.scrollTo(Composer.fieldID, anchor: .bottom) }
            }
        }
        .ignoresSafeArea(.container, edges: .bottom)
        .navigationTitle(Text(chat?.title ?? ""))
        .navigationBarTitleDisplayMode(.inline)
        .scrollEdgeEffectStyle(.hard, for: .top)
        .containerBackground(WatchStyle.page(isRunning ? WatchStyle.sky : WatchStyle.violet), for: .navigation)
        .task(id: "\(chatId)#\(kick)") { await poll() }
    }

    /// What agy is doing now, with a small Stop next to it.
    private var runningRow: some View {
        HStack(spacing: 6) {
            RunningMark(size: 12)
            Text(chat?.status.isEmpty == false ? chat!.status : String(localized: "Working"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }

    private func stopTurn() {
        Buzz.tap()
        Task { apply(await link.ask(.stop(chatId: chatId))) }
    }

    /// The phone took the message: show it now and read the transcript fast while the turn starts.
    private func posted(_ text: String) {
        outbox = (WatchLine(id: "outbox-\(UUID().uuidString)", fromUser: true, text: text), Date())
        kick += 1
    }

    private func poll() async {
        while !Task.isCancelled {
            apply(await link.ask(.transcript(chatId: chatId)))
            try? await Task.sleep(for: .seconds(isRunning ? 2.5 : 8))
        }
    }

    private func apply(_ reply: WatchReply?) {
        loaded = true
        guard let reply, reply.ok else { failed = reply == nil; return }
        failed = false
        if let fresh = reply.lines {
            lines = fresh
            if let sent = outbox {
                let echoed = fresh.contains { $0.fromUser && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == sent.line.text }
                // Give up on the echo after a while so a reworded message can't stick around.
                if echoed || Date().timeIntervalSince(sent.at) > 20 { outbox = nil }
            }
        }
        if let state = reply.isRunning { running = state }
    }
}

/// Reply chips and dictation. Inline in a chat, or as a sheet for a new chat.
struct Composer: View {
    let link: PhoneLink
    let chatId: String?
    var running = false
    var inline = false
    enum Part { case all, field, quickReplies }
    var part: Part = .all
    var onStop: () -> Void = {}
    /// Called with the text once the phone accepts it.
    var posted: (String) -> Void = { _ in }
    var sent: (WatchReply?) -> Void = { _ in }
    static let fieldID = "composer.field"
    @Environment(\.dismiss) private var dismiss
    @State private var sending = false

    /// Quick answers, only inside an existing chat.
    private var chips: [LocalizedStringResource] {
        guard chatId != nil else { return [] }
        return ["Continue", "Looks good"]
    }

    var body: some View {
        VStack(alignment: HorizontalAlignment.leading, spacing: 5) {
            if !inline {
                PromptGlyph(size: 22).frame(maxWidth: .infinity)
                Text("New Chat").font(.headline).frame(maxWidth: .infinity)
            }
            if part != .quickReplies {
            HStack(spacing: 6) {
                // A slim field-like bar: tap to dictate or scribble.
                TextFieldLink(prompt: Text(chatId == nil ? "What should agy do?" : "Reply")) {
                    Label {
                        Text(sending ? "Sending…" : running ? "Reply when this turn ends." : (chatId == nil ? "Speak or scribble" : "Reply"))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    } icon: {
                        Image(systemName: "waveform").foregroundStyle(WatchStyle.sky)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .font(.subheadline)
                    .pill(.white.opacity(0.10), height: 32, inset: 12)
                } onSubmit: { text in send(text) }
                .buttonStyle(.plain)
                .disabled(sending || running)
                if running {
                    Button(action: onStop) {
                        Circle()
                            .fill(Color.red.opacity(0.85))
                            .overlay { Image(systemName: "stop.fill").imageScale(.small).bold() }
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Stop"))
                }
            }
            .id(Self.fieldID)
            }
            // Quick replies, stacked under the field; scroll down to reach them.
            if !running && part != .field {
                ForEach(chips, id: \.key) { chip in
                    Button { send(String(localized: chip)) } label: {
                        Text(chip)
                            .font(.footnote.weight(.medium))
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .pill(.white.opacity(0.14), height: 30, inset: 12)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func send(_ raw: String) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        sending = true
        Buzz.tap()
        Task {
            let reply = await link.ask(.prompt(chatId: chatId, text: text))
            sending = false
            if reply?.ok == true { posted(text) }
            sent(reply)
            if reply?.ok == true {
                if !inline { dismiss() }
            } else {
                Buzz.no()
            }
        }
    }
}

private struct Bubble: View {
    let line: WatchLine

    var body: some View {
        if line.fromUser {
            Text(line.text)
                .font(.footnote)
                .padding(EdgeInsets(top: 7, leading: 10, bottom: 7, trailing: 10))
                .background(WatchStyle.userBubble, in: .rect(cornerRadius: 14))
                .padding(.leading, 22)
                .frame(maxWidth: .infinity, alignment: .trailing)
        } else {
            MarkdownText(line.text)
                .font(.footnote)
        }
    }
}
