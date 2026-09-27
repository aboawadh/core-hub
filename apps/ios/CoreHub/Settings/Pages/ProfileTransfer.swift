// Settings → Profiles: moving a profile in and out as Hermes's own archive (ADR 0014 stage 2).
// Export asks with or without the providers (decision §37), runs a job while Hermes writes the
// archive, downloads it and hands it to the share sheet (Save to Files among its choices). Import
// picks an archive from the phone's files, says what will be made and asks once more, uploads it
// (`purpose: import`) and runs a job while Hermes makes the profile. Both jobs and the archive live
// in the profile the person is in (`X-Hub-Profile`), as the web sends them.
import CoreHubClient
import SwiftUI
import UniformTypeIdentifiers

/// A job on its way: a spinner and the job's own line, else ours.
private struct Working: View {
    let text: String

    var body: some View {
        HStack(spacing: Space.s2) {
            ProgressView()
            Text(text).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
        }
    }
}

struct ExportProfileView: View {
    let profile: Profile
    let done: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var providers = false
    @State private var started = false
    @State private var job: Job?
    @State private var error: String?
    @State private var task: Task<Void, Never>?

    private var result: ProfileRules.Exported? { ProfileRules.exported(job) }
    private var file: HubFile? {
        result.map { .attachment(id: $0.attachmentID, name: $0.name, mime: "application/gzip", size: $0.sizeBytes) }
    }

    var body: some View {
        Form {
            Section {
                Text(l10n("admin.profiles_export_what")).font(.system(size: FontSize.sizeSm))
            }
            Section {
                Picker(l10n("admin.profiles_export_providers"), selection: $providers) {
                    Text(l10n("admin.profiles_export_without")).tag(false)
                    Text(l10n("admin.profiles_export_with")).tag(true)
                }
                .pickerStyle(.segmented)
                .disabled(started)
                .accessibilityIdentifier("export.providers")
                if providers {
                    NoticeView(text: l10n("admin.profiles_export_keys_warning"), tone: .warning).accessibilityIdentifier("export.keys_warning")
                } else {
                    NoticeView(text: l10n("admin.profiles_export_secrets"), tone: .info)
                }
            } header: {
                Text(l10n("admin.profiles_export_providers"))
            }
            Section {
                if let error { NoticeView(text: error, tone: .danger) }
                if let failure = ProfileRules.failure(job) { NoticeView(text: failure, tone: .danger) }
                if started && !ProfileRules.finished(job) {
                    Working(text: job?.progress.message ?? l10n("admin.profiles_export_running"))
                }
                if let result, let file {
                    NoticeView(text: summary(result), tone: .success).accessibilityIdentifier("export.ready")
                    download(file)
                } else if !started || ProfileRules.failure(job) != nil {
                    Button { start() } label: { LucideLabel(l10n("admin.profiles_export_start"), icon: .download).frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent).tint(Tone.accent)
                        .accessibilityIdentifier("export.start")
                }
            }
        }
        .navigationTitle(l10n("admin.profiles_export_title", ["name": profile.name]))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { task?.cancel(); done() } } }
        // The archive is fetched the moment it is ready, once.
        .onChange(of: result?.attachmentID) { _, id in
            if id != nil, let file { FileDownloads.shared.start(file, profile: app.currentProfile, app: app) }
        }
    }

    private func summary(_ result: ProfileRules.Exported) -> String {
        var lines = [l10n("admin.profiles_export_ready", ["file": result.name, "size": ProfileRules.size(result.sizeBytes)])]
        if !result.removed.isEmpty { lines.append(l10n("admin.profiles_export_removed", ["files": result.removed.joined(separator: ", ")])) }
        if !result.masked.isEmpty { lines.append(l10n("admin.profiles_export_masked", ["files": result.masked.joined(separator: ", ")])) }
        if result.providers > 0 { lines.append(l10n("admin.profiles_export_providers_carried", ["count": String(result.providers)])) }
        return lines.joined(separator: "\n")
    }

    @ViewBuilder
    private func download(_ file: HubFile) -> some View {
        switch FileDownloads.shared.state(file) {
        case .ready(let url):
            ShareLink(item: url) { LucideLabel(l10n("admin.profiles_export_share"), icon: .share2).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).tint(Tone.accent)
                .accessibilityIdentifier("export.share")
        case .failed(let failure):
            NoticeView(text: failure.describe(l10n), tone: .danger)
            Button(l10n("common.retry")) { FileDownloads.shared.start(file, profile: app.currentProfile, app: app) }
        case .loading, .idle:
            Working(text: l10n("admin.profiles_export_downloading"))
        }
    }

    private func start() {
        started = true
        error = nil
        job = nil
        let profileID = profile.id
        let scope = app.currentProfile
        let chosen = providers
        let api = app.api
        task = Task {
            do {
                let accepted = try await api.call {
                    try await AuthAPI.authExportProfile(profileId: profileID, profileExport: ProfileExport(providers: chosen), apiConfiguration: $0.inProfile(scope))
                }
                job = try await AgentJobs.follow(accepted.jobId, profile: scope, api: api, every: .seconds(1)) { job = $0 }
            } catch is CancellationError {
                return
            } catch {
                self.error = HubFailure(error).describe(l10n)
                started = false
            }
        }
    }
}

struct ImportProfileView: View {
    let taken: [String]
    /// The imported profile's name, or nil when the sheet closed without one.
    let done: (String?) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var file: URL?
    @State private var slug = ""
    @State private var name = ""
    @State private var picking = false
    @State private var asking = false
    @State private var stage: Stage?
    @State private var job: Job?
    @State private var failure: String?
    /// «Replace the default profile with this one» (decision §116), and the backup it ended with.
    @State private var replaceDefault = false
    @State private var replaced: (backup: String, name: String)?
    @State private var outdated: String?

    enum Stage { case uploading, running }

    private var size: Int { file.flatMap { try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize } ?? 0 }
    private var ready: Bool {
        file != nil && stage == nil && (replaceDefault || (!slug.isEmpty && ProfileRules.slugProblem(slug, taken: taken) == nil))
    }

    /// What the file picker offers: a gzip archive, and any file for a name the phone does not know.
    private static let archiveTypes: [UTType] = [.gzip, UTType(filenameExtension: "tgz") ?? .data, .data]

    var body: some View {
        Form {
            Section {
                Text(l10n("admin.profiles_import_what")).font(.system(size: FontSize.sizeSm))
                HStack {
                    Button(l10n(file == nil ? "admin.profiles_import_choose" : "admin.profiles_import_other")) { picking = true }
                        .buttonStyle(.bordered)
                        .disabled(stage != nil)
                        .accessibilityIdentifier("import.choose")
                    Text(file?.lastPathComponent ?? l10n("admin.profiles_import_no_file"))
                        .font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted).lineLimit(1)
                }
            }
            if let replaced {
                Section {
                    NoticeView(text: l10n("admin.profiles_import_replace_done", ["backup": replaced.backup]), tone: .success)
                        .accessibilityIdentifier("import.replaced")
                    Button(l10n("common.close")) { done(replaced.name) }
                        .accessibilityIdentifier("import.replaced_close")
                }
            }
            if let outdated {
                Section {
                    NoticeView(text: l10n("admin.profiles_import_replace_unsupported", ["name": outdated]), tone: .warning)
                    Button(l10n("common.close")) { done(outdated) }
                }
            }
            Section {
                Toggle(isOn: $replaceDefault) {
                    VStack(alignment: .leading, spacing: Space.s1) {
                        Text(l10n("admin.profiles_import_replace_default"))
                        Text(l10n("admin.profiles_import_replace_default_hint"))
                            .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                }
                .disabled(stage != nil || replaced != nil)
                .accessibilityIdentifier("import.replace_default")
            }
            Section {
                if !replaceDefault { SlugField(slug: $slug, taken: taken) }
                TextField(l10n("admin.profiles_name"), text: Binding(get: { name }, set: { name = String($0.prefix(ProfileRules.nameMax)) }))
                    .accessibilityIdentifier("import.name")
            } footer: {
                if replaceDefault { Text(l10n("admin.profiles_import_replace_name_hint")) }
            }
            Section {
                if let file, ready, !replaceDefault {
                    NoticeView(text: l10n("admin.profiles_import_summary", [
                        "file": file.lastPathComponent, "size": ProfileRules.size(size),
                        "name": name.trimmingCharacters(in: .whitespaces).isEmpty ? slug : name, "slug": slug,
                    ]), tone: .info)
                    .accessibilityIdentifier("import.summary")
                }
                switch stage {
                case .uploading?: Working(text: l10n("admin.profiles_import_uploading"))
                case .running?: Working(text: job?.progress.message ?? l10n("admin.profiles_import_running"))
                case nil: EmptyView()
                }
                if let failure { NoticeView(text: failure, tone: .danger).accessibilityIdentifier("import.failure") }
                Button { asking = true } label: {
                    LucideLabel(l10n(replaceDefault ? "admin.profiles_import_replace_confirm" : "admin.profiles_import"), icon: .file)
                        .frame(maxWidth: .infinity)
                }
                    .buttonStyle(.borderedProminent).tint(replaceDefault ? Tone.danger : Tone.accent)
                    .disabled(!ready || replaced != nil || outdated != nil)
                    .accessibilityIdentifier("import.start")
            }
        }
        .navigationTitle(l10n("admin.profiles_import_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { done(nil) }.disabled(stage != nil) } }
        .interactiveDismissDisabled(stage != nil)
        .fileImporter(isPresented: $picking, allowedContentTypes: Self.archiveTypes) { result in
            if case .success(let url) = result { take(url) }
        }
        .alert(l10n(replaceDefault ? "admin.profiles_import_replace_confirm_title" : "admin.profiles_import_confirm_title"), isPresented: $asking) {
            Button(l10n("common.cancel"), role: .cancel) {}
            if replaceDefault {
                // The second, explicit warning (decision §116).
                Button(l10n("admin.profiles_import_replace_confirm"), role: .destructive) { Task { await run() } }
            } else {
                Button(l10n("admin.profiles_import")) { Task { await run() } }
            }
        } message: {
            if replaceDefault {
                Text(l10n("admin.profiles_import_replace_confirm_body"))
            } else {
                Text(l10n("admin.profiles_import_confirm_body", ["slug": slug]))
            }
        }
    }

    /// The picked file, copied where the app may read it after the picker closes.
    private func take(_ picked: URL) {
        failure = nil
        let scoped = picked.startAccessingSecurityScopedResource()
        defer { if scoped { picked.stopAccessingSecurityScopedResource() } }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("profile-import-\(UUID().uuidString)", isDirectory: true)
        let copy = folder.appendingPathComponent(HubFileFetcher.safeName(picked.lastPathComponent, fallback: "profile.tar.gz"))
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: picked, to: copy)
        } catch {
            failure = l10n("admin.profiles_import_unreadable")
            return
        }
        file = copy
        if slug.isEmpty {
            let offered = ProfileRules.fromArchive(copy.lastPathComponent, taken: taken)
            slug = offered.slug
            if name.isEmpty { name = offered.name }
        }
    }

    private func run() async {
        guard let file else { return }
        failure = nil
        if size > ProfileRules.maxArchiveBytes {
            failure = l10n("admin.profiles_import_too_large")
            return
        }
        let scope = app.currentProfile
        let api = app.api
        stage = .uploading
        defer { stage = nil }
        let stored: Attachment
        do {
            stored = try await AttachmentUploader(backend: ImportAttachmentBackend(api: api)).upload(file, profile: scope)
        } catch {
            let hub = HubFailure(error)
            failure = hub.status == 413 ? l10n("admin.profiles_import_too_large") : hub.describe(l10n)
            return
        }
        stage = .running
        do {
            let body = ProfileRules.importBody(attachmentID: stored.id, slug: slug, name: name, replaceDefault: replaceDefault)
            let accepted = try await api.call { try await AuthAPI.authImportProfile(profileImport: body, apiConfiguration: $0.inProfile(scope)) }
            let finished = try await AgentJobs.follow(accepted.jobId, profile: scope, api: api, every: .seconds(1)) { job = $0 }
            if let made = ProfileRules.importedName(finished) {
                try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
                if let backup = ProfileRules.replacedBackup(finished) {
                    // The sheet stays: it names the backup the old default is kept as.
                    replaced = (backup, made)
                } else if replaceDefault {
                    // A hub older than the option made a new profile instead; say so.
                    outdated = made
                } else {
                    done(made)
                }
            } else {
                failure = ProfileRules.failure(finished)
            }
        } catch {
            failure = HubFailure(error).describe(l10n)
        }
    }
}
