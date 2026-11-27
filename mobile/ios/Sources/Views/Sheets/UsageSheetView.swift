import SwiftUI

/// Context-window use for the open chat, plus the plan limits agy reports.
struct UsageSheetView: View {
    @Environment(\.dismiss) private var closeSheet
    @Bindable var viewModel: RemoteWorkspaceViewModel

    var body: some View {
        NavigationStack {
            Form {
                contextSection
                limitsSection
            }
            .refreshable { await pullRefresh() }
            .inlineTitle("Usage")
            .doneToolbar { closeSheet() }
        }
        .onAppear { viewModel.refreshQuota(force: true) }
    }

    private var contextSection: some View {
        Section {
            if let usage = viewModel.selectedSessionUsage {
                let share = usage.fraction
                let tint: Color = share > 0.9 ? AppColor.errorRed : (share > 0.75 ? AppColor.warningAmber : AppColor.primary)
                UsageBarRow(title: String(localized: "Used"),
                            value: share.formatted(.percent.precision(.fractionLength(0))),
                            fraction: share,
                            tint: tint,
                            caption: String(localized: "\(Self.compact(usage.used)) of \(Self.compact(usage.size)) tokens"))
            } else {
                Text("Open a chat to see how much of the context window it uses.")
                    .ink(AppColor.onSurfaceVariant)
            }
        } header: {
            Text.caption("Context")
        } footer: {
            if viewModel.selectedSessionUsage != nil {
                Text("agy reports the total only. For a breakdown by system prompt, files and messages, run /context in agy on your computer.")
            }
        }
    }

    private var limitsSection: some View {
        Section {
            if let snapshot = viewModel.quota, !snapshot.limits.isEmpty {
                ForEach(snapshot.limits) { QuotaLimitRow(limit: $0) }
                if let credits = snapshot.credits {
                    LabeledContent("AI credits") {
                        Text(verbatim: credits.formatted(.number.precision(.fractionLength(0...2))))
                    }
                }
            } else {
                Across(10) {
                    ProgressView()
                    Text("Checking your limits…").ink(AppColor.onSurfaceVariant)
                }
            }
        } header: {
            Text.caption("Plan limits remaining")
        } footer: {
            if let snapshot = viewModel.quota {
                let ago = snapshot.fetchedAt.formatted(.relative(presentation: .named))
                Text("From agy on your computer · updated \(ago)")
            }
        }
    }

    private func pullRefresh() async {
        let before = viewModel.quota?.fetchedAt
        viewModel.refreshQuota(force: true)
        let deadline = Date().addingTimeInterval(8)
        while Date() < deadline, !Task.isCancelled,
              viewModel.selectedDevice != nil,
              viewModel.quota?.fetchedAt == before {
            try? await Task.sleep(for: .milliseconds(150))
        }
    }

    /// 950, 4.2K, 37K, 1M, 1.5M
    static func compact(_ n: Int) -> String {
        if n < 1_000 { return "\(n)" }
        if n < 10_000 { return String(format: "%.1fK", Double(n) / 1_000) }
        if n < 1_000_000 { return "\(n / 1_000)K" }
        if n % 1_000_000 == 0 { return "\(n / 1_000_000)M" }
        return String(format: "%.1fM", Double(n) / 1_000_000)
    }
}
