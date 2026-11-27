// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import WatchKit

/// The app is its chats: approvals pinned on top, then what's running, then recent chats.
/// The glanceable "now" lives in the complications and the Live Activity.
struct WatchRoot: View {
    let link: PhoneLink
    @State private var path = NavigationPath()
    @State private var picking = false
    @State private var composing = false

    var body: some View {
        NavigationStack(path: $path) {
            content
                .navigationTitle(Text("Chats"))
                .navigationDestination(for: String.self) { id in ChatDetail(link: link, chatId: id) }
                .containerBackground(WatchStyle.page(WatchStyle.violet), for: .navigation)
                .toolbar {
                    if link.snapshot?.computer != nil {
                        ToolbarItem(placement: .topBarLeading) {
                            corner("Computers", "desktopcomputer") { picking = true }
                        }
                        ToolbarItem(placement: .topBarTrailing) {
                            corner("New Chat", "square.and.pencil") { composing = true }
                        }
                    }
                }
                .sheet(isPresented: $picking) { ComputerPicker(link: link) { picking = false } }
                .sheet(isPresented: $composing) {
                    ScrollView { Composer(link: link, chatId: nil) }
                }
        }
        .onOpenURL { url in
            guard WatchPage(url: url) != nil else { return }
            // Every page lands on the list, where approvals and running chats are on top.
            path = NavigationPath()
        }
    }

    /// A round toolbar button showing only its symbol; the title is read by VoiceOver.
    private func corner(_ title: LocalizedStringKey, _ symbol: String, _ act: @escaping () -> Void) -> some View {
        Button(action: act) { Label(title, systemImage: symbol).labelStyle(.iconOnly) }
    }

    @ViewBuilder private var content: some View {
        if let snap = link.snapshot, snap.computer != nil {
            ChatList(link: link, snapshot: snap) { path.append($0) }
        } else if link.snapshot != nil {
            Notice(symbol: "laptopcomputer.slash", title: "No computer yet",
                   detail: "Pair a computer in AGY Remote on your iPhone.")
        } else if link.ready && !link.reachable {
            Notice(symbol: "iphone.gen3", title: "Open AGY Remote on your iPhone",
                   detail: "Your watch works through the app on your phone.")
        } else {
            ProgressView()
        }
    }
}

private struct ChatList: View {
    let link: PhoneLink
    let snapshot: WatchSnapshot
    let open: (String) -> Void

    var body: some View {
        List {
            header
            ForEach(Array(snapshot.approvals.enumerated()), id: \.element.id) { index, ask in
                ApprovalCard(link: link, approval: ask, primary: index == 0)
                    .listRowBackground(RoundedRectangle(cornerRadius: 18).fill(WatchStyle.amber.opacity(0.16)))
            }
            if !snapshot.running.isEmpty {
                Section {
                    ForEach(snapshot.running) { chat in row(chat) }
                } header: {
                    Text("Now running")
                }
            }
            Section {
                if snapshot.chats.isEmpty {
                    Text("No chats yet").foregroundStyle(.secondary)
                }
                ForEach(snapshot.idle) { chat in row(chat) }
            } header: {
                Text("Recent")
            }
        }
    }

    /// Which computer, whether it's online, and the plan left. Quiet, one line.
    private var header: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(snapshot.computer?.online == true ? WatchStyle.online : .gray)
                .frame(width: 6, height: 6)
            Text(snapshot.computer?.name ?? "")
                .fontWeight(.medium)
                .lineLimit(1)
            Spacer(minLength: 4)
            if !link.reachable {
                Image(systemName: "iphone.slash").foregroundStyle(WatchStyle.amber)
                    .accessibilityLabel(Text("Open AGY Remote on your iPhone"))
            } else if let quota = snapshot.quotaPercent {
                Text("\(quota)% left").foregroundStyle(.secondary).monospacedDigit()
            }
        }
        .font(.caption)
        .padding(.horizontal, 4)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 8))
    }

    private func row(_ chat: WatchSnapshot.Chat) -> some View {
        Button { open(chat.id) } label: { ChatRow(chat: chat) }
    }
}

struct Notice: View {
    let symbol: String
    let title: LocalizedStringKey
    let detail: LocalizedStringKey

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.title2)
                    .foregroundStyle(WatchStyle.brand)
                Text(title)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.top, 6)
        }
    }
}

enum Buzz {
    static func tap() { WKInterfaceDevice.current().play(.click) }
    static func yes() { WKInterfaceDevice.current().play(.success) }
    static func no() { WKInterfaceDevice.current().play(.failure) }
}
