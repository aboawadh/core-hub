// Settings → Users (admin): everyone on the hub, what each may enter, and what a row offers — only
// what the hub accepts (the owner is not edited by an admin; nobody disables or deletes themselves).
// Below them, everyone's linked messaging accounts and the addresses that locked themselves
// (PeopleSections.swift). The rules are AdminPagesRules.swift's.
import CoreHubClient
import SwiftUI
import UIKit

struct PeopleNativePage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var users: [User]?
    @State private var error: String?
    @State private var adding = false
    @State private var deleting: User?
    @State private var passwordFor: User?
    @State private var password = ""
    @State private var placing: Placing?

    /// The profiles sheet: a member's list, or an admin made a member with one.
    struct Placing: Identifiable {
        let user: User
        let makeMember: Bool
        var id: String { user.id + (makeMember ? ".member" : "") }
    }

    private var me: String? { app.credentials?.userID }

    var body: some View {
        if app.isAdmin {
            list
        } else {
            AdminOnlyNote()
        }
    }

    private var list: some View {
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            Section {
                ForEach(users ?? [], id: \.id) { user in row(user) }
                if users == nil { ProgressView().frame(maxWidth: .infinity) }
            } header: {
                HStack {
                    Text(l10n("admin.people_title"))
                    Spacer()
                    Button { adding = true } label: { LucideLabel(l10n("people_page.add"), icon: .userPlus, size: 14) }
                        .font(.system(size: FontSize.sizeSm))
                        .textCase(nil)
                        .accessibilityIdentifier("people.add")
                }
            } footer: {
                Text(l10n("people_page.owner_note"))
            }
            AllChannelAccountsSection(users: users ?? [])
            LockoutsSection()
        }
        .task { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $adding) { NavigationStack { AddPersonView { adding = false; Task { await load() } } } }
        .sheet(item: $placing) { placing in
            NavigationStack { PersonProfilesView(user: placing.user, makeMember: placing.makeMember) { self.placing = nil; Task { await load() } } }
        }
        .alert(passwordFor.map { l10n("people_page.password_for", ["name": name($0)]) } ?? "", isPresented: Binding(get: { passwordFor != nil }, set: { if !$0 { passwordFor = nil; password = "" } })) {
            SecureField(l10n("people_page.password"), text: $password)
            Button(l10n("common.save")) {
                if let user = passwordFor {
                    let typed = password
                    // Emptied the moment it is sent: nothing on the phone keeps it after that.
                    password = ""
                    Task { await update(user, UserAdminPatch(password: typed)) }
                }
                passwordFor = nil
            }
            .disabled(!AdminLogic.passwordOK(password))
            Button(l10n("common.cancel"), role: .cancel) { passwordFor = nil; password = "" }
        } message: {
            Text(l10n(passwordFor?.id == me ? "admin.people_own_password_note" : "people_page.password_note"))
        }
        .confirmationDialog(deleting.map { l10n("people_page.delete_title", ["name": name($0)]) } ?? "", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button(l10n("presets.delete"), role: .destructive) {
                if let user = deleting { Task { await remove(user) } }
                deleting = nil
            }
        } message: {
            Text(l10n("people_page.delete_body"))
        }
    }

    private func name(_ user: User) -> String { user.displayName.isEmpty ? user.username : user.displayName }

    private func reach(_ user: User) -> String {
        switch PeopleRules.reach(user) {
        case .every: return l10n("admin.people_every_profile")
        case .none: return l10n("admin.people_no_profile")
        case .listed: return user.profiles.joined(separator: ", ")
        }
    }

    @ViewBuilder
    private func row(_ user: User) -> some View {
        let kind = PeopleRules.row(user, me: me)
        HStack(spacing: Space.s2) {
            VStack(alignment: .leading, spacing: 2) {
                Text(name(user)).font(.system(size: FontSize.sizeMd, weight: .medium))
                Text("@\(user.username) · \(reach(user))")
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(PeopleRules.reach(user) == .none ? Tone.warningSoftText : Tone.textMuted)
            }
            Spacer(minLength: Space.s1)
            if user.id == me { StatusPill(text: l10n("admin.people_you"), kind: .neutral) }
            if user.status == .disabled { StatusPill(text: l10n("admin.people_status_disabled"), kind: .warn) }
            StatusPill(text: l10n("account.role_\(user.role.rawValue)"), kind: user.role == .member ? .neutral : .good)
            if kind != .ownerNote {
                Menu { actions(user, kind) } label: { LucideIcon(.ellipsis, size: 16).foregroundStyle(Tone.textMuted).padding(Space.s1) }
                    .accessibilityLabel(l10n("chat.more"))
                    .accessibilityIdentifier("person.\(user.username).more")
            }
        }
        .accessibilityIdentifier("person.\(user.username)")
        .contextMenu { if kind != .ownerNote { actions(user, kind) } }
    }

    @ViewBuilder
    private func actions(_ user: User, _ kind: PeopleRules.Row) -> some View {
        Button { password = ""; passwordFor = user } label: { LucideLabel(l10n("people_page.set_password"), icon: .keyRound) }
        if kind == .full {
            if user.role == .admin {
                // An admin holds no list: becoming a member means choosing, right then, what they may enter.
                Button { placing = Placing(user: user, makeMember: true) } label: { LucideLabel(l10n("people_page.make_member"), icon: .shieldCheck) }
            } else {
                Button { Task { await update(user, UserAdminPatch(role: .admin)) } } label: { LucideLabel(l10n("people_page.make_admin"), icon: .shieldCheck) }
            }
            if user.role == .member {
                Button { placing = Placing(user: user, makeMember: false) } label: { LucideLabel(l10n("admin.people_profiles_edit"), icon: .layoutGrid) }
            }
            if PeopleRules.canDisableOrDelete(user, me: me) {
                Divider()
                Button {
                    Task { await update(user, UserAdminPatch(status: user.status == .disabled ? .active : .disabled)) }
                } label: {
                    Text(l10n(user.status == .disabled ? "people_page.enable" : "people_page.disable"))
                }
                Button(role: .destructive) { deleting = user } label: { LucideLabel(l10n("presets.delete"), icon: .trash) }
            }
        }
    }

    private func load() async {
        do {
            users = try await app.api.call { try await AuthAPI.authListUsers(limit: 200, apiConfiguration: $0) }.items
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func update(_ user: User, _ patch: UserAdminPatch) async {
        do {
            _ = try await app.api.call { try await AuthAPI.authUpdateUser(userId: user.id, userAdminPatch: patch, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        await load()
    }

    private func remove(_ user: User) async {
        do {
            try await app.api.call { try await AuthAPI.authDeleteUser(userId: user.id, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        await load()
    }
}

/// «Only an owner or an admin manages this page»: what a member sees if a link brings them here.
struct AdminOnlyNote: View {
    @Environment(\.l10n) private var l10n

    var body: some View {
        List { NoticeView(text: l10n("admin.only_note"), tone: .info).accessibilityIdentifier("admin.only") }
    }
}

/// The profiles to choose from, one mark each.
private struct ProfileChoice: View {
    let profiles: [Profile]
    @Binding var chosen: Set<String>

    var body: some View {
        ForEach(profiles, id: \.slug) { profile in
            Button {
                if chosen.contains(profile.slug) { chosen.remove(profile.slug) } else { chosen.insert(profile.slug) }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(profile.name).foregroundStyle(Tone.text)
                        Text(profile.slug).font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textMuted)
                    }
                    Spacer()
                    SelectionMark(chosen: chosen.contains(profile.slug))
                }
            }
            .accessibilityIdentifier("person.profile.\(profile.slug)")
        }
    }
}

/// A member's profiles; or an admin's, as the member they are about to become (never an empty list).
private struct PersonProfilesView: View {
    let user: User
    let makeMember: Bool
    let done: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var profiles: [Profile] = []
    @State private var chosen: Set<String> = []
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        let order = profiles.map(\.slug).filter { chosen.contains($0) }
        let patch = PeopleRules.profilesPatch(order, makeMember: makeMember)
        let name = user.displayName.isEmpty ? user.username : user.displayName
        Form {
            if let error { NoticeView(text: error, tone: .danger) }
            Section {
                ProfileChoice(profiles: profiles, chosen: $chosen)
            } header: {
                Text(l10n("people_page.profiles"))
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(l10n("admin.people_profiles_hint"))
                    if chosen.isEmpty { Text(l10n("admin.people_profiles_required")).foregroundStyle(Tone.danger) }
                }
            }
        }
        .navigationTitle(l10n(makeMember ? "admin.people_make_member_for" : "admin.people_profiles_for", ["name": name]))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { done() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(l10n("common.save")) { if let patch { Task { await save(patch) } } }
                    .disabled(patch == nil || busy)
                    .accessibilityIdentifier("person.profiles.save")
            }
        }
        .task {
            chosen = Set(PeopleRules.startingChoice(user, makeMember: makeMember))
            profiles = (try? await app.api.call { try await AuthAPI.authListProfiles(apiConfiguration: $0) }.items) ?? []
        }
    }

    private func save(_ patch: UserAdminPatch) async {
        busy = true
        defer { busy = false }
        do {
            _ = try await app.api.call { try await AuthAPI.authUpdateUser(userId: user.id, userAdminPatch: patch, apiConfiguration: $0) }
            done()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

private struct AddPersonView: View {
    let done: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var username = ""
    @State private var name = ""
    @State private var password = ""
    @State private var admin = false
    @State private var chosen: Set<String> = []
    @State private var profiles: [Profile] = []
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        let body = AdminLogic.create(username: username, displayName: name, password: password, admin: admin, profiles: profiles.map(\.slug).filter { chosen.contains($0) })
        Form {
            if let error { NoticeView(text: error, tone: .danger) }
            Section {
                TextField(l10n("people_page.username"), text: $username)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .font(.system(size: FontSize.sizeMd, design: .monospaced))
                    .accessibilityIdentifier("person.username")
                if !username.isEmpty && !AdminLogic.usernameOK(username) {
                    Text(l10n("people_page.username_bad")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
                }
                TextField(l10n("people_page.display_name"), text: $name)
                SecureField(l10n("people_page.password"), text: $password)
                if !password.isEmpty && !AdminLogic.passwordOK(password) {
                    Text(l10n("people_page.password_short")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
                }
            }
            Section {
                Picker(l10n("people_page.role"), selection: $admin) {
                    Text(l10n("account.role_member")).tag(false)
                    Text(l10n("account.role_admin")).tag(true)
                }
                .pickerStyle(.segmented)
            } footer: {
                Text(l10n(admin ? "people_page.admin_can" : "people_page.member_can") + (admin ? " " + l10n("admin.people_admin_everywhere") : ""))
            }
            if !admin {
                Section {
                    ProfileChoice(profiles: profiles, chosen: $chosen)
                } header: {
                    Text(l10n("people_page.profiles"))
                } footer: {
                    if chosen.isEmpty { Text(l10n("admin.people_profiles_required")) }
                }
            }
            Section {
                Button {
                    guard let body else { return }
                    Task { await add(body) }
                } label: {
                    LucideLabel(l10n("people_page.add"), icon: .userPlus, size: 16).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(Tone.accent)
                .disabled(busy || body == nil)
                .accessibilityIdentifier("person.create")
            }
        }
        .navigationTitle(l10n("people_page.add"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { done() } } }
        .task { profiles = (try? await app.api.call { try await AuthAPI.authListProfiles(apiConfiguration: $0) }.items) ?? [] }
    }

    private func add(_ body: UserCreate) async {
        busy = true
        defer { busy = false }
        do {
            _ = try await app.api.call { try await AuthAPI.authCreateUser(userCreate: body, apiConfiguration: $0) }
            password = ""
            done()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

extension PhonePage {
    static let users = PhonePage(.users) { _ in PeopleNativePage() }
}
