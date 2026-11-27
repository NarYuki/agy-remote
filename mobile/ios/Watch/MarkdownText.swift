// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

/// Agent replies arrive as Markdown. This draws the parts that matter on a wrist: inline
/// emphasis, code and links; headings; lists; and fenced code as a small scrolling panel.
/// Long replies start folded at about `foldAt` visible characters, cut only between words of
/// already-parsed text, never inside Markdown syntax.
struct MarkdownText: View {
    private let blocks: [MDBlock]
    @State private var expanded = false

    static let foldAt = 500
    static let codeLines = 8

    init(_ source: String) {
        blocks = MDBlock.parse(source)
    }

    var body: some View {
        let shown = expanded ? Fold(blocks: blocks, all: true) : Fold(blocks: blocks, all: false)
        VStack(alignment: .leading, spacing: 6) {
            ForEach(shown.items.indices, id: \.self) { i in
                MDBlockView(block: shown.items[i])
            }
            if shown.cut {
                Button { withAnimation(.snappy) { expanded = true } } label: {
                    Text("Show more")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(WatchStyle.sky)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }
}

/// The blocks that fit in the folded budget (or all of them).
private struct Fold {
    var items: [MDBlock] = []
    var cut = false

    init(blocks: [MDBlock], all: Bool) {
        guard !all else { items = blocks; return }
        var left = MarkdownText.foldAt
        for block in blocks {
            if left <= 0 { cut = true; break }
            let cost = block.weight
            if cost <= left {
                items.append(block)
                left -= cost
                continue
            }
            if let part = block.shortened(to: left) { items.append(part) }
            cut = true
            break
        }
    }
}

enum MDBlock {
    case text(AttributedString)
    case heading(AttributedString)
    case item(marker: String, AttributedString)
    case code([String])

    /// Visible characters, used for the fold.
    var weight: Int {
        switch self {
        case .text(let a), .heading(let a), .item(_, let a): Self.cost(of: a.characters)
        case .code(let lines): min(lines.count, MarkdownText.codeLines) * 30
        }
    }

    /// A kept line break fills the rest of a wrist row, so it costs like a short line of text.
    static let breakCost = 24

    static func cost<S: Sequence>(of chars: S) -> Int where S.Element == Character {
        chars.reduce(0) { $0 + ($1 == "\n" ? breakCost : 1) }
    }

    /// The same block cut at a word boundary of its rendered text, with "…".
    func shortened(to budget: Int) -> MDBlock? {
        func cut(_ a: AttributedString) -> AttributedString? {
            let chars = a.characters
            guard budget > 40, Self.cost(of: chars) > budget else { return nil }
            // Walk forward until the budget is spent, so line breaks count for their height.
            var end = chars.startIndex
            var spent = 0
            while end < chars.endIndex {
                spent += chars[end] == "\n" ? Self.breakCost : 1
                if spent > budget { break }
                end = chars.index(after: end)
            }
            if let space = chars[chars.startIndex..<end].lastIndex(where: \.isWhitespace),
               Self.cost(of: chars[chars.startIndex..<space]) > budget / 2 {
                end = space
            }
            var head = AttributedString(a[a.startIndex..<end])
            head.append(AttributedString("…"))
            return head
        }
        switch self {
        case .text(let a): return cut(a).map(MDBlock.text)
        case .item(let m, let a): return cut(a).map { .item(marker: m, $0) }
        case .heading, .code: return nil
        }
    }

    static func parse(_ source: String) -> [MDBlock] {
        var out: [MDBlock] = []
        var para: [String] = []
        var fence: [String]?

        func flush() {
            guard !para.isEmpty else { return }
            // Lines inside a paragraph keep their breaks, as on the phone.
            out.append(.text(inline(para.joined(separator: "\n"))))
            para = []
        }

        for raw in source.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("```") {
                if let open = fence {
                    out.append(.code(open))
                    fence = nil
                } else {
                    flush()
                    fence = []
                }
                continue
            }
            if fence != nil { fence!.append(raw); continue }
            if line.isEmpty { flush(); continue }
            if line.hasPrefix("#") {
                flush()
                let title = line.drop { $0 == "#" }.trimmingCharacters(in: .whitespaces)
                out.append(.heading(inline(title)))
                continue
            }
            if let (marker, rest) = listItem(line) {
                flush()
                out.append(.item(marker: marker, inline(rest)))
                continue
            }
            para.append(line)
        }
        if let open = fence { out.append(.code(open)) }
        flush()
        return out
    }

    /// "- x", "* x", "+ x" become a bullet; "3. x" or "3) x" keep their number.
    private static func listItem(_ line: String) -> (String, String)? {
        if let first = line.first, "-*+".contains(first), line.dropFirst().first == " " {
            return ("•", String(line.dropFirst(2)))
        }
        let digits = line.prefix { $0.isNumber }
        guard !digits.isEmpty, digits.count < 4 else { return nil }
        let after = line.dropFirst(digits.count)
        guard let mark = after.first, mark == "." || mark == ")", after.dropFirst().first == " " else { return nil }
        return (digits + ".", String(after.dropFirst(2)))
    }

    /// Inside `code` spans, a word joiner after "(" keeps a call from wrapping right after it,
    /// and dots and slashes become the preferred places to wrap.
    private static func gluedOpenParens(_ s: String) -> String {
        var out = ""
        var inCode = false
        for ch in s {
            if ch == "`" { inCode.toggle() }
            out.append(ch)
            guard inCode else { continue }
            if ch == "(" { out.append("\u{2060}") }
            // Long paths and member chains break after a dot or slash instead.
            if ch == "." || ch == "/" { out.append("\u{200B}") }
        }
        return out
    }

    /// Inline Markdown through Foundation, then code runs in tinted mono and links in sky.
    static func inline(_ raw: String) -> AttributedString {
        let text = gluedOpenParens(raw)
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace,
                                                              failurePolicy: .returnPartiallyParsedIfPossible)
        var a = (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
        for run in a.runs {
            if run.inlinePresentationIntent?.contains(.code) == true {
                a[run.range].font = .system(.footnote, design: .monospaced)
                a[run.range].foregroundColor = WatchStyle.mint
            }
            if run.link != nil {
                a[run.range].foregroundColor = WatchStyle.sky
                a[run.range].underlineStyle = .single
            }
        }
        return a
    }
}

private struct MDBlockView: View {
    let block: MDBlock

    var body: some View {
        switch block {
        case .text(let a):
            Text(a)
        case .heading(let a):
            Text(a).fontWeight(.semibold)
        case .item(let marker, let a):
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(verbatim: marker).foregroundStyle(.tertiary)
                Text(a)
            }
        case .code(let lines):
            CodePanel(lines: lines)
        }
    }
}

/// A fenced block: dark rounded panel, 11 pt mono, scrolls sideways, at most eight lines.
private struct CodePanel: View {
    let lines: [String]

    var body: some View {
        let shown = lines.prefix(MarkdownText.codeLines)
        let more = lines.count > MarkdownText.codeLines
        ScrollView(.horizontal) {
            VStack(alignment: .leading, spacing: 1) {
                ForEach(Array(shown.enumerated()), id: \.offset) { _, line in
                    Text(verbatim: line.isEmpty ? " " : line)
                }
                if more { Text(verbatim: "…").foregroundStyle(.secondary) }
            }
            .font(.system(size: 11, design: .monospaced))
            .fixedSize(horizontal: true, vertical: false)
            .padding(8)
        }
        .scrollIndicators(.hidden)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.white.opacity(0.07)))
    }
}
