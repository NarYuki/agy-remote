import Foundation

extension AgyBaseModel {
    /// The name without its vendor word and without a trailing "(...)" note.
    var compactTitle: String {
        var text = name
        if let vendor = ["Gemini ", "Claude "].first(where: text.hasPrefix) {
            text.removeFirst(vendor.count)
        }
        if text.hasSuffix(")"), let cut = text.range(of: " (", options: .backwards) {
            text = String(text[..<cut.lowerBound])
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

extension WorkspaceFolder {
    var hostPathKey: String { "\(host)|\(path)" }
}

extension ChatSummary {
    var folderKey: String? { folder.map { "\(host)|\($0)" } }
}
