// Settings → Updates, the owner's and admins' part (the web's UpdatesTab): where the hub takes the
// app releases from — the default channel, a GitHub repository with its token (written once,
// never shown), publishing to test on its own — and the shelf of published releases, each one
// removable. The page's first part is still this app's own update check (UpdatesPage.swift).
import CoreHubClient
import SwiftUI

/// The shelf and the source's rules, apart from the views.
enum UpdatesAdminRules {
    /// A repository as GitHub names it: `owner/repo`.
    static func repoValid(_ text: String) -> Bool {
        let parts = text.trimmingCharacters(in: .whitespaces).split(separator: "/", omittingEmptySubsequences: false)
        return parts.count == 2 && parts.allSatisfy { !$0.isEmpty && $0.allSatisfy { $0.isLetter || $0.isNumber || "-_.".contains($0) } }
    }

    /// Newest first.
    static func shelf(_ releases: [Release]) -> [Release] {
        releases.sorted { $0.publishedAt > $1.publishedAt }
    }
}

struct UpdatesAdminSections: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var settings: UpdateSettings?
    @State private var releases: [Release]?
    @State private var repo = ""
    @State private var token = ""
    @State private var error: String?
    @State private var busy = false
    @State private var removing: Release?

    var body: some View {
        // Two sections of the page's form; the load hangs on the first, the delete question on
        // the second (a modifier on a group would repeat for each).
            Section {
                if let settings {
                    Picker(l10n("updates_admin.default_channel"), selection: Binding(get: { settings.defaultChannel }, set: { next in
                        Task { await save(UpdateSettingsWrite(defaultChannel: next)) }
                    })) {
                        Text(l10n("updates_admin.channel_stable")).tag(ReleaseChannel.stable)
                        Text(l10n("updates_admin.channel_test")).tag(ReleaseChannel.test)
                    }
                    .accessibilityIdentifier("updates.channel")
                    Toggle(isOn: Binding(get: { settings.source.kind == .githubRelease }, set: { next in
                        Task { await save(UpdateSettingsWrite(source: UpdateSettingsWriteSource(kind: next ? .githubRelease : .manual))) }
                    })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(l10n("updates_admin.from_source"))
                            Text(l10n("updates_admin.from_source_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                    }
                    .accessibilityIdentifier("updates.from_source")
                    if settings.source.kind == .githubRelease {
                        VStack(alignment: .leading, spacing: 2) {
                            TextField(l10n("updates_admin.repo"), text: $repo, prompt: Text(verbatim: "owner/repo"))
                                .monoField()
                                .environment(\.layoutDirection, .leftToRight)
                                .onSubmit { Task { await saveRepo() } }
                                .accessibilityIdentifier("updates.repo")
                            Text(l10n("updates_admin.repo_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                        if repo.trimmingCharacters(in: .whitespaces) != (settings.source.repo ?? "") {
                            Button(l10n("common.save")) { Task { await saveRepo() } }
                                .disabled(busy || !UpdatesAdminRules.repoValid(repo))
                                .accessibilityIdentifier("updates.repo.save")
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            SecureField(l10n("updates_admin.token"), text: $token)
                                .monoField()
                                .accessibilityIdentifier("updates.token")
                            Text(l10n(settings.source.token != nil ? "updates_admin.token_stored" : "updates_admin.token_hint"))
                                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                        if !token.isEmpty {
                            Button(l10n("common.save")) {
                                let value = token
                                Task {
                                    await save(UpdateSettingsWrite(source: UpdateSettingsWriteSource(kind: .githubRelease, token: value)))
                                    token = ""
                                }
                            }
                            .disabled(busy)
                            .accessibilityIdentifier("updates.token.save")
                        }
                        Toggle(isOn: Binding(get: { settings.autoPublish }, set: { next in Task { await save(UpdateSettingsWrite(autoPublish: next)) } })) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(l10n("updates_admin.auto_publish"))
                                Text(l10n("updates_admin.auto_publish_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            }
                        }
                        .accessibilityIdentifier("updates.auto_publish")
                    }
                } else if error == nil {
                    ProgressView().frame(maxWidth: .infinity)
                }
                if let error { NoticeView(text: error, tone: .danger) }
            } header: {
                Text(l10n("updates_admin.source"))
            } footer: {
                Text(l10n("updates_admin.apps_note"))
            }
            .task { await load() }
            Section {
                if let releases {
                    if releases.isEmpty {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(l10n("updates_admin.empty")).font(.system(size: FontSize.sizeSm, weight: .medium))
                            Text(l10n("updates_admin.empty_body")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                    }
                    ForEach(UpdatesAdminRules.shelf(releases), id: \.id) { release in
                        releaseRow(release)
                            .swipeActions {
                                Button(l10n("kit.delete"), role: .destructive) { removing = release }
                            }
                            .contextMenu {
                                Button(role: .destructive) { removing = release } label: {
                                    Label { Text(l10n("kit.delete")) } icon: { Image(lucide: .trash) }
                                }
                            }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            } header: {
                Text(l10n("updates_admin.shelf"))
            }
        .alert(
            removing.map { l10n("updates_admin.delete_title", ["version": $0.version]) } ?? "",
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })
        ) {
            Button(l10n("common.cancel"), role: .cancel) { removing = nil }
            Button(l10n("kit.delete"), role: .destructive) {
                guard let target = removing else { return }
                removing = nil
                Task { await remove(target) }
            }
            .accessibilityIdentifier("dialog.confirm")
        } message: {
            Text(l10n("updates_admin.delete_body"))
        }
    }

    private func releaseRow(_ release: Release) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: Space.s2) {
                Text(l10n("updates_admin.platform_\(release.platform.rawValue)")).font(.system(size: FontSize.sizeSm, weight: .medium))
                Text(release.version).font(.system(size: FontSize.sizeSm, design: .monospaced))
                StatusPill(text: l10n(release.channel == .stable ? "updates_admin.channel_stable" : "updates_admin.channel_test"))
                if release.mandatory { StatusPill(text: l10n("updates_admin.mandatory"), kind: .bad) }
            }
            Text([
                l10n("updates_admin.build") + " " + String(release.build),
                ByteCount.text(Int64(release.sizeBytes), style: .file),
                release.publishedAt.shortText(app.language),
            ].joined(separator: " · "))
            .font(.system(size: FontSize.sizeXs))
            .foregroundStyle(Tone.textMuted)
        }
        .accessibilityIdentifier("updates.release.\(release.id)")
    }

    private func load() async {
        do {
            let current = try await app.api.call { try await UpdatesAPI.updatesGetSettings(apiConfiguration: $0) }
            settings = current
            repo = current.source.repo ?? ""
            releases = try await app.api.call { try await UpdatesAPI.updatesListReleases(limit: 100, apiConfiguration: $0) }.items
            error = nil
        } catch {
            if releases == nil { releases = [] }
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func saveRepo() async {
        let value = repo.trimmingCharacters(in: .whitespaces)
        guard UpdatesAdminRules.repoValid(value) else { return }
        await save(UpdateSettingsWrite(source: UpdateSettingsWriteSource(kind: .githubRelease, repo: value)))
    }

    private func save(_ write: UpdateSettingsWrite) async {
        busy = true
        defer { busy = false }
        do {
            let saved = try await app.api.call { try await UpdatesAPI.updatesSetSettings(updateSettingsWrite: write, apiConfiguration: $0) }
            settings = saved
            repo = saved.source.repo ?? repo
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func remove(_ release: Release) async {
        let id = release.id
        do {
            try await app.api.call { try await UpdatesAPI.updatesDeleteRelease(releaseId: id, apiConfiguration: $0) }
            releases?.removeAll { $0.id == id }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}
