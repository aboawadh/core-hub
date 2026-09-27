// Settings → Files (apps batches 11 and 13; the web's FilesTool, contract decision §65): the plain
// rules the page follows and the calls it makes, apart from the views so FilesRulesTests checks them
// without drawing. Android's FilesKit.kt is the twin.
import CoreHubClient
import Foundation

enum FilesRules {
    /// `a/b` + `c` = `a/b/c`; the top folder joins to the bare name.
    static func join(_ folder: String, _ name: String) -> String { folder.isEmpty ? name : "\(folder)/\(name)" }

    /// The folder a path is in; `""` for anything at the top.
    static func parent(_ path: String) -> String {
        guard let cut = path.lastIndex(of: "/") else { return "" }
        return String(path[..<cut])
    }

    /// The last segment.
    static func baseName(_ path: String) -> String {
        guard let cut = path.lastIndex(of: "/") else { return path }
        return String(path[path.index(after: cut)...])
    }

    /// One crumb per folder from the top to `path`, each with its own path.
    static func crumbs(_ path: String) -> [(name: String, path: String)] {
        let parts = path.split(separator: "/").map(String.init)
        return parts.indices.map { (parts[$0], parts[...$0].joined(separator: "/")) }
    }

    /// What a person typed as a name or a destination, in the contract's form: no leading `/` or `./`.
    static func normalise(_ typed: String) -> String {
        var text = typed.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\\", with: "/")
        while text.contains("//") { text = text.replacingOccurrences(of: "//", with: "/") }
        while text.hasPrefix("/") { text.removeFirst() }
        while text.hasPrefix("./") { text.removeFirst(2) }
        while text.hasSuffix("/") { text.removeLast() }
        return text
    }

    /// A new name for something in its own folder: one segment, not `.` or `..`.
    static func validName(_ typed: String) -> Bool {
        let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        return !name.isEmpty && name != "." && name != ".." && !name.contains("/") && !name.contains("\\")
    }

    /// `notes.md` → `notes copy.md`: the suggested name of a copy beside the original.
    static func copyName(_ name: String) -> String {
        guard let dot = name.lastIndex(of: "."), dot != name.startIndex else { return "\(name) copy" }
        return "\(name[..<dot]) copy\(name[dot...])"
    }

    enum Sort: String, CaseIterable {
        case name, newest, largest
    }

    /// What the list shows: the entries whose name holds `query` (any case), folders first, then by
    /// `sort` — the name, the newest change first, or the largest first.
    static func arrange(_ entries: [WorkspaceFileEntry], query: String, sort: Sort) -> [WorkspaceFileEntry] {
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        let shown = needle.isEmpty ? entries : entries.filter { $0.name.lowercased().contains(needle) }
        func byName(_ a: WorkspaceFileEntry, _ b: WorkspaceFileEntry) -> Bool {
            a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
        }
        return shown.sorted { a, b in
            let aDir = a.kind == .directory, bDir = b.kind == .directory
            if aDir != bDir { return aDir }
            switch sort {
            case .name:
                return byName(a, b)
            case .newest:
                let x = a.modifiedAt ?? .distantPast, y = b.modifiedAt ?? .distantPast
                return x == y ? byName(a, b) : x > y
            case .largest:
                let x = a.sizeBytes ?? -1, y = b.sizeBytes ?? -1
                return x == y ? byName(a, b) : x > y
            }
        }
    }

    /// The entry as a file the chat's opener fetches and opens; nil for a folder or a link that leads out.
    static func hubFile(_ entry: WorkspaceFileEntry, profile: String) -> HubFile? {
        guard entry.kind == .file else { return nil }
        return .profileFile(profile: profile, path: entry.path, name: entry.name, mime: entry.mime, size: entry.sizeBytes, modified: entry.modifiedAt)
    }

    /// Text the editor shows as Markdown (Edit/Preview); anything else is plain text in monospace.
    static func markdown(_ name: String) -> Bool {
        ["md", "markdown"].contains((name as NSString).pathExtension.lowercased())
    }

    /// A size a person reads, in Latin digits in every language, kept left to right inside a sentence.
    static func size(_ bytes: Int) -> String {
        let units = ["B", "KB", "MB", "GB", "TB"]
        var value = Double(bytes)
        var unit = 0
        while value >= 1024, unit < units.count - 1 {
            value /= 1024
            unit += 1
        }
        var number = unit == 0 ? String(bytes) : String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
        if number.hasSuffix(".0") { number.removeLast(2) }
        return "\u{2066}\(number) \(units[unit])\u{2069}"
    }

    /// «12 KB · 27 Sep 2026, 14:05»: the size (a file's) and the last change, when the hub gave them.
    static func detail(_ entry: WorkspaceFileEntry, language: AppLanguage, zone: TimeZone = .current) -> String? {
        let formatter = DateFormatter()
        formatter.locale = language.locale
        formatter.timeZone = zone
        // A fixed pattern: a localized template can carry the region's own digits (Arabic-Indic).
        formatter.dateFormat = "d MMM y, HH:mm"
        let parts = [entry.sizeBytes.map(size), entry.modifiedAt.map(formatter.string(from:))].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// How many of the profile's recent chats «Attach to chat» offers besides a new one (as the web).
    static let recentChatLimit = 8

    /// The chats «Attach to chat» offers: this profile's recent ones as the hub lists them, without
    /// the global agent's conversation (it is not in the chats list, §46), at most `recentChatLimit`.
    static func recentChats(_ sessions: [Session]) -> [Session] {
        Array(sessions.filter { $0.source != .globalAgent }.prefix(recentChatLimit))
    }

    /// A file the phone need not even send: over the hub's upload cap.
    static func tooLarge(_ size: Int, max: Int?) -> Bool {
        guard let max, max > 0 else { return false }
        return size > max
    }

    /// An upload refused because a file of that name is there (`409`, not a text save's `changed`): ask to replace.
    static func nameTaken(_ error: Error?) -> Bool {
        guard let error else { return false }
        let failure = HubFailure(error)
        return failure.status == 409 && failure.reason != "changed"
    }

    enum PromptKind { case newFolder, newFile, rename, move, copy }

    /// What a name or destination typed in a prompt makes the page do.
    enum Step: Equatable {
        case badName
        case same
        case makeFolder(String)
        case writeNew(String)
        case move(from: String, to: String)
        case copy(from: String, to: String)
    }

    /// `typed` in the prompt of `kind`, in `folder`, for the entry at `path`: a new folder or file may
    /// name a path under the folder (`a/b`); a rename is one name; a move or copy names its whole
    /// destination. Nothing changes when the name or place is the same.
    static func promptStep(_ kind: PromptKind, typed: String, folder: String, path: String) -> Step {
        let clean = normalise(typed)
        let usable = !clean.isEmpty && !clean.split(separator: "/").contains { $0 == "." || $0 == ".." }
        let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        switch kind {
        case .newFolder: return usable ? .makeFolder(join(folder, clean)) : .badName
        case .newFile: return usable ? .writeNew(join(folder, clean)) : .badName
        case .rename:
            if !validName(typed) { return .badName }
            if name == baseName(path) { return .same }
            return .move(from: path, to: join(parent(path), name))
        case .move:
            if !usable { return .badName }
            return clean == path ? .same : .move(from: path, to: clean)
        case .copy:
            return !usable || clean == path ? .badName : .copy(from: path, to: clean)
        }
    }

    /// Why the hub refused, as the page says it in one line.
    enum Refusal: String {
        case notAllowed = "not_allowed", exists, changed, tooLarge = "too_large", notText = "not_text", outside
        case root = "root_refused", intoItself = "into_itself", gone, other
    }

    static func refusal(_ error: Error) -> Refusal {
        let failure = HubFailure(error)
        let reason = failure.reason ?? ""
        switch (failure.status, reason) {
        case (403, _): return .notAllowed
        case (409, "changed"): return .changed
        case (409, _): return .exists
        case (413, _): return .tooLarge
        case (415, _): return .notText
        case (404, _): return .gone
        case (_, "root"): return .root
        case (_, "into_itself"): return .intoItself
        case (_, "absolute"), (_, "outside_root"), (_, "symlink_outside"), (_, "invalid"): return .outside
        default: return .other
        }
    }

    /// A line already in the page's words, as a failure the shared sheets show as it is.
    static func said(_ text: String) -> HubFailure {
        HubFailure(kind: .http, status: 400, code: saidCode, message: text, operationID: nil, requestID: nil, detail: text)
    }

    private static let saidCode = "files.said"

    /// The one line the page says for a failure: its own words for a refusal it knows, else the hub's.
    static func say(_ error: Error, _ l10n: L10n) -> String {
        let failure = HubFailure(error)
        if failure.code == saidCode, let message = failure.message { return message }
        if failure.kind != .http { return failure.describe(l10n) }
        let refusal = refusal(error)
        switch refusal {
        case .other, .changed: return l10n("files.failed", ["a": failure.describe(l10n)])
        default: return l10n("files.\(refusal.rawValue)")
        }
    }
}

/// The page's calls, in one profile, through the generated client only.
struct FilesOps {
    let api: HubAPI
    let profile: String

    func folder(_ path: String) async throws -> WorkspaceFolder {
        try await api.call { try await KnowledgeAPI.knowledgeListWorkspaceFiles(xHubProfile: profile, path: path, apiConfiguration: $0) }
    }

    func mkdir(_ path: String) async throws {
        _ = try await api.call {
            try await KnowledgeAPI.knowledgeCreateWorkspaceFolder(xHubProfile: profile, workspacePathBody: WorkspacePathBody(path: path), apiConfiguration: $0)
        }
    }

    func move(_ from: String, to: String) async throws {
        _ = try await api.call {
            try await KnowledgeAPI.knowledgeMoveWorkspaceFile(xHubProfile: profile, workspaceFileTransfer: WorkspaceFileTransfer(from: from, to: to), apiConfiguration: $0)
        }
    }

    func copy(_ from: String, to: String) async throws {
        _ = try await api.call {
            try await KnowledgeAPI.knowledgeCopyWorkspaceFile(xHubProfile: profile, workspaceFileTransfer: WorkspaceFileTransfer(from: from, to: to), apiConfiguration: $0)
        }
    }

    func delete(_ path: String) async throws {
        try await api.call { try await KnowledgeAPI.knowledgeDeleteWorkspaceFile(xHubProfile: profile, path: path, apiConfiguration: $0) }
    }

    func readText(_ path: String) async throws -> WorkspaceText {
        try await api.call { try await KnowledgeAPI.knowledgeReadWorkspaceText(xHubProfile: profile, path: path, apiConfiguration: $0) }
    }

    /// Saves `content` against the `etag` read (nil makes a new file); a change on disk since is refused.
    func writeText(_ path: String, content: String, etag: String?) async throws -> WorkspaceText {
        try await api.call {
            try await KnowledgeAPI.knowledgeWriteWorkspaceText(
                xHubProfile: profile, workspaceTextWrite: WorkspaceTextWrite(path: path, content: content, etag: etag), apiConfiguration: $0
            )
        }
    }

    /// Puts `file` (named as it should be named there) into `folder`; `overwrite` replaces a file of
    /// that name. `progress` hears the fraction sent (nil while unknown).
    func upload(_ file: URL, into folder: String, overwrite: Bool = false, progress: @escaping @Sendable (Double?) -> Void = { _ in }) async throws {
        _ = try await api.call { config in
            let builder = KnowledgeAPI.knowledgeUploadWorkspaceFileWithRequestBuilder(
                xHubProfile: profile, file: file, path: folder, overwrite: overwrite ? true : nil, apiConfiguration: config
            )
            let watcher = ProgressWatcher(progress)
            builder.onProgressReady = { watcher.watch($0) }
            defer { watcher.stop() }
            return try await builder.execute().body
        }
    }

    /// The file as an attachment of this profile, copied on the hub (`knowledge.attachWorkspaceFile`):
    /// nothing is fetched to the phone or sent back; a chat's composer takes it ready (`AttachmentHandOff`).
    func attach(_ path: String) async throws -> Attachment {
        try await api.call {
            try await KnowledgeAPI.knowledgeAttachWorkspaceFile(xHubProfile: profile, workspacePathBody: WorkspacePathBody(path: path), apiConfiguration: $0)
        }
    }

    /// The chats «Attach to chat» offers (`FilesRules.recentChats`).
    func recentChats() async throws -> [Session] {
        let page = try await api.call {
            try await SessionsAPI.sessionsList(xHubProfile: profile, archived: ._false, limit: 20, apiConfiguration: $0)
        }
        return FilesRules.recentChats(page.items)
    }

    /// A folder as one zip in the caches, named after it (the top folder after the profile).
    func zip(_ path: String) async throws -> URL {
        let downloaded = try await api.call { try await KnowledgeAPI.knowledgeDownloadWorkspaceFolder(xHubProfile: profile, path: path, apiConfiguration: $0) }
        let name = HubFileFetcher.safeName(path.isEmpty ? profile : FilesRules.baseName(path), fallback: "files")
        let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("zips/\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = folder.appendingPathComponent("\(name).zip")
        try FileManager.default.moveItem(at: downloaded, to: target)
        return target
    }
}
