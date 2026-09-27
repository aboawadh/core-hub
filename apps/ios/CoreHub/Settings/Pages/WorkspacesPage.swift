// Settings → Profiles (destination `workspaces`: the code's word; a person reads «profile»), admin.
// The separate rooms this hub keeps (ADR 0005), each a Hermes profile (ADR 0014). The drawer's
// switcher changes which one the person is in; this page decides what exists: a new one (from
// scratch or as a copy), a new name (never a new id), archive, export and import
// (ProfileTransfer.swift). The rules are AdminPagesRules.swift's.
import CoreHubClient
import SwiftUI

struct WorkspacesPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var profiles: [Profile]?
    @State private var error: String?
    @State private var adding = false
    @State private var importing = false
    @State private var renaming: ProfileItem?
    @State private var exporting: ProfileItem?
    @State private var archiving: Profile?
    @State private var imported: String?

    var body: some View {
        if app.isAdmin {
            list
        } else {
            AdminOnlyNote()
        }
    }

    private var list: some View {
        List {
            Section {
                Text(l10n("admin.profiles_note")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                HStack(spacing: Space.s2) {
                    Spacer()
                    Button { importing = true } label: { LucideLabel(l10n("admin.profiles_import"), icon: .file, size: 14) }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("profiles.import")
                    Button { adding = true } label: { LucideLabel(l10n("admin.profiles_add"), icon: .plus, size: 14) }
                        .buttonStyle(.borderedProminent).tint(Tone.accent)
                        .accessibilityIdentifier("profiles.add")
                }
                .font(.system(size: FontSize.sizeSm))
            }
            if let error { NoticeView(text: error, tone: .danger) }
            if let imported { NoticeView(text: l10n("admin.profiles_import_done", ["name": imported]), tone: .success) }
            Section {
                if let profiles {
                    ForEach(profiles, id: \.id) { profile in row(profile) }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
        }
        .task { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $adding) {
            NavigationStack { AddProfileView(existing: profiles ?? []) { made in adding = false; if made { Task { await changed() } } } }
        }
        .sheet(isPresented: $importing) {
            NavigationStack {
                ImportProfileView(taken: (profiles ?? []).map(\.slug)) { name in
                    importing = false
                    if let name { imported = name; Task { await changed() } }
                }
            }
        }
        .sheet(item: $renaming) { item in
            NavigationStack { RenameProfileView(profile: item.profile) { renamed in renaming = nil; if renamed { Task { await changed() } } } }
        }
        .sheet(item: $exporting) { item in
            NavigationStack { ExportProfileView(profile: item.profile) { exporting = nil } }
        }
        // The hub's word is «archive»: the rows stay and only the memberships go (the message says exactly that).
        .confirmationDialog(
            archiving.map { l10n("admin.profiles_archive_title", ["name": $0.name]) } ?? "",
            isPresented: Binding(get: { archiving != nil }, set: { if !$0 { archiving = nil } }), titleVisibility: .visible
        ) {
            Button(l10n("admin.profiles_archive"), role: .destructive) {
                if let profile = archiving { Task { await archive(profile) } }
                archiving = nil
            }
            .accessibilityIdentifier("dialog.confirm")
        } message: {
            Text(archiving.map { l10n("admin.profiles_archive_body", ["sessions": String($0.sessionCount)]) } ?? "")
        }
    }

    @ViewBuilder
    private func row(_ profile: Profile) -> some View {
        HStack(spacing: Space.s2) {
            VStack(alignment: .leading, spacing: 2) {
                Text(profile.name).font(.system(size: FontSize.sizeMd, weight: .medium))
                Text("\u{2066}\(profile.slug)\u{2069} · " + l10n("admin.profiles_counts", ["agents": String(profile.agentCount), "sessions": String(profile.sessionCount)]))
                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
            Spacer(minLength: Space.s1)
            if profile.slug == app.currentProfile { StatusPill(text: l10n("admin.profiles_current"), kind: .good) }
            if profile.slug == "default" { StatusPill(text: l10n("admin.profiles_default"), kind: .neutral) }
            Menu { actions(profile) } label: { LucideIcon(.ellipsis, size: 16).foregroundStyle(Tone.textMuted).padding(Space.s1) }
                .accessibilityLabel(l10n("chat.more"))
                .accessibilityIdentifier("profile.\(profile.slug).more")
        }
        .accessibilityIdentifier("profile.\(profile.slug)")
        .contextMenu { actions(profile) }
    }

    @ViewBuilder
    private func actions(_ profile: Profile) -> some View {
        Button { renaming = ProfileItem(profile: profile) } label: { LucideLabel(l10n("admin.profiles_rename"), icon: .pencil) }
        Button { exporting = ProfileItem(profile: profile) } label: { LucideLabel(l10n("admin.profiles_export"), icon: .download) }
        if ProfileRules.canArchive(profile.slug) {
            Button(role: .destructive) { archiving = profile } label: { LucideLabel(l10n("admin.profiles_archive"), icon: .archive) }
        }
    }

    private func load() async {
        do {
            profiles = try await app.api.call { try await AuthAPI.authListProfiles(apiConfiguration: $0) }.items
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    /// The list again, and the drawer's switcher with it.
    private func changed() async {
        await load()
        await app.refreshAccount()
    }

    private func archive(_ profile: Profile) async {
        do {
            try await app.api.call { try await AuthAPI.authDeleteProfile(profileId: profile.id, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        await changed()
    }
}

/// A profile a sheet opens on.
struct ProfileItem: Identifiable {
    let profile: Profile
    var id: String { profile.id }
}

/// The hub's refusal in words: Hermes's own when Hermes said no.
func profileRefusal(_ error: Error, _ l10n: L10n) -> String {
    let failure = HubFailure(error)
    if let words = ProfileRules.hermesRefusal(failure) { return l10n("admin.profiles_refused", ["message": words]) }
    return failure.describe(l10n)
}

/// The slug field with what is wrong with it, before the hub says so.
struct SlugField: View {
    @Binding var slug: String
    let taken: [String]
    @Environment(\.l10n) private var l10n

    var body: some View {
        TextField(l10n("admin.profiles_slug"), text: Binding(get: { slug }, set: { slug = $0.lowercased().trimmingCharacters(in: .whitespaces) }))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.system(size: FontSize.sizeMd, design: .monospaced))
            .environment(\.layoutDirection, .leftToRight)
            .accessibilityIdentifier("profile.slug")
        switch ProfileRules.slugProblem(slug, taken: taken) {
        case .bad?: Text(l10n("admin.profiles_slug_bad")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
        case .taken?: Text(l10n("admin.profiles_slug_taken")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
        case nil: Text(l10n("admin.profiles_slug_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
        }
    }
}

private struct AddProfileView: View {
    let existing: [Profile]
    let done: (Bool) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var name = ""
    @State private var slug = ""
    @State private var copy = false
    @State private var from: String?
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        let taken = existing.map(\.slug)
        let body = ProfileRules.create(name: name, slug: slug, copy: copy, cloneFrom: from, taken: taken)
        Form {
            if let error { NoticeView(text: error, tone: .danger) }
            Section {
                TextField(l10n("admin.profiles_name"), text: Binding(get: { name }, set: { next in
                    slug = ProfileRules.followName(slug: slug, old: name, new: next)
                    name = String(next.prefix(ProfileRules.nameMax))
                }))
                .accessibilityIdentifier("profile.name")
                SlugField(slug: $slug, taken: taken)
            }
            Section {
                Picker(l10n("admin.profiles_origin"), selection: $copy) {
                    Text(l10n("admin.profiles_origin_blank")).tag(false)
                    Text(l10n("admin.profiles_origin_clone")).tag(true)
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("profile.origin")
            } header: {
                Text(l10n("admin.profiles_origin"))
            } footer: {
                Text(l10n(copy ? "admin.profiles_clone_what" : "admin.profiles_origin_blank_what"))
            }
            if copy {
                Section(l10n("admin.profiles_clone")) {
                    ForEach(existing, id: \.slug) { profile in
                        Button { from = profile.slug } label: {
                            HStack {
                                Text(profile.name).foregroundStyle(Tone.text)
                                Spacer()
                                SelectionMark(chosen: from == profile.slug)
                            }
                        }
                        .accessibilityIdentifier("profile.clone.\(profile.slug)")
                    }
                }
            }
        }
        .navigationTitle(l10n("admin.profiles_add"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { done(false) } }
            ToolbarItem(placement: .confirmationAction) {
                if busy {
                    ProgressView()
                } else {
                    Button(l10n("admin.profiles_add")) { if let body { Task { await create(body) } } }
                        .disabled(body == nil)
                        .accessibilityIdentifier("profile.create")
                }
            }
        }
    }

    private func create(_ body: ProfileCreate) async {
        busy = true
        defer { busy = false }
        do {
            _ = try await app.api.call { try await AuthAPI.authCreateProfile(profileCreate: body, apiConfiguration: $0) }
            done(true)
        } catch {
            self.error = profileRefusal(error, l10n)
        }
    }
}

/// A new name, never a new id: the slug stays, since chats, channels and schedules use it.
private struct RenameProfileView: View {
    let profile: Profile
    let done: (Bool) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var name = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        let patch = ProfileRules.rename(current: profile.name, typed: name)
        Form {
            if let error { NoticeView(text: error, tone: .danger) }
            Section {
                TextField(l10n("admin.profiles_name"), text: Binding(get: { name }, set: { name = String($0.prefix(ProfileRules.nameMax)) }))
                    .accessibilityIdentifier("profile.rename")
            } footer: {
                Text(l10n("admin.profiles_rename_hint", ["slug": "\u{2066}\(profile.slug)\u{2069}"]))
            }
        }
        .navigationTitle(l10n("admin.profiles_rename_title", ["name": profile.name]))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { done(false) } }
            ToolbarItem(placement: .confirmationAction) {
                Button(l10n("common.save")) { if let patch { Task { await save(patch) } } }
                    .disabled(patch == nil || busy)
                    .accessibilityIdentifier("profile.rename.save")
            }
        }
        .onAppear { if name.isEmpty { name = profile.name } }
    }

    private func save(_ patch: ProfilePatch) async {
        busy = true
        defer { busy = false }
        do {
            _ = try await app.api.call { try await AuthAPI.authUpdateProfile(profileId: profile.id, profilePatch: patch, apiConfiguration: $0) }
            done(true)
        } catch {
            self.error = profileRefusal(error, l10n)
        }
    }
}

extension PhonePage {
    static let workspaces = PhonePage(.workspaces) { _ in WorkspacesPage() }
}
