// Settings → Files (apps batches 11 and 13; the web's FilesTool, contract decision §65): the working
// folder of the profile in the top chip, for its owner and admins. Browse by the folder trail, search
// and sort the folder; open a file the way a chat's file opens (Quick Look, the player, the share
// sheet — `FileOpener`), share or save it («Save to Files» in the share sheet), or attach it to a new
// chat; upload files and photos with their progress; make a folder or a text file, rename, move,
// copy, delete (after a confirm); edit text with the save-conflict check. The rules and calls are
// FilesRules.swift; the header and rows FilesViews.swift. Android's FilesPage.kt is the twin.
import CoreHubClient
import Observation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// One entry as a list row.
struct FileItem: Identifiable {
    let entry: WorkspaceFileEntry
    var id: String { entry.path }
}

/// A text file open in the editor: `etag` nil is a new file.
struct FilesEditing: Identifiable {
    let id = UUID()
    let path: String
    let text: String
    let etag: String?
}

/// A prompt for one name or destination.
struct FilesPrompt: Identifiable {
    let id = UUID()
    let kind: FilesRules.PromptKind
    let entry: WorkspaceFileEntry?
}

/// One file on its way up: its progress, or why it did not go.
struct FilesUpload: Identifiable, Equatable {
    let id = UUID()
    let name: String
    var fraction: Double?
    var error: String?
}

/// Where the list reads its folder: the search and order it is arranged by, and the folder as last
/// read, so a new search or order re-arranges it without reading it again (a pull reads it again).
@Observable
final class FilesSource {
    /// The folder as last read: its limits and whether it was cut short, for the header.
    var info: WorkspaceFolder?
    @ObservationIgnored var query = ""
    @ObservationIgnored var sort = FilesRules.Sort.name
    @ObservationIgnored var reuse = false
}

struct FilesPage: View {
    private struct Waiting {
        let file: HubFile
        let then: (URL) -> Void
    }

    private struct Replacing {
        let name: String
        let answer: CheckedContinuation<Bool, Never>
    }

    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var folder = ""
    @State private var typed = ""
    @State private var query = ""
    @State private var sort = FilesRules.Sort.name
    @State private var list: PagedList<FileItem>?
    @State private var source = FilesSource()
    @State private var problem: String?
    @State private var opener = FileOpener()
    @State private var waiting: Waiting?
    @State private var sharing: SharedFile?
    @State private var zipping = false
    @State private var editing: FilesEditing?
    @State private var reopen: String?
    @State private var prompt: FilesPrompt?
    @State private var writeNew: String?
    @State private var deleting: WorkspaceFileEntry?
    @State private var uploads: [FilesUpload] = []
    @State private var replacing: Replacing?
    @State private var choosingFiles = false
    @State private var choosingPhotos = false
    @State private var photos: [PhotosPickerItem] = []

    private var profile: String { app.currentProfile }
    private var ops: FilesOps { FilesOps(api: app.api, profile: profile) }
    private var key: String { "\(profile)/\(folder)/\(query)/\(sort.rawValue)" }

    var body: some View {
        Group {
            if let list {
                ListScaffold(
                    list,
                    key: key,
                    query: $typed,
                    emptyIcon: .folder,
                    emptyTitle: l10n("files.empty"),
                    emptyMessage: l10n("files.empty_body"),
                    actions: { actions($0.entry) },
                    swipe: { item in [RowAction(title: l10n("kit.delete"), icon: .trash, destructive: true) { deleting = item.entry }] },
                    tag: "files.list"
                ) { item in
                    FilesEntryRow(
                        entry: item.entry,
                        state: FilesRules.hubFile(item.entry, profile: profile).flatMap { FileDownloads.shared.states[$0.cacheKey] },
                        language: app.language,
                        actions: actions(item.entry),
                        open: { activate(item.entry) },
                        cancel: { if let file = FilesRules.hubFile(item.entry, profile: profile) { FileDownloads.shared.cancel(file) } }
                    )
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            FilesHeader(
                profileName: app.profileName(profile), folder: folder, sort: $sort, busy: zipping, problem: problem,
                uploads: uploads, folderInfo: source.info,
                open: { open($0) },
                uploadFiles: { choosingFiles = true }, uploadPhotos: { choosingPhotos = true },
                newFolder: { prompt = FilesPrompt(kind: .newFolder, entry: nil) },
                newFile: { prompt = FilesPrompt(kind: .newFile, entry: nil) },
                shareZip: { shareZip(folder) },
                dismissProblem: { problem = nil },
                dismissUpload: { id in uploads.removeAll { $0.id == id } }
            )
        }
        // A new list for each profile and folder; the scaffold reads it when its key changes.
        .onChange(of: "\(profile)/\(folder)", initial: true) { _, _ in
            source.reuse = false
            source.info = nil
            list = makeList(profile: profile, folder: folder)
        }
        .onChange(of: profile) { _, _ in open("") }
        .onChange(of: sort) { _, new in
            source.sort = new
            source.reuse = source.info != nil
        }
        // The folder is re-arranged once the typing pauses, not at every letter.
        .task(id: typed) {
            if typed != query { try? await Task.sleep(for: .milliseconds(300)) }
            guard !Task.isCancelled, typed != query else { return }
            source.query = typed
            source.reuse = source.info != nil
            query = typed
        }
        .fileOpener(opener)
        .onChange(of: FileDownloads.shared.states) { _, states in settle(states) }
        .sheet(item: $sharing) { item in ActivitySheet(items: [item.url]) }
        .sheet(item: $editing, onDismiss: reopenIfAsked) { edit in editor(edit) }
        .sheet(item: $prompt, onDismiss: openNewFile) { prompt in promptSheet(prompt) }
        .confirmDelete($deleting, name: { $0.name }, delete: { [ops, l10n] entry in
            do { try await ops.delete(entry.path) } catch { throw FilesRules.said(FilesRules.say(error, l10n)) }
        }, deleted: { _ in reload() })
        .alert(
            replacing.map { l10n("files.replace_title", ["a": $0.name]) } ?? "",
            isPresented: Binding(get: { replacing != nil }, set: { if !$0 { answerReplace(false) } })
        ) {
            Button(l10n("files.skip"), role: .cancel) { answerReplace(false) }
            Button(l10n("files.replace"), role: .destructive) { answerReplace(true) }
        } message: {
            Text(l10n("files.replace_body"))
        }
        .fileImporter(isPresented: $choosingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            let into = folder
            let picked = urls.map { url in (url.lastPathComponent, FilesPage.stage(url)) }
            Task { await uploadAll(picked, into: into) }
        }
        .photosPicker(
            isPresented: $choosingPhotos, selection: $photos, maxSelectionCount: 20, matching: .any(of: [.images, .videos]),
            // `.current`: the library's own file (HEIC stays HEIC), never a transcoded copy.
            preferredItemEncoding: .current
        )
        .onChange(of: photos) { _, picked in
            guard !picked.isEmpty else { return }
            photos = []
            let into = folder
            Task {
                var staged: [(String, URL?)] = []
                for (index, item) in picked.enumerated() {
                    let type = item.supportedContentTypes.first
                    let name = "photo-\(FilesPage.stamp())-\(index + 1).\(type?.preferredFilenameExtension ?? "jpg")"
                    let data = try? await item.loadTransferable(type: Data.self)
                    staged.append((name, data.flatMap { FilesPage.stage($0, name: name) }))
                }
                await uploadAll(staged, into: into)
            }
        }
        .accessibilityIdentifier("files.page")
    }

    // MARK: - the list

    private func makeList(profile: String, folder: String) -> PagedList<FileItem> {
        let ops = FilesOps(api: app.api, profile: profile)
        let source = self.source
        let l10n = self.l10n
        return PagedList { _ in
            let answer: WorkspaceFolder
            if source.reuse, let known = source.info, known.path == folder {
                answer = known
            } else {
                do { answer = try await ops.folder(folder) } catch { throw FilesRules.said(FilesRules.say(error, l10n)) }
            }
            await MainActor.run {
                source.reuse = false
                source.info = answer
            }
            let items = FilesRules.arrange(answer.entries, query: source.query, sort: source.sort).map(FileItem.init(entry:))
            return ListPage(items: items, next: nil)
        }
    }

    private func reload() {
        source.reuse = false
        guard let list else { return }
        Task { await list.refresh() }
    }

    private func open(_ path: String) {
        folder = path
        problem = nil
    }

    private func activate(_ entry: WorkspaceFileEntry) {
        switch entry.kind {
        case .directory:
            open(entry.path)
        case .link:
            problem = l10n("files.link_outside_hint")
        case .file:
            // Text opens where it can be read and edited; anything else the way a chat's file opens.
            if entry.editable {
                edit(entry.path)
            } else if let file = FilesRules.hubFile(entry, profile: profile) {
                opener.open(file, profile: profile, app: app)
            }
        }
    }

    private func actions(_ entry: WorkspaceFileEntry) -> [RowAction] {
        var list: [RowAction] = []
        let isFile = entry.kind == .file, isFolder = entry.kind == .directory
        if isFile, let file = FilesRules.hubFile(entry, profile: profile) {
            list.append(RowAction(title: l10n("files.open"), icon: .externalLink) { opener.open(file, profile: profile, app: app) })
        }
        if isFolder { list.append(RowAction(title: l10n("files.open"), icon: .folder) { open(entry.path) }) }
        if isFile, entry.editable { list.append(RowAction(title: l10n("files.edit"), icon: .pencil) { edit(entry.path) }) }
        if isFile { list.append(RowAction(title: l10n("files.share"), icon: .share2) { withLocal(entry) { sharing = SharedFile(url: $0) } }) }
        if isFolder { list.append(RowAction(title: l10n("files.share_zip"), icon: .share2) { shareZip(entry.path) }) }
        if isFile { list.append(RowAction(title: l10n("files.attach"), icon: .paperclip) { attach(entry) }) }
        list.append(RowAction(title: l10n("files.rename"), icon: .squarePen) { prompt = FilesPrompt(kind: .rename, entry: entry) })
        list.append(RowAction(title: l10n("files.move"), icon: .folderInput) { prompt = FilesPrompt(kind: .move, entry: entry) })
        if entry.kind != .link {
            list.append(RowAction(title: l10n("files.copy"), icon: .copy) { prompt = FilesPrompt(kind: .copy, entry: entry) })
        }
        list.append(RowAction(title: l10n("kit.delete"), icon: .trash, destructive: true) { deleting = entry })
        return list
    }

    // MARK: - files on the phone

    /// The file on the phone first (fetched once, with its progress on its row), then `then`.
    private func withLocal(_ entry: WorkspaceFileEntry, then: @escaping (URL) -> Void) {
        guard let file = FilesRules.hubFile(entry, profile: profile) else { return }
        if case .ready(let url) = FileDownloads.shared.state(file), FileManager.default.fileExists(atPath: url.path) {
            then(url)
            return
        }
        waiting = Waiting(file: file, then: then)
        FileDownloads.shared.start(file, profile: profile, app: app)
    }

    private func settle(_ states: [String: FileDownloads.State]) {
        guard let pending = waiting else { return }
        switch states[pending.file.cacheKey] {
        case .ready(let url):
            waiting = nil
            pending.then(url)
        case .failed(let failure):
            waiting = nil
            problem = FilesRules.say(failure, l10n)
        case .loading:
            break
        default:
            waiting = nil
        }
    }

    /// Handed to a new chat the way another app's share is: it lands in the composer's tray and uploads there.
    private func attach(_ entry: WorkspaceFileEntry) {
        withLocal(entry) { local in
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent("files-\(UUID().uuidString)", isDirectory: true)
            let copy = folder.appendingPathComponent(local.lastPathComponent)
            do {
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                try FileManager.default.copyItem(at: local, to: copy)
            } catch {
                problem = FilesRules.say(error, l10n)
                return
            }
            app.pendingFiles.append(copy)
            if app.pendingDraft == nil { app.pendingDraft = "" }
        }
    }

    private func shareZip(_ path: String) {
        zipping = true
        Task {
            do { sharing = SharedFile(url: try await ops.zip(path)) } catch { problem = FilesRules.say(error, l10n) }
            zipping = false
        }
    }

    // MARK: - the editor

    private func edit(_ path: String) {
        Task {
            do {
                let text = try await ops.readText(path)
                editing = FilesEditing(path: path, text: text.content, etag: text.etag)
            } catch {
                problem = FilesRules.say(error, l10n)
            }
        }
    }

    private func editor(_ edit: FilesEditing) -> some View {
        let name = FilesRules.baseName(edit.path)
        return TextEditorSheet(
            title: l10n(edit.etag == nil ? "files.new_file_title" : "files.edit_title", ["a": name]),
            initial: edit.text,
            markdown: FilesRules.markdown(name),
            subtitle: edit.path,
            tag: "files.editor",
            // The sheet closes; the file is read again and opens with what is on disk now.
            reload: { reopen = edit.path },
            save: { [ops, l10n] text in
                do {
                    _ = try await ops.writeText(edit.path, content: text, etag: edit.etag)
                } catch {
                    // A change on disk stays the hub's own refusal, so the editor offers Reload.
                    if FilesRules.refusal(error) == .changed { throw error }
                    throw FilesRules.said(FilesRules.say(error, l10n))
                }
                reload()
            }
        )
    }

    private func reopenIfAsked() {
        guard let path = reopen else { return }
        reopen = nil
        edit(path)
    }

    // MARK: - prompts

    private func openNewFile() {
        guard let path = writeNew else { return }
        writeNew = nil
        editing = FilesEditing(path: path, text: "", etag: nil)
    }

    /// A prompt's title, the value it starts with, and its button.
    private func promptWords(_ kind: FilesRules.PromptKind, entry: WorkspaceFileEntry?) -> (String, String, String) {
        let name = entry?.name ?? ""
        switch kind {
        case .newFolder: return (l10n("files.new_folder"), "", l10n("files.create"))
        case .newFile: return (l10n("files.new_file"), "", l10n("files.create"))
        case .rename: return (l10n("files.rename_title", ["a": name]), name, l10n("files.rename"))
        case .move: return (l10n("files.move_title", ["a": name]), entry?.path ?? "", l10n("files.move"))
        case .copy:
            return (
                l10n("files.copy_title", ["a": name]),
                FilesRules.join(FilesRules.parent(entry?.path ?? ""), FilesRules.copyName(name)),
                l10n("files.copy")
            )
        }
    }

    private func promptSheet(_ prompt: FilesPrompt) -> some View {
        let entry = prompt.entry
        let destination = prompt.kind == .move || prompt.kind == .copy
        let (title, initial, save) = promptWords(prompt.kind, entry: entry)
        let ops = self.ops
        let l10n = self.l10n
        let folder = self.folder
        return FormSheet(
            title: title,
            fields: [FormField(
                key: "value", label: l10n(destination ? "files.destination" : "files.name"), required: true,
                help: l10n(destination ? "files.destination_hint" : "files.name_help"), mono: destination
            )],
            initial: ["value": initial],
            saveTitle: save,
            tag: "files.prompt"
        ) { values in
            let step = FilesRules.promptStep(prompt.kind, typed: values["value"] ?? "", folder: folder, path: entry?.path ?? "")
            do {
                switch step {
                case .badName: throw FilesRules.said(l10n("files.bad_name"))
                case .same: return
                case .makeFolder(let path): try await ops.mkdir(path)
                case .writeNew(let path):
                    // The editor opens once this sheet has gone (`openNewFile`).
                    writeNew = path
                    return
                case .move(let from, let to): try await ops.move(from, to: to)
                case .copy(let from, let to): try await ops.copy(from, to: to)
                }
            } catch {
                throw FilesRules.said(FilesRules.say(error, l10n))
            }
            reload()
        }
    }

    // MARK: - uploads

    private func uploadAll(_ picked: [(String, URL?)], into: String) async {
        var done = 0
        let max = source.info?.limits.maxUploadBytes
        for (name, file) in picked {
            guard let file else {
                uploads.append(FilesUpload(name: name, error: l10n("files.upload_unreadable", ["a": name])))
                continue
            }
            defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
            let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if FilesRules.tooLarge(size, max: max) {
                uploads.append(FilesUpload(name: name, error: l10n("files.upload_too_large", ["a": name, "b": FilesRules.size(max ?? 0)])))
                continue
            }
            let item = FilesUpload(name: name, fraction: 0)
            uploads.append(item)
            let progress: @Sendable (Double?) -> Void = { fraction in
                Task { @MainActor in
                    if let index = uploads.firstIndex(where: { $0.id == item.id }) { uploads[index].fraction = fraction }
                }
            }
            var failure: Error?
            do { try await ops.upload(file, into: into, progress: progress) } catch { failure = error }
            if FilesRules.nameTaken(failure) {
                guard await askReplace(name) else {
                    uploads.removeAll { $0.id == item.id }
                    continue
                }
                failure = nil
                do { try await ops.upload(file, into: into, overwrite: true, progress: progress) } catch { failure = error }
            }
            if let failure, let index = uploads.firstIndex(where: { $0.id == item.id }) {
                uploads[index].fraction = nil
                uploads[index].error = FilesRules.say(failure, l10n)
            } else {
                done += 1
                uploads.removeAll { $0.id == item.id }
            }
        }
        if done > 0, into == folder { reload() }
    }

    private func askReplace(_ name: String) async -> Bool {
        await withCheckedContinuation { continuation in replacing = Replacing(name: name, answer: continuation) }
    }

    private func answerReplace(_ yes: Bool) {
        guard let pending = replacing else { return }
        replacing = nil
        pending.answer.resume(returning: yes)
    }

    /// A picked file copied under its own name into a folder of its own (the upload's file name is the entry's).
    static func stage(_ url: URL) -> URL? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return nil }
        return stage(data, name: url.lastPathComponent)
    }

    static func stage(_ data: Data, name: String) -> URL? {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("files-up-\(UUID().uuidString)", isDirectory: true)
        let file = folder.appendingPathComponent(HubFileFetcher.safeName(name, fallback: "file"))
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try data.write(to: file)
            return file
        } catch {
            return nil
        }
    }

    static func stamp() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: Date())
    }
}

extension PhonePage {
    static let files = PhonePage(.files) { _ in FilesPage() }
}
