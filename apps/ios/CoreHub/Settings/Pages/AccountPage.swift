// Settings → Account: who you are on this hub — your picture, your name, your password, and the
// messaging accounts that act as you. Everything here is the person's own (`auth.updateMe`,
// `auth.changePassword`, `auth.*MyChannelIdentit*`). Android's AccountPage.kt is the twin.
import CoreHubClient
import PhotosUI
import SwiftUI
import UIKit

struct AccountPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var name = ""
    @State private var current = ""
    @State private var fresh = ""
    @State private var again = ""
    @State private var note: (String, NoticeView.Kind)?
    @State private var photo: PhotosPickerItem?
    @State private var picture: UIImage?
    @State private var busy = false

    var body: some View {
        AsyncContent(key: "me") {
            try await app.api.call { try await AuthAPI.authGetMe(apiConfiguration: $0) }
        } content: { me, reload in
            Form {
                if let note { NoticeView(text: note.0, tone: note.1) }
                Section {
                    header(me, reload)
                }
                Section {
                    FactRow(label: l10n("login.username"), value: me.username)
                    FactRow(label: l10n("account.role"), value: l10n("account.role_\(me.role.rawValue)"))
                    FactRow(label: l10n("own_settings.profiles"), value: me.profiles.map { app.profileName($0) }.joined(separator: ", "))
                }
                Section(l10n("account.display_name")) {
                    TextField(l10n("account.display_name"), text: $name)
                        .onAppear { if name.isEmpty { name = me.displayName } }
                        .accessibilityIdentifier("account.name")
                    Button(l10n("common.save")) { Task { await saveName(me, reload) } }
                        .disabled(busy || OwnSettingsRules.nameToSave(name, current: me.displayName) == nil)
                        .accessibilityIdentifier("account.name.save")
                }
                password
                ChannelAccountsSection()
            }
            .task(id: me.updatedAt) { await loadPicture(me) }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task { await upload(item, reload) }
            }
        }
    }

    // MARK: - Picture and name

    private func header(_ me: User, _ reload: @escaping () -> Void) -> some View {
        HStack(spacing: Space.s3) {
            ZStack {
                Circle().fill(Tone.accentSoft)
                if let picture {
                    Image(uiImage: picture).resizable().scaledToFill()
                } else {
                    Text(OwnSettingsRules.initials(me.displayName.isEmpty ? me.username : me.displayName))
                        .font(.system(size: FontSize.sizeLg, weight: .semibold))
                        .foregroundStyle(Tone.accent)
                }
            }
            .frame(width: 64, height: 64)
            .clipShape(Circle())
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Space.s1) {
                Text(me.displayName.isEmpty ? me.username : me.displayName)
                    .font(.system(size: FontSize.sizeMd, weight: .semibold))
                    .contentDirection(of: me.displayName)
                PhotosPicker(selection: $photo, matching: .images) {
                    LucideLabel(l10n("own_settings.photo_choose"), icon: .image, size: 14)
                }
                .disabled(busy)
                .accessibilityIdentifier("account.photo")
                if me.avatar.kind == .image {
                    Button(role: .destructive) {
                        Task { await removePicture(reload) }
                    } label: {
                        LucideLabel(l10n("own_settings.photo_remove"), icon: .trash, size: 14)
                    }
                    .disabled(busy)
                    .accessibilityIdentifier("account.photo.remove")
                }
            }
            .buttonStyle(.borderless)
        }
    }

    private func loadPicture(_ me: User) async {
        guard me.avatar.kind == .image else {
            picture = nil
            return
        }
        if let url = try? await app.api.call({ try await AuthAPI.authGetUserAvatar(userId: me.id, apiConfiguration: $0) }),
           let data = try? Data(contentsOf: url) {
            picture = UIImage(data: data)
        }
    }

    private func upload(_ item: PhotosPickerItem, _ reload: @escaping () -> Void) async {
        busy = true
        defer {
            busy = false
            photo = nil
        }
        guard let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data),
              let url = Self.dataURL(image) else {
            note = (l10n("own_settings.photo_too_big"), .danger)
            return
        }
        await update(UserSelfPatch(avatar: AvatarInput(kind: .image, dataUrl: url)), saved: "own_settings.photo_saved", reload)
    }

    private func removePicture(_ reload: @escaping () -> Void) async {
        busy = true
        defer { busy = false }
        await update(UserSelfPatch(avatar: AvatarInput(kind: .generated)), saved: "common.saved", reload)
    }

    /// The picture drawn at most `avatarSide` on its longest side, as a JPEG the hub takes; a lower
    /// quality is tried once when the first is still too big.
    static func dataURL(_ image: UIImage) -> String? {
        let size = OwnSettingsRules.avatarSize(width: Double(image.size.width), height: Double(image.size.height))
        guard size.width > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let bounds = CGRect(x: 0, y: 0, width: size.width, height: size.height)
        let drawn = UIGraphicsImageRenderer(size: bounds.size, format: format).image { _ in image.draw(in: bounds) }
        for quality in [0.85, 0.6] {
            if let jpeg = drawn.jpegData(compressionQuality: quality), let url = OwnSettingsRules.avatarDataURL(jpeg: jpeg) { return url }
        }
        return nil
    }

    private func saveName(_ me: User, _ reload: @escaping () -> Void) async {
        guard let value = OwnSettingsRules.nameToSave(name, current: me.displayName) else { return }
        busy = true
        defer { busy = false }
        name = value
        await update(UserSelfPatch(displayName: value), saved: "common.saved", reload)
    }

    private func update(_ change: UserSelfPatch, saved: String, _ reload: @escaping () -> Void) async {
        do {
            _ = try await app.api.call { try await AuthAPI.authUpdateMe(userSelfPatch: change, apiConfiguration: $0) }
            note = (l10n(saved), .success)
            await app.refreshAccount()
            reload()
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
    }

    // MARK: - Password

    private var password: some View {
        let problem = OwnSettingsRules.passwordProblem(new: fresh, again: again)
        return Section {
            SecureField(l10n("account.current_password"), text: $current)
                .textContentType(.password)
                .accessibilityIdentifier("account.current_password")
            SecureField(l10n("account.new_password"), text: $fresh)
                .textContentType(.newPassword)
                .accessibilityIdentifier("account.new_password")
            SecureField(l10n("own_settings.confirm_password"), text: $again)
                .textContentType(.newPassword)
                .accessibilityIdentifier("account.confirm_password")
            if let problem {
                Text(l10n(problem == .short ? "own_settings.password_short" : "own_settings.password_mismatch"))
                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
            }
            Button(l10n("account.change_password")) { Task { await changePassword() } }
                .disabled(busy || !OwnSettingsRules.canChangePassword(current: current, new: fresh, again: again))
                .accessibilityIdentifier("account.change_password")
        } header: {
            Text(l10n("account.password"))
        } footer: {
            Text(l10n("own_settings.password_hint"))
        }
    }

    private func changePassword() async {
        let change = PasswordChange(currentPassword: current, newPassword: fresh)
        busy = true
        defer { busy = false }
        do {
            try await app.api.call { try await AuthAPI.authChangePassword(passwordChange: change, apiConfiguration: $0) }
            // Emptied the moment the hub has them: nothing on the page keeps a password.
            current = ""
            fresh = ""
            again = ""
            note = (l10n("own_settings.password_changed"), .success)
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
    }
}

extension PhonePage {
    static let account = PhonePage(.account) { _ in AccountPage() }
}
