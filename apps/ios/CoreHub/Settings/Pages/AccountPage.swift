// Settings → Account: the person's name and password.
import CoreHubClient
import SwiftUI

struct AccountPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var name = ""
    @State private var current = ""
    @State private var fresh = ""
    @State private var note: (String, NoticeView.Kind)?

    var body: some View {
        AsyncContent(key: "me") {
            try await app.api.call { try await AuthAPI.authGetMe(apiConfiguration: $0) }
        } content: { me, reload in
            Form {
                if let note { NoticeView(text: note.0, tone: note.1) }
                Section {
                    FactRow(label: l10n("login.username"), value: me.username)
                    FactRow(label: l10n("account.role"), value: l10n("account.role_\(me.role.rawValue)"))
                    TextField(l10n("account.display_name"), text: $name)
                        .onAppear { if name.isEmpty { name = me.displayName } }
                    Button(l10n("common.save")) { Task { await saveName(reload) } }
                        .disabled(name.isEmpty || name == me.displayName)
                }
                Section(l10n("account.password")) {
                    SecureField(l10n("account.current_password"), text: $current)
                    SecureField(l10n("account.new_password"), text: $fresh)
                    Button(l10n("account.change_password")) { Task { await changePassword() } }
                        .disabled(current.isEmpty || fresh.count < 8)
                }
            }
        }
    }

    private func saveName(_ reload: @escaping () -> Void) async {
        let patch = UserSelfPatch(displayName: name)
        do {
            _ = try await app.api.call { try await AuthAPI.authUpdateMe(userSelfPatch: patch, apiConfiguration: $0) }
            note = (l10n("common.saved"), .success)
            await app.refreshAccount()
            reload()
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
    }

    private func changePassword() async {
        let change = PasswordChange(currentPassword: current, newPassword: fresh)
        do {
            try await app.api.call { try await AuthAPI.authChangePassword(passwordChange: change, apiConfiguration: $0) }
            current = ""
            fresh = ""
            note = (l10n("account.password_changed"), .success)
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
    }
}

extension PhonePage {
    static let account = PhonePage(.account) { _ in AccountPage() }
}
