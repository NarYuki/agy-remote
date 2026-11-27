import SwiftUI
import UIKit

private enum Ink {
    static var hairline: Color { AppColor.outlineVariant.opacity(0.55) }
    static var secondary: Color { AppColor.onSurfaceVariant }
    static var muted: Color { AppColor.onSurfaceMuted }
}

// MARK: - Agent activity

/// A reply cut into what came, in order: text, then the steps that ran before the next text, and so on.
enum ReplyPart: Identifiable {
    case text(id: Int, String)
    case steps(id: Int, [AgyToolCall])

    var id: String {
        switch self {
        case .text(let i, _): "t\(i)"
        case .steps(let i, _): "s\(i)"
        }
    }

    static func split(_ m: ChatMessage) -> [ReplyPart] {
        let text = m.text
        let utf16 = text.utf16
        func index(_ offset: Int) -> String.Index {
            let i = utf16.index(utf16.startIndex, offsetBy: min(max(offset, 0), utf16.count))
            return i.samePosition(in: text) ?? text.unicodeScalars.index(before: i)
        }
        var parts: [ReplyPart] = []
        var cursor = 0
        var group: [AgyToolCall] = []
        var groupAt = 0
        for call in m.toolCalls {
            let at = min(call.at ?? 0, utf16.count)
            let between = at > cursor ? String(text[index(cursor)..<index(at)]) : ""
            if !between.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                if !group.isEmpty { parts.append(.steps(id: groupAt, group)); group = [] }
                parts.append(.text(id: cursor, between.trimmingCharacters(in: .newlines)))
            }
            if at > cursor { cursor = at }
            if group.isEmpty { groupAt = cursor }
            group.append(call)
        }
        if !group.isEmpty { parts.append(.steps(id: groupAt, group)) }
        let rest = String(text[index(cursor)...])
        if !rest.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            parts.append(.text(id: cursor, rest.trimmingCharacters(in: .newlines)))
        }
        return parts
    }
}

/// What a turn is doing right now, at the bottom of its reply.
struct LiveStatusLine: View {
    let message: ChatMessage

    var body: some View {
        let live = message.activeStatusLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = live.isEmpty ? String(localized: "Thinking") : live
        HStack(spacing: 8) {
            ThinkingIndicatorView(size: 14).chatSize(18, 18)
            // A new status rises in as the old one lifts away and fades, instead of swapping in place.
            ZStack(alignment: .leading) {
                Text(label)
                    .chatType(14, Ink.secondary, .medium)
                    .lineLimit(1)
                    .id(label)
                    .transition(.asymmetric(
                        insertion: .offset(y: 9).combined(with: .opacity),
                        removal: .offset(y: -9).combined(with: .opacity)))
            }
            .clipped()
            .animation(.spring(response: 0.38, dampingFraction: 0.86), value: label)
            Spacer(minLength: 0)
        }
    }
}

/// Lines added and removed, like `+12 −3`.
struct LineDelta: View {
    let added: Int
    let removed: Int

    var body: some View {
        HStack(spacing: 4) {
            if added > 0 { Text(verbatim: "+\(added)").foregroundStyle(Color.green) }
            if removed > 0 { Text(verbatim: "\u{2212}\(removed)").foregroundStyle(AppColor.errorRed) }
        }
        .font(.system(size: 13, weight: .medium).monospacedDigit())
    }
}

/// Steps that ran between two pieces of reply text, folded into one line such as
/// "Ran 2 commands, edited a.txt +12 −3 >"; tapped, every step.
struct StepGroupView: View {
    let calls: [AgyToolCall]
    var thought = ""
    /// The turn is still running and this is its newest group.
    var live = false
    @State private var expanded = false

    private var totals: (added: Int, removed: Int) {
        calls.filter { $0.status == "completed" }.reduce((0, 0)) { ($0.0 + ($1.added ?? 0), $0.1 + ($1.removed ?? 0)) }
    }

    static func fileName(_ path: String?) -> String? {
        guard let path, !path.isEmpty else { return nil }
        return (path as NSString).lastPathComponent
    }

    private var summary: String {
        var order: [String] = []
        var byKind: [String: [AgyToolCall]] = [:]
        for c in calls {
            // Only a finished step gets a past-tense verb: one waiting for approval or denied
            // didn't edit anything, so it counts as a plain step.
            let k = c.status == "completed" && ["read", "edit", "execute", "search", "fetch", "delete"].contains(c.kind) ? c.kind : "other"
            if byKind[k] == nil { order.append(k) }
            byKind[k, default: []].append(c)
        }
        var pieces = order.map { k -> String in
            let list = byKind[k] ?? []
            let files = Set(list.compactMap { $0.path }).count
            let n = max(files, k == "read" || k == "edit" || k == "delete" ? 1 : list.count)
            let one = Self.fileName(list.first?.path)
            switch k {
            case "read": return files <= 1 && one != nil ? String(localized: "Read \(one!)") : String(localized: "Read \(n) files")
            case "edit": return files <= 1 && one != nil ? String(localized: "Edited \(one!)") : String(localized: "Edited \(n) files")
            case "delete": return String(localized: "Deleted \(n) files")
            case "execute": return String(localized: "Ran \(list.count) commands")
            case "search": return String(localized: "Searched \(list.count) times")
            case "fetch": return String(localized: "Fetched \(list.count) pages")
            default: return String(localized: "\(list.count) steps")
            }
        }
        if pieces.isEmpty { pieces = [String(localized: "Thought")] }
        return ListFormatter.localizedString(byJoining: pieces)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) { expanded.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Text(summary)
                        .chatType(14, Ink.secondary, .medium)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    let t = totals
                    if t.added + t.removed > 0 { LineDelta(added: t.added, removed: t.removed) }
                    Image(systemName: "chevron.right")
                        .chatType(10, Ink.muted, .semibold)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(PlainButtonStyle())

            if expanded {
                steps
                    .padding(.top, 10)
                    .chatInsets(0, 2, 0, 0)
                    .transition(AnyTransition.move(edge: .top).combined(with: .opacity))
            }
        }
    }

    private struct Step: Identifiable {
        enum State { case running, done, failed }
        let id: String
        let symbol: String
        let title: String
        let detail: String?
        let state: State
        var added = 0
        var removed = 0
    }

    private var stepList: [Step] {
        var out: [Step] = []
        if !thought.isEmpty {
            out.append(Step(id: "thought", symbol: "brain", title: String(localized: "Thought"), detail: thought, state: .done))
        }
        for (i, call) in calls.enumerated() {
            let state: Step.State
            switch call.status {
            case "failed": state = .failed
            case "completed": state = .done
            default: state = (live && i == calls.count - 1) ? .running : .done
            }
            // A file step reads as its file name; the tool's own title ("File a.txt") says less.
            let title = Self.fileName(call.path) ?? (call.title.isEmpty ? call.name : call.title)
            out.append(Step(id: call.id, symbol: Self.symbol(for: call), title: title,
                            detail: call.detail.flatMap { $0.isEmpty ? nil : $0 }, state: state,
                            added: call.added ?? 0, removed: call.removed ?? 0))
        }
        return out
    }

    /// Every step; past about seven rows the list scrolls inside itself, kept at the newest step.
    private var steps: some View {
        let list = stepList
        let rows = VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(list.enumerated()), id: \.element.id) { index, step in
                stepRow(step, last: index == list.count - 1)
            }
        }
        return ViewThatFits(in: .vertical) {
            rows
            ScrollView(.vertical) { rows }
                .defaultScrollAnchor(.bottom)
                .scrollIndicators(.visible)
        }
        .frame(maxHeight: 320)
    }

    private func stepRow(_ step: Step, last: Bool) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 0) {
                ZStack {
                    Circle().fill(AppColor.surfaceContainer)
                    if step.state == .running {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: step.state == .failed ? "exclamationmark" : step.symbol)
                            .chatType(10.5, step.state == .failed ? AppColor.errorRed : Ink.secondary, .semibold)
                    }
                }
                .chatSize(24, 24)
                if !last {
                    Rectangle().fill(Ink.hairline).frame(width: 1).frame(maxHeight: .infinity)
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(step.title)
                        .chatType(13.5, AppColor.onSurface)
                        .lineLimit(2)
                    if step.added + step.removed > 0 { LineDelta(added: step.added, removed: step.removed) }
                }
                if let detail = step.detail {
                    Text(detail)
                        .chatType(13, Ink.secondary)
                        .lineLimit(8)
                }
            }
            .padding(.top, 3)
            .padding(.bottom, last ? 0 : 12)
            Spacer(minLength: 0)
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    static func duration(_ seconds: Double) -> String {
        let f = DateComponentsFormatter()
        f.unitsStyle = .abbreviated
        f.allowedUnits = [.hour, .minute, .second]
        f.maximumUnitCount = 2
        return f.string(from: max(1, seconds.rounded())) ?? "1s"
    }

    static func symbol(for call: AgyToolCall) -> String {
        switch call.kind {
        case "read": "doc.text"
        case "edit": "pencil"
        case "delete": "trash"
        case "move": "arrow.right.doc.on.clipboard"
        case "search": "magnifyingglass"
        case "execute": "terminal"
        case "fetch": "globe"
        case "think": "brain"
        case "switch_mode": "arrow.triangle.branch"
        default: "circle.dashed"
        }
    }
}

// MARK: - Plan

/// The mark in front of a plan step: done, under way (pulsing while the turn runs), or to do.
struct PlanMark: View {
    let status: AgyPlanEntry.Status
    var live = false

    var body: some View {
        switch status {
        case .completed:
            Image(systemName: "checkmark.circle.fill").chatType(13, AppColor.primary)
        case .inProgress:
            Image(systemName: "circle.dotted.circle")
                .chatType(13, AppColor.onSurface, .semibold)
                .symbolEffect(.pulse, isActive: live)
        case .pending:
            Image(systemName: "circle").chatType(13, Ink.muted)
        }
    }
}

/// The plan of a turn that is still running, shown like the other notices above the input: folded,
/// one line with the step under way and how far along it is; tapped, the whole list, scrolling
/// past a set height.
struct PlanDockView: View {
    let entries: [AgyPlanEntry]
    @Binding var open: Bool

    private var done: Int { entries.filter { $0.status == .completed }.count }
    private var currentIndex: Int {
        entries.firstIndex { $0.status == .inProgress } ?? entries.firstIndex { $0.status == .pending } ?? max(0, entries.count - 1)
    }
    private var current: AgyPlanEntry? { entries.isEmpty ? nil : entries[currentIndex] }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if open {
                HStack(spacing: 8) {
                    Image(systemName: "checklist").chatFont(12, .semibold)
                    Text("Plan").chatFont(12.5, .semibold)
                    Spacer()
                    count
                    chevron
                }
                .foregroundStyle(AppColor.onSurfaceVariant)
                // Opened at the step under way, so where things stand shows at a glance.
                ScrollViewReader { reader in
                    ScrollView(.vertical) {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(entries.indices, id: \.self) { i in
                                HStack(alignment: .firstTextBaseline, spacing: 10) {
                                    PlanMark(status: entries[i].status, live: true).frame(width: 16)
                                    Text(entries[i].content)
                                        .chatType(13, entries[i].status == .completed ? Ink.muted : AppColor.onSurface)
                                        .strikethrough(entries[i].status == .completed, color: Ink.muted)
                                        .fixedSize(horizontal: false, vertical: true)
                                    Spacer(minLength: 0)
                                }
                                .id(i)
                            }
                        }
                    }
                    .scrollIndicators(.visible)
                    .scrollBounceBehavior(.basedOnSize)
                    .frame(maxHeight: 240)
                    .fixedSize(horizontal: false, vertical: true)
                    .onAppear { reader.scrollTo(currentIndex, anchor: .center) }
                    .onChange(of: currentIndex) { _, i in withAnimation(.smooth) { reader.scrollTo(i, anchor: .center) } }
                }
                .transition(.opacity)
            } else if let step = current {
                HStack(spacing: 8) {
                    PlanMark(status: step.status, live: true)
                    Text(step.content).chatFont(12.5, .medium).foregroundStyle(AppColor.onSurface).lineLimit(1)
                    Spacer(minLength: 4)
                    count
                    chevron
                }
                .transition(.opacity)
            }
        }
        .chatPad(14, 8)
        .background(chatRound(16).fill(AppColor.surfaceContainerLow.opacity(0.9)))
        .chatOutline(chatRound(16), AppColor.outlineVariant.opacity(0.6))
        .padding(.horizontal, 20)
        .contentShape(Rectangle())
        .onTapGesture { toggle(!open) }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityValue(open ? Text("Expanded") : Text("Collapsed"))
        .animation(.easeOut(duration: 0.2), value: entries)
    }

    private var chevron: some View {
        Image(systemName: "chevron.up").chatFont(10, .semibold).foregroundStyle(Ink.muted)
            .rotationEffect(.degrees(open ? 180 : 0))
    }

    private var count: some View {
        Text(verbatim: "\(done)/\(entries.count)").monospacedDigit().chatFont(12, .medium).foregroundStyle(Ink.muted)
    }

    private func toggle(_ to: Bool) {
        guard to != open else { return }
        Haptics.select()
        withAnimation(.smooth(duration: 0.28)) { open = to }
    }
}

struct PlanCardView: View {
    let entries: [AgyPlanEntry]
    var isStreaming: Bool = false

    var body: some View {
        let done = entries.filter { $0.status == .completed }.count
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Plan").chatType(13, AppColor.onSurface, .semibold)
                Spacer()
                Text(verbatim: "\(done)/\(entries.count)")
                    .monospacedDigit()
                    .chatFont(12, .medium)
                    .foregroundStyle(Ink.muted)
            }
            VStack(alignment: HorizontalAlignment.leading, spacing: 8) {
                ForEach(entries.indices, id: \.self) { index in
                    let entry = entries[index]
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        PlanMark(status: entry.status, live: isStreaming).frame(width: 16)
                        Text(entry.content)
                            .chatType(14, entry.status == .completed ? Ink.muted : AppColor.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                            .strikethrough(entry.status == .completed, color: Ink.muted)
                    }
                }
            }
        }
        .padding(14)
        .chatOutline(chatRound(14), Ink.hairline)
        .animation(.easeOut(duration: 0.2), value: entries)
    }
}

// MARK: - Permission

struct PermissionCardView: View {
    let request: PendingPermissionRequest
    let onSelect: (String) -> Void

    private enum Subject {
        case command(String)
        case edit(path: String, before: String, after: String)
        case write(path: String, lines: Int)
        case other(String)

        var symbol: String {
            switch self {
            case .command: "terminal"
            case .edit: "pencil"
            case .write: "doc.badge.plus"
            case .other: "hand.raised"
            }
        }
    }

    @State private var subject: Subject?

    private static let pathKeys = "Path path file_path TargetFile".split(separator: " ").map(String.init)

    private static func makeSubject(_ request: PendingPermissionRequest) -> Subject {
        let object = (request.detail.data(using: .utf8)).flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any] ?? [:]
        func first(_ keys: [String]) -> String? {
            for k in keys { if let s = object[k] as? String, !s.isEmpty { return s } }
            return nil
        }
        if let cmd = first(["CommandLine", "command", "cmd"]) { return .command(cmd) }
        let path = first(Self.pathKeys)
        if let path, let before = first(["Target", "old_string", "TargetContent"]),
           let after = first(["Replacement", "new_string", "ReplacementContent"]) {
            return .edit(path: path, before: before, after: after)
        }
        if let content = first(["Content", "content", "CodeContent"]) {
            return .write(path: path ?? request.title, lines: content.components(separatedBy: "\n").count)
        }
        return .other(request.detail)
    }

    private var resolved: Subject { subject ?? Self.makeSubject(request) }

    var body: some View {
        Group {
            if let chosen = request.selectedOptionId {
                answered(chosen).transition(.opacity)
            } else {
                question.transition(.opacity.combined(with: .scale(scale: 0.98)))
            }
        }
        .onAppear { if subject == nil { subject = Self.makeSubject(request) } }
    }

    private static func fileName(_ path: String) -> String { (path as NSString).lastPathComponent }

    private var question: some View {
        let s = resolved
        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: s.symbol).chatFont(12.5, .semibold)
                Text("Needs your approval").chatFont(12.5, .semibold)
            }
            .foregroundStyle(Ink.secondary)

            details(s)

            HStack(spacing: 8) {
                let allows = request.options.filter { $0.kind.hasPrefix("allow") }
                ForEach(request.options.filter { !$0.kind.hasPrefix("allow") }) { option in
                    PermissionButton(title: option.displayName, prominent: false) { onSelect(option.optionId) }
                }
                Spacer(minLength: 0)
                ForEach(allows) { option in
                    PermissionButton(title: option.displayName, prominent: option.kind == "allow_once" || allows.count == 1) { onSelect(option.optionId) }
                }
            }
        }
        .padding(14)
        .chatOutline(chatRound(14), AppColor.outlineVariant)
    }

    @ViewBuilder
    private func details(_ s: Subject) -> some View {
        let title = Font.system(size: 15, weight: .medium)
        switch s {
        case .command(let cmd):
            VStack(alignment: .leading, spacing: 8) {
                Text("Run a command").font(title).foregroundStyle(AppColor.onSurface)
                codeBox(Text(verbatim: cmd).foregroundStyle(AppColor.onSurface), lines: 8)
            }
        case .write(let path, let lines):
            VStack(alignment: .leading, spacing: 4) {
                let name = Self.fileName(path)
                Text("Create \(name)").font(title).foregroundStyle(AppColor.onSurface)
                Text("\(path) · \(lines) lines")
                    .chatType(12.5, Ink.muted)
                    .lineLimit(2).truncationMode(.head)
            }
        case .edit(let path, let before, let after):
            VStack(alignment: .leading, spacing: 8) {
                Text("Edit \(Self.fileName(path))").font(title).foregroundStyle(AppColor.onSurface)
                codeBox(Self.diff(before, after), lines: 10)
            }
        case .other(let raw):
            VStack(alignment: .leading, spacing: 8) {
                if request.title.isEmpty {
                    Text("Use a tool").font(title).foregroundStyle(AppColor.onSurface)
                } else {
                    Text(request.title).font(title).foregroundStyle(AppColor.onSurface)
                }
                if !raw.isEmpty {
                    codeBox(Text(verbatim: raw).foregroundStyle(Ink.secondary), lines: 6)
                }
            }
        }
    }

    /// The whole text, never cut: what is approved must be readable to the end. Past `lines`
    /// lines it scrolls.
    private func codeBox(_ text: Text, lines: Int) -> some View {
        let body = text
            .chatFont(12.5, mono: true)
            .chatWide(.leading)
            .padding(10)
        return ViewThatFits(in: .vertical) {
            body
            ScrollView(.vertical) { body }
        }
        .frame(maxHeight: CGFloat(lines) * 17 + 20)
        .background(AppColor.surfaceContainer, in: chatRound(8))
        .environment(\.layoutDirection, .leftToRight)
    }

    private static func diff(_ before: String, _ after: String) -> Text {
        var old = before.components(separatedBy: "\n")
        var new = after.components(separatedBy: "\n")
        while let a = old.first, let b = new.first, a == b { old.removeFirst(); new.removeFirst() }
        while let a = old.last, let b = new.last, a == b { old.removeLast(); new.removeLast() }
        var removed = old.map { "- " + $0 }
        if old.count > 4 && !new.isEmpty {
            let extra = old.count - 3
            removed = Array(removed.prefix(3)) + [String(localized: "… \(extra) more")]
        }
        let added = new.map { "+ " + $0 }
        var out = AttributedString()
        func line(_ s: String, _ color: Color) {
            if !out.characters.isEmpty { out.append(AttributedString("\n")) }
            var piece = AttributedString(s)
            piece.foregroundColor = color
            out.append(piece)
        }
        removed.forEach { line($0, AppColor.errorRed) }
        added.forEach { line($0, AppColor.primary) }
        return Text(out)
    }

    private func answered(_ chosen: String) -> some View {
        let option = request.options.first { $0.optionId == chosen }
        let allowed = option?.kind.hasPrefix("allow") ?? false
        let s = resolved
        let summary: String
        var mono = false
        switch s {
        case .command(let cmd): summary = cmd; mono = true
        case .write(let path, _): summary = String(localized: "Create \(Self.fileName(path))")
        case .edit(let path, _, _): summary = String(localized: "Edit \(Self.fileName(path))")
        case .other: summary = request.title
        }
        return HStack(spacing: 8) {
            Image(systemName: allowed ? "checkmark" : "xmark")
                .chatType(11, allowed ? AppColor.primary : AppColor.errorRed, .bold)
            Text(verbatim: summary)
                .font(.system(size: 13, design: mono ? .monospaced : .default))
                .foregroundStyle(Ink.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer(minLength: 4)
            if let option {
                Text(option.displayName).chatType(12, Ink.muted, .medium)
            }
        }
    }
}

/// A question from the agent (`ask_question`): pick options (one, or several where allowed), or
/// write an answer where the agent accepts one, then send. Skip lets the agent go on without one.
struct QuestionCardView: View {
    let request: PendingPermissionRequest
    let onSend: ([String: Any]?) -> Void

    @State private var picked: [String: Set<String>] = [:]
    @State private var written: [String: String] = [:]
    /// Set the moment Send or Skip is tapped, so the card closes at once (and only once) without
    /// waiting for the transcript to catch up.
    @State private var sent: String?
    @FocusState private var typing: Bool

    private func answered(_ q: FormQuestion) -> Bool {
        !(picked[q.key] ?? []).isEmpty || !(written[q.key] ?? "").trimmingCharacters(in: .whitespaces).isEmpty
    }

    private var complete: Bool { request.questions.allSatisfy(answered) }

    var body: some View {
        let done = sent ?? request.selectedOptionId
        VStack(alignment: .leading, spacing: 0) {
            if request.isStale && done == nil {
                closedLine(Text("This question is no longer waiting."), symbol: "clock")
                    .transition(.opacity)
            } else if let done {
                closedLine(done == "skipped" ? Text("Skipped") : Text("Answered"),
                           symbol: done == "skipped" ? "arrow.uturn.right" : "checkmark")
                    .transition(.opacity.combined(with: .offset(y: 6)))
            } else {
                form.transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipped()
    }

    private func finish(_ answers: [String: Any]?) {
        guard sent == nil else { return }
        typing = false
        // One transaction for the whole transcript, so the rows around the card glide with it
        // instead of jumping when it shrinks.
        withAnimation(.spring(response: 0.45, dampingFraction: 0.9)) { sent = answers == nil ? "skipped" : "answered" }
        onSend(answers)
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: "questionmark.bubble").chatFont(12.5, .semibold)
                Text("The agent has a question").chatFont(12.5, .semibold)
            }
            .foregroundStyle(Ink.secondary)

            ForEach(request.questions, id: \.key) { q in
                VStack(alignment: .leading, spacing: 8) {
                    Text(verbatim: q.question).font(.system(size: 15, weight: .medium)).foregroundStyle(AppColor.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                    if q.multi {
                        Text("Select all that apply").chatType(12, Ink.muted)
                    }
                    ForEach(q.options, id: \.id) { option in optionRow(q, option) }
                    if q.otherKey != nil {
                        TextField(text: Binding(get: { written[q.key] ?? "" }, set: { written[q.key] = $0 })) { Text("Other answer") }
                            .focused($typing)
                            .submitLabel(.send)
                            .onSubmit { if complete { finish(answers()) } }
                            .chatFont(14)
                            .padding(.horizontal, 12).frame(height: 38)
                            .background(chatRound(10).fill(AppColor.surfaceContainer))
                    }
                }
            }

            HStack(spacing: 8) {
                PermissionButton(title: String(localized: "Skip"), prominent: false) { finish(nil) }
                Spacer(minLength: 0)
                PermissionButton(title: String(localized: "Send"), prominent: true) { finish(answers()) }
                    .disabled(!complete)
                    .opacity(complete ? 1 : 0.4)
            }
        }
        .padding(14)
        .chatOutline(chatRound(14), AppColor.outlineVariant)
    }

    private func optionRow(_ q: FormQuestion, _ option: FormQuestion.Option) -> some View {
        let on = picked[q.key]?.contains(option.id) == true
        return Button {
            var set = picked[q.key] ?? []
            if q.multi {
                if on { set.remove(option.id) } else { set.insert(option.id) }
            } else {
                set = on ? [] : [option.id]
            }
            withAnimation(.easeOut(duration: 0.12)) { picked[q.key] = set }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: q.multi ? (on ? "checkmark.square.fill" : "square") : (on ? "largecircle.fill.circle" : "circle"))
                    .chatType(16, on ? AppColor.primary : Ink.secondary)
                Text(verbatim: option.label).chatFont(14.5).foregroundStyle(AppColor.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func answers() -> [String: Any] {
        var out: [String: Any] = [:]
        for q in request.questions {
            let set = picked[q.key] ?? []
            if q.multi {
                out[q.key] = q.options.map(\.id).filter(set.contains)
            } else if let one = set.first {
                out[q.key] = one
            }
            if let other = q.otherKey, let text = written[q.key]?.trimmingCharacters(in: .whitespaces), !text.isEmpty {
                out[other] = text
            }
        }
        return out
    }

    private func closedLine(_ text: Text, symbol: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: symbol).chatType(11, Ink.secondary, .bold)
            text.chatFont(13).foregroundStyle(Ink.secondary)
            Text(verbatim: request.questions.first?.question ?? "").chatFont(13).foregroundStyle(Ink.muted).lineLimit(1)
            Spacer(minLength: 0)
        }
    }
}

private struct PermissionButton: View {
    let title: String
    let prominent: Bool
    let action: @MainActor () -> Void

    var body: some View {
        Button(action: { action() }) {
            Text(title)
                .chatFont(13.5, .semibold)
                .lineLimit(1)
                .foregroundStyle(prominent ? AppColor.surface : AppColor.onSurface)
                .padding(.horizontal, 14)
                .frame(height: 34)
                .background {
                    if prominent {
                        Capsule().fill(AppColor.onSurface)
                    } else {
                        Capsule().strokeBorder(Ink.hairline, lineWidth: 1)
                    }
                }
                .contentShape(Capsule())
        }
        .buttonStyle(FadePressStyle())
    }
}

private struct FadePressStyle: ButtonStyle {
    func makeBody(configuration c: Configuration) -> some View {
        let down = c.isPressed
        return c.label.opacity(down ? 0.6 : 1)
    }
}

// MARK: - Actions

struct MessageActionsView: View {
    let text: String
    let onCopy: () -> Void
    let onRerun: () -> Void
    @State private var copied = false

    var body: some View {
        HStack(spacing: 4) {
            Button {
                onCopy()
                Haptics.success()
                copied = true
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 1_400_000_000)
                    copied = false
                }
            } label: {
                icon(copied ? "checkmark" : "doc.on.doc").contentTransition(.symbolEffect(.replace))
            }
            .accessibilityLabel(Text("Copy"))

            ShareLink(item: text) { icon("square.and.arrow.up") }
                .accessibilityLabel(Text("Share"))

            Button(action: onRerun) { icon("arrow.clockwise") }
                .accessibilityLabel(Text("Retry"))
            Spacer(minLength: 0)
        }
        .buttonStyle(PlainButtonStyle())
    }

    private func icon(_ name: String) -> some View {
        Image(systemName: name)
            .chatType(14, Ink.muted)
            .chatSize(34, 30)
            .contentShape(Rectangle())
    }
}

// MARK: - Unavailable history

struct UnavailableHistoryView: View {
    var cliUnsaved: Bool = false

    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: cliUnsaved ? "terminal" : "macwindow")
                .chatType(30, Ink.muted, .light)
            Group {
                if cliUnsaved { Text("Can't show this chat yet") } else { Text("Open this chat on your computer") }
            }
            .chatType(17, AppColor.onSurface, .semibold)
            Group {
                if cliUnsaved {
                    Text("It's open in the agy CLI, but agy couldn't save it where other apps can read it. Follow it in the terminal for now.")
                } else {
                    Text("It was started in the Antigravity desktop app. Only chats from the agy CLI can be shown and continued here.")
                }
            }
            .chatType(14.5, Ink.secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: 320)
            Spacer()
            Spacer()
        }
        .padding(.horizontal, 32)
    }
}

// MARK: - Streaming text

struct StreamingTextView: View {
    let text: String
    let isStreaming: Bool

    /// UTF-8 bytes shown so far, or nil when everything is shown.
    @State private var revealed: Int?
    @State private var ticker: Task<Void, Never>?
    /// How fast text arrives (bytes per second), measured from the chunks; the reveal runs at
    /// that pace, so a reply that comes in bursts reads as one steady flow.
    @State private var pace: Double = 600
    @State private var lastArrival: Date?
    @State private var lastLength = 0

    private var visible: String {
        guard isStreaming, let revealed else { return text }
        let bytes = text.utf8
        guard revealed < bytes.count else { return text }
        var idx = bytes.index(bytes.startIndex, offsetBy: revealed)
        while idx > bytes.startIndex, idx.samePosition(in: text.unicodeScalars) == nil || !isCharacterBoundary(idx) {
            idx = bytes.index(before: idx)
        }
        return String(text[..<idx])
    }

    private func isCharacterBoundary(_ i: String.Index) -> Bool {
        i.samePosition(in: text) != nil
    }

    var body: some View {
        MarkdownMessageView(text: visible).equatable()
            .onAppear {
                lastLength = text.utf8.count
                if isStreaming {
                    revealed = text.utf8.count
                    startTicker()
                } else {
                    revealed = nil
                }
            }
            .onDisappear { stopTicker(); revealed = nil }
            .onChange(of: isStreaming) { _, now in
                if !now { stopTicker(); revealed = nil }
            }
            .onChange(of: text) { _, new in
                measure(new.utf8.count)
                if isStreaming, revealed != nil, ticker == nil { startTicker() }
            }
    }

    /// Updates `pace` from the size of the new chunk and the time since the last one.
    private func measure(_ length: Int) {
        let now = Date()
        defer { lastArrival = now; lastLength = length }
        guard let last = lastArrival, length > lastLength else { return }
        let gap = max(0.05, now.timeIntervalSince(last))
        let rate = min(20_000, Double(length - lastLength) / gap)
        pace = pace * 0.6 + rate * 0.4
    }

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { @MainActor in
            let frame = 1.0 / 60
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(16))
                guard let current = revealed else { break }
                let target = text.utf8.count
                let backlog = target - current
                if backlog > 0 {
                    var step = max(1, Int(pace * frame))
                    // Fallen behind by more than about a third of a second: catch up gently.
                    let slack = Int(pace * 0.35)
                    if backlog > slack { step += (backlog - slack) / 6 }
                    revealed = min(target, current + step)
                } else if !isStreaming {
                    revealed = nil
                    break
                }
            }
            ticker = nil
        }
    }

    private func stopTicker() {
        ticker?.cancel()
        ticker = nil
    }
}

// MARK: - Notices

struct OpenInCliNotice: View {
    let hostName: String

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Image(systemName: "terminal")
                .chatFont(12, Font.Weight.semibold)
            Group {
                if hostName.isEmpty {
                    Text("Open in the agy CLI · updating live. Close it there to reply here.")
                } else {
                    Text("Open in the agy CLI on \(hostName) · updating live. Close it there to reply here.")
                }
            }
            .chatFont(12.5, .medium)
            .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(AppColor.onSurfaceVariant)
        .chatPad(14, 8)
        .background(chatRound(16).fill(AppColor.surfaceContainerLow.opacity(0.9)))
        .chatOutline(chatRound(16), AppColor.outlineVariant.opacity(0.6))
        .padding(.horizontal, 20)
    }
}

struct VersionNotice: View {
    let icon: String
    let text: Text

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Image(systemName: icon)
                .chatFont(12, Font.Weight.semibold)
            text
                .chatFont(12.5, .medium)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(AppColor.onSurface)
        .chatPad(14, 8)
        .background(chatRound(16).fill(AppColor.surfaceContainerLow.opacity(0.95)))
        .chatOutline(chatRound(16), AppColor.primary.opacity(0.5))
        .padding(.horizontal, 20)
    }
}

struct AvailableAgainBanner: View {
    let chatTitle: String

    var body: some View {
        HStack(spacing: 12) {
            let okInk = AppColor.connectedGreen
            Image(systemName: "checkmark.circle.fill").chatType(20, okInk)
            VStack(alignment: HorizontalAlignment.leading, spacing: 1) {
                Text("You can reply here now")
                    .chatType(14.5, AppColor.onSurface, .semibold)
                Group {
                    if chatTitle.isEmpty { Text("The agy CLI session was released.") } else { Text("\(chatTitle): agy CLI session released.") }
                }
                .chatType(12.5, AppColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .chatPad(16, 10)
        .chatGlass(chatRound(20))
        .padding(.horizontal, 20)
    }
}

struct ConnectionStatusView: View {
    let device: RemoteDevice

    private var connecting: Bool { device.isConnecting }

    var body: some View {
        VStack(spacing: 10) {
            Spacer()
            if connecting {
                ThinkingIndicatorView(size: 20).chatSize(28, 28)
                Text("Connecting to \(device.name)…")
                    .chatType(14, AppColor.onSurfaceVariant)
            } else {
                Image(systemName: device.deviceIconName)
                    .chatType(24, AppColor.onSurfaceVariant, .light)
                    .chatSize(28, 28)
                Text("Can't reach \(device.name)")
                    .chatType(14, AppColor.onSurface, .medium)
                Text("Make sure it's on and agy-connector is running. Retrying…")
                    .chatType(13, AppColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 280)
            }
            Spacer()
        }
        .animation(.melt, value: connecting)
    }
}

// MARK: - Shared chat styling shorthands

extension View {
    /// System font at `size` in `color`; `mono` picks the monospaced design.
    func chatType(_ size: CGFloat, _ color: Color, _ weight: Font.Weight = .regular, mono: Bool = false) -> some View {
        font(.system(size: size, weight: weight, design: mono ? .monospaced : .default)).foregroundStyle(color)
    }

    func chatInsets(_ top: CGFloat, _ leading: CGFloat, _ bottom: CGFloat, _ trailing: CGFloat) -> some View {
        padding(EdgeInsets(top: top, leading: leading, bottom: bottom, trailing: trailing))
    }

    func chatPad(_ h: CGFloat, _ v: CGFloat) -> some View {
        padding(EdgeInsets(top: v, leading: h, bottom: v, trailing: h))
    }

    func chatGlass<S: Shape>(_ shape: S, tint: Color? = nil) -> some View {
        glassEffect(tint.map { Glass.regular.tint($0).interactive() } ?? Glass.regular.interactive(), in: shape)
    }
}

extension View {
    func chatCapsule(_ color: Color) -> some View { background(Capsule().fill(color)) }
    func chatWide(_ alignment: Alignment = .leading) -> some View { frame(maxWidth: .infinity, alignment: alignment) }
    func chatSize(_ width: CGFloat, _ height: CGFloat) -> some View { frame(width: width, height: height) }
}

extension View {
    func chatFont(_ size: CGFloat, _ weight: Font.Weight = .regular, mono: Bool = false) -> some View {
        font(.system(size: size, weight: weight, design: mono ? .monospaced : .default))
    }

    func chatOutline<S: InsettableShape>(_ shape: S, _ color: Color) -> some View {
        overlay(shape.strokeBorder(color, lineWidth: 1))
    }
}

func chatRound(_ radius: CGFloat) -> RoundedRectangle {
    RoundedRectangle(cornerRadius: radius, style: .continuous)
}
