// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import WidgetKit

@main
struct AGYComplications: WidgetBundle {
    var body: some Widget {
        StatusComplication()
        ApprovalsComplication()
        QuotaComplication()
    }
}

struct SnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: WatchSnapshot?

    /// Rises in the Smart Stack while something needs a look.
    var relevance: TimelineEntryRelevance? {
        guard let snap = snapshot else { return nil }
        if !snap.approvals.isEmpty { return TimelineEntryRelevance(score: 100) }
        if !snap.running.isEmpty { return TimelineEntryRelevance(score: 70) }
        return TimelineEntryRelevance(score: 0)
    }
}

struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: .now, snapshot: SamplePicture.busy)
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        completion(SnapshotEntry(date: .now, snapshot: context.isPreview ? SamplePicture.busy : SnapshotStore.load()))
    }

    /// The app reloads the timelines whenever the phone sends something new; the hourly
    /// refresh only catches a snapshot that went stale while the app was asleep.
    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let entry = SnapshotEntry(date: .now, snapshot: SnapshotStore.load())
        completion(Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(3600))))
    }
}

private let allFamilies: [WidgetFamily] = [.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner]

private struct TopicWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let topic: ComplicationTopic
    let entry: SnapshotEntry

    var body: some View {
        ComplicationView(topic: topic, family: family, snapshot: entry.snapshot)
            .widgetURL(topic.page.url)
            .containerBackground(for: .widget) { Color.clear }
    }
}

struct StatusComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "agy.status", provider: SnapshotProvider()) { entry in
            TopicWidgetView(topic: .status, entry: entry)
        }
        .configurationDisplayName(Text("Status"))
        .description(Text("The running chat, its step and how long it has run."))
        .supportedFamilies(allFamilies)
    }
}

struct ApprovalsComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "agy.approvals", provider: SnapshotProvider()) { entry in
            TopicWidgetView(topic: .approvals, entry: entry)
        }
        .configurationDisplayName(Text("Approvals"))
        .description(Text("Requests waiting for your answer."))
        .supportedFamilies(allFamilies)
    }
}

struct QuotaComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "agy.quota", provider: SnapshotProvider()) { entry in
            TopicWidgetView(topic: .quota, entry: entry)
        }
        .configurationDisplayName(Text("Quota"))
        .description(Text("How much of your plan is left."))
        .supportedFamilies(allFamilies)
    }
}
