// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import WidgetKit

/// What a complication is about; each is its own widget so a face can carry several.
enum ComplicationTopic: String, CaseIterable, Sendable {
    case status, approvals, quota

    var page: WatchPage {
        switch self {
        case .status: .now
        case .approvals: .approvals
        case .quota: .now
        }
    }
}

/// One complication drawn from a snapshot. The widget passes its family in; the app's debug
/// gallery draws the same view at each family's size.
struct ComplicationView: View {
    let topic: ComplicationTopic
    let family: WidgetFamily
    let snapshot: WatchSnapshot?

    private var running: WatchSnapshot.Chat? { snapshot?.running.first }
    private var waiting: Int { snapshot?.approvals.count ?? 0 }
    private var quota: Int? { snapshot?.quotaPercent }
    private var percentText: String { quota.map { String($0) + "%" } ?? "–" }

    var body: some View {
        switch family {
        case .accessoryCircular: circular
        case .accessoryRectangular: rectangular
        case .accessoryInline: inline
        case .accessoryCorner: corner
        default: rectangular
        }
    }

    @ViewBuilder private var circular: some View {
        switch topic {
        case .quota:
            Gauge(value: Double(quota ?? 0), in: 0...100) {
                PromptGlyph(size: 9, gradient: false)
            } currentValueLabel: {
                Text(quota.map { "\($0)" } ?? "–")
            }
            .gaugeStyle(.accessoryCircular)
            .tint(WatchStyle.brand)
        case .approvals:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 1) {
                    Image(systemName: "hand.raised.fill").imageScale(.small)
                    Text(waiting, format: .number).font(.title3.bold())
                }
                .foregroundStyle(waiting > 0 ? WatchStyle.amber : .secondary)
            }
        case .status:
            ZStack {
                AccessoryWidgetBackground()
                Circle()
                    .trim(from: 0, to: running == nil ? 1 : 0.78)
                    .stroke(running == nil ? AnyShapeStyle(Color.secondary.opacity(0.4)) : AnyShapeStyle(WatchStyle.ring),
                            style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(3)
                PromptGlyph(size: 15, gradient: running != nil)
            }
        }
    }

    @ViewBuilder private var rectangular: some View {
        VStack(alignment: .leading, spacing: 1) {
            switch topic {
            case .approvals:
                Label { Text("Approvals") } icon: { Image(systemName: "hand.raised.fill") }
                    .font(.headline)
                    .foregroundStyle(WatchStyle.amber)
                if waiting > 0 {
                    Text("\(waiting) waiting").font(.body)
                    if let first = snapshot?.approvals.first {
                        Text(first.summary).font(.system(.caption, design: .monospaced)).lineLimit(1)
                    }
                } else {
                    Text("Nothing to approve").font(.body).foregroundStyle(.secondary)
                }
            case .quota:
                HStack { PromptGlyph(size: 13); Text("Quota left") }.font(.headline)
                Gauge(value: Double(quota ?? 0), in: 0...100) { EmptyView() }
                    .gaugeStyle(.accessoryLinearCapacity)
                    .tint(WatchStyle.brand)
                Text(verbatim: percentText).font(.body)
            case .status:
                HStack(spacing: 4) {
                    PromptGlyph(size: 13, gradient: running != nil)
                    if let start = running?.runningSince {
                        Text(Date(timeIntervalSince1970: start), style: .timer).monospacedDigit()
                    } else {
                        Text(snapshot?.computer?.name ?? String(localized: "AGY Remote"))
                    }
                }
                .font(.headline)
                if let chat = running {
                    Text(chat.title).font(.body).lineLimit(1)
                    Text(chat.status.isEmpty ? String(localized: "Working") : chat.status)
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                } else if waiting > 0 {
                    Text("\(waiting) approvals waiting").font(.body).foregroundStyle(WatchStyle.amber)
                } else {
                    Text("Nothing running").font(.body).foregroundStyle(.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }

    @ViewBuilder private var inline: some View {
        switch topic {
        case .approvals:
            Text(waiting > 0 ? "\(waiting) approvals waiting" : "No approvals")
        case .quota:
            Text(quota.map { ">_ \($0)% left" } ?? ">_ –")
        case .status:
            if let chat = running {
                Text(verbatim: ">_ \(chat.status.isEmpty ? chat.title : chat.status)")
            } else if waiting > 0 {
                Text("\(waiting) approvals waiting")
            } else {
                Text(verbatim: ">_ \(String(localized: "Idle"))")
            }
        }
    }

    private var cornerValue: Double {
        switch topic {
        case .quota: Double(quota ?? 0) / 100
        case .approvals: waiting > 0 ? 1 : 0
        case .status: running == nil ? 0 : 1
        }
    }

    private var corner: some View {
        PromptGlyph(size: 18, gradient: cornerValue > 0)
            .widgetLabel {
                Gauge(value: cornerValue) {
                    Text(verbatim: ">_")
                } currentValueLabel: {
                    Text(verbatim: "")
                } minimumValueLabel: {
                    Text(verbatim: "")
                } maximumValueLabel: {
                    Text(topic == .quota ? (quota.map { "\($0)%" } ?? "") : (waiting > 0 ? "\(waiting)" : ""))
                }
                .tint(WatchStyle.brand)
            }
    }
}
