// Settings → Users: people and adding a person.
import AVFoundation
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

    var body: some View {
        List {
            Section { Text(l10n("settings.global_note")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
            if let error { NoticeView(text: error, tone: .danger) }
            Button { adding = true } label: { LucideLabel(l10n("people_page.add"), icon: .userPlus, size: 16) }
                .accessibilityIdentifier("people.add")
            ForEach(users ?? [], id: \.id) { user in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(user.displayName).font(.system(size: FontSize.sizeMd, weight: .medium))
                        Spacer()
                        StatusPill(text: l10n("account.role_\(user.role.rawValue)"), kind: user.role.rawValue == "member" ? .neutral : .good)
                        if user.status == .disabled { StatusPill(text: l10n("users.disabled"), kind: .bad) }
                    }
                    Text("@\(user.username) · \(user.profiles.joined(separator: ", "))")
                        .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
                .accessibilityIdentifier("person.\(user.username)")
                .contextMenu {
                    if user.role.rawValue != "owner" {
                        let admin = user.role.rawValue == "admin"
                        Button(l10n(admin ? "people_page.make_member" : "people_page.make_admin")) {
                            Task { await update(user, UserAdminPatch(role: admin ? .member : .admin)) }
                        }
                        Button(l10n(user.status == .disabled ? "people_page.enable" : "people_page.disable")) {
                            Task { await update(user, UserAdminPatch(status: user.status == .disabled ? .active : .disabled)) }
                        }
                        Button(l10n("people_page.set_password")) { password = ""; passwordFor = user }
                        Button(l10n("presets.delete"), role: .destructive) { deleting = user }
                    }
                }
            }
            Text(l10n("people_page.owner_note")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
        }
        .task { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $adding) { NavigationStack { AddPersonView { adding = false; Task { await load() } } } }
        .alert(passwordFor.map { l10n("people_page.password_for", ["name": $0.displayName]) } ?? "", isPresented: Binding(get: { passwordFor != nil }, set: { if !$0 { passwordFor = nil } })) {
            SecureField(l10n("people_page.password"), text: $password)
            Button(l10n("common.save")) {
                if let user = passwordFor { let typed = password; Task { await update(user, UserAdminPatch(password: typed)) } }
                passwordFor = nil
            }
            .disabled(!AdminLogic.passwordOK(password))
            Button(l10n("common.cancel"), role: .cancel) { passwordFor = nil }
        } message: {
            Text(l10n("people_page.password_note"))
        }
        .confirmationDialog(deleting.map { l10n("people_page.delete_title", ["name": $0.displayName]) } ?? "", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button(l10n("presets.delete"), role: .destructive) {
                if let user = deleting { Task { await remove(user) } }
                deleting = nil
            }
        } message: {
            Text(l10n("people_page.delete_body"))
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
                Text(l10n(admin ? "people_page.admin_can" : "people_page.member_can"))
            }
            if !admin {
                Section(l10n("people_page.profiles")) {
                    ForEach(profiles, id: \.slug) { profile in
                        Button {
                            if chosen.contains(profile.slug) { chosen.remove(profile.slug) } else { chosen.insert(profile.slug) }
                        } label: {
                            HStack {
                                Text(profile.name).foregroundStyle(Tone.text)
                                Spacer()
                                SelectionMark(chosen: chosen.contains(profile.slug))
                            }
                        }
                    }
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
            done()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

extension PhonePage {
    static let users = PhonePage(.users) { _ in PeopleNativePage() }
}
