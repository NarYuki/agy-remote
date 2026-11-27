// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

/// One waiting approval. The first card on screen answers a double tap with Allow.
struct ApprovalCard: View {
    let link: PhoneLink
    let approval: WatchSnapshot.Approval
    let primary: Bool
    @State private var busy = false
    @State private var failed = false
    @State private var showingAll = false

    private var allow: WatchSnapshot.Choice? {
        approval.choices.first { $0.kind == "allow_once" } ?? approval.choices.first(where: \.allows)
    }
    private var deny: WatchSnapshot.Choice? {
        approval.choices.first { $0.kind == "deny_once" } ?? approval.choices.first { !$0.allows }
    }
    private var others: [WatchSnapshot.Choice] {
        approval.choices.filter { $0.id != allow?.id && $0.id != deny?.id }
    }

    var body: some View {
        card.sheet(isPresented: $showingAll) { moreChoices }
    }

    private var card: some View {
        VStack(alignment: HorizontalAlignment.leading, spacing: 8) {
            Label("Needs approval", systemImage: "hand.raised.fill")
                .font(.caption2.weight(.semibold))
            .foregroundStyle(WatchStyle.amber)
            // All of it (the phone sends at most 300 characters): what is allowed is read in full.
            Text(approval.summary)
                .font(.system(.footnote, design: .monospaced))
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 9).fill(Color.black.opacity(0.35)))
            if !approval.chatTitle.isEmpty {
                Text(approval.chatTitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            if let allow {
                allowButton(allow)
            } else {
                Text("Too long to read here. Allow it on your iPhone.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 6) {
                if let deny { pill(deny.displayName, tone: .red) { answer(deny) } }
                if !others.isEmpty { pill(String(localized: "More"), tone: .white) { showingAll = true } }
            }
            .frame(maxWidth: .infinity)
            if failed {
                Text("Couldn't reach your iPhone.").font(.caption2).foregroundStyle(WatchStyle.amber)
            }
        }
        .padding(.vertical, 6)
        .disabled(busy)
    }

    @ViewBuilder private func allowButton(_ choice: WatchSnapshot.Choice) -> some View {
        let button = Button { answer(choice) } label: {
            Label(choice.displayName, systemImage: "checkmark")
                .font(.headline)
                .foregroundStyle(.black)
                .frame(maxWidth: .infinity)
                .pill(WatchStyle.mint, height: 36)
        }
        .buttonStyle(.plain)
        if primary { button.handGestureShortcut(.primaryAction) } else { button }
    }

    /// A small secondary action that hugs its label.
    private func pill(_ title: String, tone: Color, _ act: @escaping () -> Void) -> some View {
        Button(action: act) {
            Text(title)
                .font(.footnote.weight(.medium))
                .foregroundStyle(tone == .red ? Color.red : .primary)
                .pill(tone.opacity(tone == .red ? 0.18 : 0.12), inset: 12)
        }
        .buttonStyle(.plain)
    }

    private var moreChoices: some View {
        List(others) { choice in
            Button(choice.displayName) {
                showingAll = false
                answer(choice)
            }
        }
    }

    private func answer(_ choice: WatchSnapshot.Choice) {
        busy = true
        Task {
            let reply = await link.ask(.approve(chatId: approval.chatId, approvalId: approval.id, choiceId: choice.id))
            busy = false
            failed = reply?.ok != true
            if failed { Buzz.no() } else if choice.allows { Buzz.yes() } else { Buzz.tap() }
        }
    }
}

extension WatchSnapshot.Choice {
    /// agy's plain English answers ("Allow", "Deny") in the watch's language; any other label as sent.
    var displayName: String {
        switch (kind, label) {
        case ("allow_once", "Allow"): String(localized: "Allow")
        case ("deny_once", "Deny"): String(localized: "Deny")
        default: label
        }
    }
}
