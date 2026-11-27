import SwiftUI
import UIKit

// MARK: - Blocks

struct MarkdownBlock: Identifiable, Equatable {
    struct Item: Equatable {
        var indent: Int
        var number: Int?
        var text: String
    }

    enum Kind: Equatable {
        case paragraph(String)
        case heading(Int, String)
        case list([Item])
        case quote(String)
        case code(String, String)
        case table([String], [[String]])
        case rule

        var tag: String {
            switch self {
            case .paragraph: "p"
            case .heading(let level, _): "h\(level)"
            case .list: "l"
            case .quote: "q"
            case .code(let lang, _): "c-\(lang)"
            case .table: "t"
            case .rule: "r"
            }
        }
    }

    let id: String
    let kind: Kind

    nonisolated(unsafe) private static var memo = BoundedCache<String, [Kind]>(limit: 300)
    private static let memoLock = NSLock()

    /// Blocks with ids that stay put while the text grows.
    static func cachedParse(_ text: String) -> [MarkdownBlock] {
        let kinds = cachedKinds(text)
        var counts: [String: Int] = [:]
        return kinds.map { kind in
            let n = counts[kind.tag, default: 0]
            counts[kind.tag] = n + 1
            return MarkdownBlock(id: "\(kind.tag)#\(n)", kind: kind)
        }
    }

    private static func cachedKinds(_ text: String) -> [Kind] {
        memoLock.lock(); defer { memoLock.unlock() }
        if let hit = memo[text] { return hit }
        let result: [Kind]
        if let cut = stableCut(text) {
            let head = String(text[..<cut])
            let headKinds: [Kind]
            if let h = memo[head] { headKinds = h } else { headKinds = parse(head); memo[head] = headKinds }
            result = headKinds + parse(String(text[cut...]))
        } else {
            result = parse(text)
        }
        memo[text] = result
        return result
    }

    /// Start of the last blank line that is outside a fence and follows a non-blank line.
    private static func stableCut(_ text: String) -> String.Index? {
        var inFence = false
        var previousBlank = false
        var best: String.Index?
        var lineStart = text.startIndex
        while true {
            let lineEnd = text[lineStart...].firstIndex(of: "\n") ?? text.endIndex
            let trimmed = text[lineStart..<lineEnd].trimmingCharacters(in: .spacesAndTabs)
            if trimmed.hasPrefix("```") { inFence.toggle() }
            let blank = trimmed.isEmpty
            if blank, !inFence, !previousBlank, lineStart != text.startIndex { best = lineStart }
            previousBlank = blank
            if lineEnd == text.endIndex { break }
            lineStart = text.index(after: lineEnd)
        }
        return best
    }

    static func tableCells(_ line: String) -> [String] {
        var s = Substring(line.trimmingCharacters(in: .whitespaces))
        if s.hasPrefix("|") { s = s.dropFirst() }
        if s.hasSuffix("|") { s = s.dropLast() }
        return s.split(separator: "|", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
    }

    static func isTableSeparator(_ line: String) -> Bool {
        let cells = tableCells(line)
        guard !cells.isEmpty else { return false }
        return cells.allSatisfy { cell in cell.contains("-") && cell.allSatisfy { $0 == "-" || $0 == ":" } }
    }

    private static func leadingColumns(_ line: String) -> Int {
        var n = 0
        for ch in line {
            if ch == " " { n += 1 } else if ch == "\t" { n += 4 } else { break }
        }
        return n
    }

    private static func listMarker(_ t: String) -> (Int?, String)? {
        let chars = Array(t)
        if chars.count >= 2, "-*+".contains(chars[0]), chars[1] == " " || chars[1] == "\t" {
            return (nil, String(chars[2...]).trimmingCharacters(in: .spacesAndTabs))
        }
        var digits = 0
        while digits < chars.count, digits < 3, chars[digits].isASCII, chars[digits].isNumber { digits += 1 }
        guard digits > 0, digits + 1 < chars.count else { return nil }
        guard chars[digits] == "." || chars[digits] == ")", chars[digits + 1] == " " || chars[digits + 1] == "\t" else { return nil }
        return (Int(String(chars[..<digits])), String(chars[(digits + 2)...]).trimmingCharacters(in: .spacesAndTabs))
    }

    static func parse(_ source: String) -> [Kind] {
        var out: [Kind] = []
        var para: [String] = []
        var items: [Item] = []
        var quote: [String] = []
        var pipes: [String] = []
        var fenceLang: String?
        var fence: [String] = []

        func closePara() {
            let joined = para.joined(separator: "\n")
            let clean = joined.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
            if clean.isEmpty == false { out.append(Kind.paragraph(clean)) }
            para.removeAll()
        }
        func closeList() { if !items.isEmpty { out.append(.list(items)) }; items.removeAll() }
        func closeQuote() { if !quote.isEmpty { out.append(.quote(quote.joined(separator: "\n"))) }; quote.removeAll() }
        func closePipes() {
            guard !pipes.isEmpty else { return }
            if pipes.count >= 2, isTableSeparator(pipes[1]) {
                closePara()
                out.append(.table(tableCells(pipes[0]), pipes.dropFirst(2).map(tableCells)))
            } else {
                para.append(contentsOf: pipes)
            }
            pipes.removeAll()
        }
        func closeAll() { closePipes(); closePara(); closeList(); closeQuote() }

        for rawSub in source.split(separator: "\n", omittingEmptySubsequences: false) {
            let raw = String(rawSub)
            let t = raw.trimmingCharacters(in: .spacesAndTabs)
            if let lang = fenceLang {
                if t.hasPrefix("```") {
                    out.append(.code(lang, fence.joined(separator: "\n")))
                    fence.removeAll(); fenceLang = nil
                } else {
                    fence.append(raw)
                }
                continue
            }
            if t.hasPrefix("```") {
                closeAll()
                fenceLang = String(t.dropFirst(3)).trimmingCharacters(in: .whitespaces).lowercased()
                continue
            }
            if t.isEmpty { closeAll(); continue }
            if t.hasPrefix("|") {
                if pipes.isEmpty { closeList(); closeQuote() }
                pipes.append(t)
                continue
            }
            closePipes()

            let hashes = t.prefix { $0 == "#" }.count
            if (1...6).contains(hashes), t.count > hashes, t[t.index(t.startIndex, offsetBy: hashes)] == " " {
                closePara(); closeList(); closeQuote()
                out.append(.heading(hashes, String(t.dropFirst(hashes + 1)).trimmingCharacters(in: .whitespaces)))
                continue
            }
            if t == "---" || t == "***" || t == "___" {
                closePara(); closeList(); closeQuote()
                out.append(.rule)
                continue
            }
            if let (number, body) = listMarker(t) {
                closePara(); closeQuote()
                items.append(Item(indent: leadingColumns(raw) / 2, number: number, text: body))
                continue
            }
            if t.hasPrefix(">") {
                closePara(); closeList()
                quote.append(String(t.dropFirst()).trimmingCharacters(in: .whitespaces))
                continue
            }
            if !items.isEmpty, para.isEmpty, quote.isEmpty, leadingColumns(raw) >= 2 {
                items[items.count - 1].text += " " + t
                continue
            }
            closeList(); closeQuote()
            para.append(raw)
        }
        if let lang = fenceLang, fence.contains(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) {
            out.append(.code(lang, fence.joined(separator: "\n")))
        }
        closeAll()
        return out
    }
}

private extension CharacterSet {
    static let spacesAndTabs = CharacterSet(charactersIn: " \t")
}

/// Tiny insertion-ordered cache that drops its oldest entries past `limit`.
struct BoundedCache<Key: Hashable, Value> {
    let limit: Int
    private var values: [Key: Value] = [:]
    private var order: [Key] = []

    init(limit: Int) { self.limit = limit }

    subscript(key: Key) -> Value? {
        get { values[key] }
        set {
            guard let newValue else { values[key] = nil; order.removeAll { $0 == key }; return }
            if values.updateValue(newValue, forKey: key) == nil {
                order.append(key)
                if order.count > limit {
                    let drop = order.count - limit
                    for k in order.prefix(drop) { values[k] = nil }
                    order.removeFirst(drop)
                }
            }
        }
    }
}

// MARK: - Message view

struct MarkdownMessageView: View, Equatable {
    let text: String

    static let bodyLineSpacing: CGFloat = 4

    var body: some View {
        let parsed = MarkdownBlock.cachedParse(text)
        LazyVStack(alignment: HorizontalAlignment.leading, spacing: 12) {
            ForEach(parsed, id: \.id) { MarkdownBlockView(kind: $0.kind).equatable() }
        }
        .chatWide(.leading)
        .textSelection(.enabled)
        .tint(AppColor.primary)
    }

    /// Inline Markdown restyled for the app.
    static func inline(_ s: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace, failurePolicy: .returnPartiallyParsedIfPossible)
        guard var styled = try? AttributedString(markdown: s, options: options) else { return AttributedString(s) }
        let codeFont = Font.system(size: 14.5).monospaced()
        let strong = AppFont.bodyLarge.weight(.semibold)
        for run in styled.runs {
            let span = run.range
            if run.link != nil { styled[span].foregroundColor = AppColor.primary }
            let intent = run.inlinePresentationIntent ?? []
            if intent.contains(.code) {
                styled[span].backgroundColor = AppColor.surfaceContainerHigh
                styled[span].font = codeFont
                continue
            }
            if intent.contains(.stronglyEmphasized) {
                styled[span].font = intent.contains(.emphasized) ? strong.italic() : strong
            }
        }
        return styled
    }
}

private struct HairRule: View {
    var share: Double = 0.6
    var body: some View {
        AppColor.outlineVariant.opacity(share).frame(height: 1)
    }
}

private struct MarkdownBlockView: View, Equatable {
    let kind: MarkdownBlock.Kind

    var body: some View {
        switch kind {
        case .paragraph(let s): Prose(source: s)
        case .heading(let level, let s): heading(level, s)
        case .list(let items):
            VStack(alignment: HorizontalAlignment.leading, spacing: 8) {
                ForEach(items.indices, id: \.self) { ListRow(item: items[$0]) }
            }
        case .quote(let s):
            HStack(alignment: .top, spacing: 12) {
                AppColor.outlineVariant.frame(width: 2).clipShape(Capsule())
                Prose(source: s, ink: AppColor.onSurfaceVariant)
            }
            .fixedSize(horizontal: false, vertical: true)
        case .code(let lang, let code): CodeBlockView(language: lang, code: code)
        case .table(let header, let rows): MarkdownTableView(header: header, rows: rows)
        case .rule: HairRule().chatPad(0, 6)
        }
    }

    private func heading(_ level: Int, _ s: String) -> some View {
        let size: CGFloat = level <= 1 ? 20 : level == 2 ? 17.5 : 16
        let ink = level <= 3 ? AppColor.onSurface : AppColor.onSurfaceVariant
        return Text(MarkdownMessageView.inline(s))
            .chatType(size, ink, .semibold)
            .fixedSize(horizontal: false, vertical: true)
            .chatInsets(level <= 1 ? 8 : 4, 0, 0, 0)
    }
}

private struct Prose: View {
    let source: String
    var ink: Color = AppColor.onSurface

    /// A single Text is drawn into one layer, and a layer taller than the GPU's texture limit
    /// (a few thousand points at 3x) renders blank. Long paragraphs are therefore drawn as a
    /// stack of shorter Texts, each well under that limit.
    static let linesPerPiece = 40

    var body: some View {
        let pieces = Self.pieces(of: source)
        if pieces.count == 1 {
            piece(source)
        } else {
            VStack(alignment: .leading, spacing: MarkdownMessageView.bodyLineSpacing) {
                ForEach(pieces.indices, id: \.self) { piece(pieces[$0]) }
            }
        }
    }

    private func piece(_ part: String) -> some View {
        Text(MarkdownMessageView.inline(part))
            .lineSpacing(MarkdownMessageView.bodyLineSpacing)
            .font(AppFont.bodyLarge)
            .foregroundStyle(ink)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// Splits at line breaks every `linesPerPiece` lines; very long lines also count by length.
    static func pieces(of text: String) -> [String] {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.count > linesPerPiece || text.count > linesPerPiece * 60 else { return [text] }
        var out: [String] = []
        var run: [Substring] = []
        var weight = 0
        for line in lines {
            let rows = max(1, line.count / 45 + 1)
            if !run.isEmpty, weight + rows > linesPerPiece {
                out.append(run.joined(separator: "\n"))
                run.removeAll()
                weight = 0
            }
            run.append(line)
            weight += rows
        }
        if !run.isEmpty { out.append(run.joined(separator: "\n")) }
        return out
    }
}

private struct ListRow: View {
    let item: MarkdownBlock.Item

    var body: some View {
        let depth = CGFloat(item.indent) * 18
        if let n = item.number {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: String(n) + ".")
                    .monospacedDigit()
                    .frame(minWidth: 18, alignment: .trailing)
                    .font(AppFont.bodyLarge)
                    .foregroundStyle(AppColor.onSurfaceMuted)
                Prose(source: item.text)
            }
            .chatInsets(0, depth, 0, 0)
        } else {
            HStack(alignment: VerticalAlignment.firstTextBaseline, spacing: 10) {
                let dotInk = AppColor.onSurfaceMuted
                Text(verbatim: "\u{2022}").foregroundStyle(dotInk).font(AppFont.bodyLarge)
                Prose(source: item.text)
            }
            .chatInsets(0, depth + 4, 0, 0)
        }
    }
}

// MARK: - Table

struct MarkdownTableView: View {
    let header: [String]
    let rows: [[String]]

    var body: some View {
        let width = rows.reduce(header.count) { max($0, $1.count) }
        ScrollView(.horizontal) {
            Grid(alignment: .leading, horizontalSpacing: 18, verticalSpacing: 8) {
                GridRow {
                    ForEach(0..<width, id: \.self) { col in
                        cell(header, col).font(AppFont.bodyMedium.weight(.semibold))
                    }
                }
                HairRule().gridCellUnsizedAxes(.horizontal)
                ForEach(rows.indices, id: \.self) { r in
                    GridRow {
                        ForEach(0..<width, id: \.self) { col in
                            cell(rows[r], col).fixedSize(horizontal: false, vertical: true).font(AppFont.bodyMedium)
                        }
                    }
                }
            }
            .chatPad(0, 2)
        }
        .scrollIndicators(.hidden)
    }

    private func cell(_ values: [String], _ col: Int) -> Text {
        let raw = values.indices.contains(col) ? values[col] : ""
        return Text(MarkdownMessageView.inline(raw)).foregroundColor(AppColor.onSurface)
    }
}

// MARK: - Code block

struct CodeBlockView: View {
    let language: String
    let code: String

    @Environment(\.colorScheme) private var scheme
    @State private var expanded = false
    @State private var copied = false

    private static let foldAbove = 20
    private static let foldTo = 16

    var body: some View {
        let all = code.components(separatedBy: "\n")
        let canFold = all.count > Self.foldAbove
        let folded = canFold && !expanded
        let visible = folded ? Array(all.prefix(Self.foldTo)) : all
        let card = chatRound(12)

        VStack(spacing: 0) {
            header
            HairRule(share: 0.5)
            codeArea(visible, gutter: all.count > 1)
                .overlay(alignment: .bottom) { if folded { fade } }
            if canFold {
                HairRule(share: 0.5)
                foldButton(total: all.count)
            }
        }
        .background(card.fill(AppColor.surfaceContainerLow))
        .chatOutline(card, AppColor.outlineVariant.opacity(0.6))
        .environment(\.layoutDirection, .leftToRight)
    }

    private var header: some View {
        HStack(spacing: 0) {
            Text(verbatim: language.isEmpty ? "code" : language.lowercased())
                .chatType(12, AppColor.onSurfaceMuted, .medium, mono: true)
            Spacer(minLength: 8)
            Button(action: copy) {
                let ink = copied ? AppColor.primary : AppColor.onSurfaceMuted
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                    .chatType(12.5, ink, .medium)
                    .chatSize(32, 28)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PlainButtonStyle())
            .accessibilityLabel(Text("Copy code"))
        }
        .chatInsets(0, 14, 0, 4)
        .frame(height: 34)
    }

    private func codeArea(_ lines: [String], gutter: Bool) -> some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 14) {
                if gutter {
                    Text(verbatim: lines.indices.map { String($0 + 1) }.joined(separator: "\n"))
                        .monospacedDigit()
                        .multilineTextAlignment(.trailing)
                        .lineSpacing(3.5)
                        .font(AppFont.codeBlock)
                        .foregroundStyle(AppColor.onSurfaceMuted.opacity(0.7))
                }
                Text(SyntaxHighlighter.cachedColour(lines.joined(separator: "\n"), scheme: scheme))
                    .lineSpacing(3.5)
                    .font(AppFont.codeBlock)
                    .fixedSize(horizontal: true, vertical: false)
                    .textSelection(.enabled)
            }
            .chatPad(14, 12)
        }
        .scrollIndicators(.hidden)
    }

    private var fade: some View {
        let base = AppColor.surfaceContainerLow
        return LinearGradient(gradient: Gradient(colors: [base.opacity(0), base]), startPoint: .top, endPoint: .bottom)
            .allowsHitTesting(false)
            .frame(height: 36)
    }

    private func foldButton(total: Int) -> some View {
        Button {
            let motion = Animation.spring(response: 0.32, dampingFraction: 0.9)
            withAnimation(motion) { expanded = !expanded }
        } label: {
            Group {
                if expanded { Text("Show less") } else { Text("Show all \(total) lines") }
            }
            .chatType(13, AppColor.onSurfaceVariant, .medium)
            .frame(maxWidth: .infinity, minHeight: 36)
            .contentShape(Rectangle())
        }
        .buttonStyle(PlainButtonStyle())
    }

    private func copy() {
        UIPasteboard.general.string = code
        Haptics.success()
        let appear = Animation.easeOut(duration: 0.15)
        withAnimation(appear) { copied = true }
        Task { @MainActor [code] in
            _ = code
            try? await Task.sleep(nanoseconds: 1_400_000_000)
            withAnimation(.default) { copied = false }
        }
    }
}

// MARK: - Syntax highlighting

enum SyntaxHighlighter {
    struct Palette {
        var plain: Color
        var keyword: Color
        var string: Color
        var comment: Color
        var number: Color

        static let light = Palette(plain: hex(0x1A1F20), keyword: hex(0x6D3FD9), string: hex(0x0F766E), comment: hex(0x7B8587), number: hex(0x1D6FB8))
        static let dark = Palette(plain: hex(0xE4E9E8), keyword: hex(0xB9A4FF), string: hex(0x6EE0C8), comment: hex(0x6F7A7D), number: hex(0x8EC5F5))

        static func forScheme(_ scheme: ColorScheme) -> Palette { scheme == .dark ? dark : light }

        private static func hex(_ v: UInt32) -> Color {
            Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
        }
    }

    private enum Role { case plain, keyword, string, comment, number }

    nonisolated(unsafe) private static var memo = BoundedCache<String, AttributedString>(limit: 200)
    private static let lock = NSLock()

    private static let keywords: Set<String> = Set("""
    func fn def class struct enum protocol interface trait impl extension typealias type \
    let var const mut static public private internal fileprivate protected open \
    export import from package module use mod pub extern final override abstract sealed \
    data object companion init deinit new delete namespace using include where lazy \
    if else elif elseif then fi for while do done loop repeat in of until switch match \
    when case default break continue return yield goto fallthrough async await go chan \
    select try catch except finally throw throws raise defer guard with as is not and or \
    pass lambda esac \
    true false nil null None True False undefined self Self this super NULL \
    String Int Int64 Int32 Double Float Bool Void Character UInt \
    int float double char bool void long short unsigned str bytes \
    i8 i16 i32 i64 u8 u16 u32 u64 usize isize
    """.split(separator: " ").map(String.init))

    static func cachedColour(_ source: String, scheme: ColorScheme) -> AttributedString {
        let key = (scheme == .dark ? "d|" : "l|") + source
        lock.lock()
        if let hit = memo[key] { lock.unlock(); return hit }
        lock.unlock()
        let out = colour(source, palette: .forScheme(scheme))
        lock.lock(); memo[key] = out; lock.unlock()
        return out
    }

    static func colour(_ source: String, palette: Palette) -> AttributedString {
        let chars = Array(source.unicodeScalars)
        var runs: [(Int, Int, Role)] = []
        func emit(_ a: Int, _ z: Int, _ role: Role) {
            guard z > a else { return }
            if let last = runs.last, last.2 == role, last.1 == a { runs[runs.count - 1].1 = z } else { runs.append((a, z, role)) }
        }
        func endOfLine(_ from: Int) -> Int {
            var j = from
            while j < chars.count, chars[j] != "\n" { j += 1 }
            return j
        }
        func isIdentStart(_ c: Unicode.Scalar) -> Bool { c == "_" || c.properties.isAlphabetic }
        func isIdent(_ c: Unicode.Scalar) -> Bool { isIdentStart(c) || ("0"..."9").contains(c) }
        func isDigit(_ c: Unicode.Scalar) -> Bool { ("0"..."9").contains(c) }
        func isHex(_ c: Unicode.Scalar) -> Bool { isDigit(c) || ("a"..."f").contains(c) || ("A"..."F").contains(c) }
        func at(_ k: Int) -> Unicode.Scalar? { k < chars.count ? chars[k] : nil }

        var i = 0
        var startOfLine = true
        while i < chars.count {
            let c = chars[i]
            if c == "\n" { emit(i, i + 1, .plain); i += 1; startOfLine = true; continue }
            if c == " " || c == "\t" { emit(i, i + 1, .plain); i += 1; continue }
            if c == "/", at(i + 1) == "/" { let e = endOfLine(i); emit(i, e, .comment); i = e; continue }
            if c == "/", at(i + 1) == "*" {
                var j = i + 2
                while j < chars.count, !(chars[j] == "*" && at(j + 1) == "/") { j += 1 }
                j = min(chars.count, j + 2)
                emit(i, j, .comment); i = j; startOfLine = false; continue
            }
            if c == "#", startOfLine, at(i + 1) != "!" { let e = endOfLine(i); emit(i, e, .comment); i = e; continue }
            startOfLine = false
            if c == "\"" || c == "'" || c == "`" {
                var j = i + 1
                var closed = false
                while j < chars.count, chars[j] != "\n" {
                    if chars[j] == "\\" { j += 2; continue }
                    if chars[j] == c { closed = true; j += 1; break }
                    j += 1
                }
                j = min(j, chars.count)
                if !closed && c == "'" { emit(i, i + 1, .plain); i += 1 } else { emit(i, j, .string); i = j }
                continue
            }
            if isDigit(c) {
                var j = i + 1
                if c == "0", let x = at(j), x == "x" || x == "X", let h = at(j + 1), isHex(h) {
                    j += 1
                    while let h = at(j), isHex(h) { j += 1 }
                } else {
                    while let d = at(j), isDigit(d) { j += 1 }
                    if at(j) == ".", let d = at(j + 1), isDigit(d) {
                        j += 1
                        while let d = at(j), isDigit(d) { j += 1 }
                    }
                }
                emit(i, j, .number); i = j; continue
            }
            if isIdentStart(c) {
                var j = i + 1
                while let d = at(j), isIdent(d) { j += 1 }
                var word = String.UnicodeScalarView()
                word.append(contentsOf: chars[i..<j])
                emit(i, j, keywords.contains(String(word)) ? .keyword : .plain)
                i = j; continue
            }
            emit(i, i + 1, .plain); i += 1
        }

        var out = AttributedString()
        for (a, z, role) in runs {
            var view = String.UnicodeScalarView()
            view.append(contentsOf: chars[a..<z])
            var piece = AttributedString(String(view))
            switch role {
            case .plain: piece.foregroundColor = palette.plain
            case .keyword: piece.foregroundColor = palette.keyword
            case .string: piece.foregroundColor = palette.string
            case .comment: piece.foregroundColor = palette.comment
            case .number: piece.foregroundColor = palette.number
            }
            out.append(piece)
        }
        return out
    }
}
