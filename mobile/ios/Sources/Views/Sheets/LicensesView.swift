import SwiftUI

struct LicenseCatalog: Decodable, Sendable {
    struct App: Decodable, Sendable, Hashable {
        let name: String
        let licenseId: String
        let textKey: String
    }

    struct Library: Decodable, Sendable, Hashable, Identifiable {
        let name: String
        let version: String
        let group: String
        let licenseId: String
        let licenseKeys: [String]
        let author: String?
        let url: String?
        let noticeKey: String?

        var id: String { "\(group)/\(name)/\(version)" }
    }

    let app: App
    let libraries: [Library]
    let texts: [String: String]

    static func load() -> LicenseCatalog? {
        guard let file = Bundle.main.url(forResource: "licenses", withExtension: "json"),
              let bytes = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode(LicenseCatalog.self, from: bytes)
    }

    @MainActor var appLibrary: Library {
        Library(name: app.name, version: RemoteWorkspaceViewModel.appVersion, group: "app",
                licenseId: app.licenseId, licenseKeys: [app.textKey],
                author: nil, url: nil, noticeKey: nil)
    }
}

struct LicensesView: View {
    @State private var catalog: LicenseCatalog?
    @State private var started = false
    @State private var query = ""

    private var needle: String { query.trimmingCharacters(in: .whitespaces) }

    private func keep(_ lib: LicenseCatalog.Library) -> Bool {
        let q = needle
        if q.isEmpty { return true }
        return [lib.name, lib.licenseId, lib.version].contains { $0.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        let appRows = catalog.map { [$0.appLibrary].filter(keep) } ?? []
        let swiftRows = catalog?.libraries.filter { $0.group == "swift" && keep($0) } ?? []
        let rustRows = catalog?.libraries.filter { $0.group == "rust" && keep($0) } ?? []
        let texts = catalog?.texts ?? [:]

        List {
            if !appRows.isEmpty {
                Section { ForEach(appRows) { LibraryRow(library: $0, texts: texts) } }
            }
            if !swiftRows.isEmpty {
                Section("Swift packages") { ForEach(swiftRows) { LibraryRow(library: $0, texts: texts) } }
            }
            if !rustRows.isEmpty {
                Section("Native (Rust) libraries") { ForEach(rustRows) { LibraryRow(library: $0, texts: texts) } }
            }
        }
        .overlay {
            if catalog != nil, !needle.isEmpty, appRows.isEmpty, swiftRows.isEmpty, rustRows.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
        .inlineTitle("License")
        .drawerSearch($query, prompt: "Search libraries")
        .task {
            guard !started else { return }
            started = true
            catalog = await Task.detached { LicenseCatalog.load() }.value
        }
    }
}

private struct LibraryRow: View {
    let library: LicenseCatalog.Library
    let texts: [String: String]

    var body: some View {
        NavigationLink {
            LicenseDetailView(library: library, texts: texts)
        } label: {
            Across(12) {
                Lead(2) {
                    Text(verbatim: library.name)
                        .ink(AppColor.onSurface)
                        .oneLine()
                    if !library.version.isEmpty {
                        Text(verbatim: library.version)
                            .font(.footnote)
                            .ink(AppColor.onSurfaceVariant)
                    }
                }
                Spacer(minLength: 8)
                Text(verbatim: library.licenseId)
                    .font(.subheadline)
                    .ink(AppColor.onSurfaceVariant)
                    .oneLine()
                    .frame(maxWidth: 170, alignment: .trailing)
            }
        }
    }
}

struct LicenseDetailView: View {
    let library: LicenseCatalog.Library
    let texts: [String: String]
    @Environment(\.openURL) private var openURL

    var body: some View {
        List {
            Section {
                if !library.version.isEmpty {
                    LabeledContent("Version") { Text(verbatim: library.version) }
                }
                LabeledContent("License") { Text(verbatim: library.licenseId) }
                if let author = library.author {
                    LabeledContent("Author") { Text(verbatim: author) }
                }
                if let raw = library.url, let link = URL(string: raw) {
                    LabeledContent("Source") {
                        Button {
                            openURL(link)
                        } label: {
                            Text(verbatim: raw.replacingOccurrences(of: "https://", with: ""))
                                .oneLine()
                        }
                        .tint(AppColor.onSurface)
                    }
                }
            }
            if let key = library.noticeKey, let notice = texts[key] {
                Section("Notice") { licenseBody(notice) }
            }
            ForEach(library.licenseKeys, id: \.self) { key in
                if let body = texts[key] {
                    Section { licenseBody(body) } header: { Text(verbatim: key) }
                }
            }
        }
        .inlineTitle(Text(verbatim: library.name))
    }

    private func licenseBody(_ text: String) -> some View {
        Text(verbatim: text)
            .typeset(11.5, design: .monospaced, ink: AppColor.onSurfaceVariant)
            .textSelection(.enabled)
    }
}
