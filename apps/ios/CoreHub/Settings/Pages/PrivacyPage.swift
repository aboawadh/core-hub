// Settings → Privacy: who, besides you signing in, can act as you on this hub — the app tokens
// (paired phones among them), each revocable after a confirm — and, for an admin, what Hermes tells
// the model about people on messaging channels (the web's PrivacyTab). Android's PrivacyPage.kt is the twin.
import CoreHubClient
import SwiftUI

struct PrivacyPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var error: String?
    @State private var revoking: AppToken?

    var body: some View {
        AsyncContent(key: "tokens") {
            try await app.api.call { try await AuthAPI.authListAppTokens(apiConfiguration: $0) }.items
        } content: { tokens, reload in
            List {
                if let error { NoticeView(text: error, tone: .danger) }
                Section {
                    if tokens.isEmpty {
                        EmptyStateView(icon: .shieldCheck, title: l10n("own_settings.privacy_none"), message: l10n("own_settings.privacy_none_body"))
                            .listRowBackground(Color.clear)
                            .accessibilityIdentifier("privacy.empty")
                    }
                    ForEach(tokens, id: \.id) { token in
                        row(token)
                            .swipeActions {
                                Button(l10n("own_settings.privacy_revoke"), role: .destructive) { revoking = token }
                            }
                            .contextMenu {
                                Button(role: .destructive) { revoking = token } label: {
                                    LucideLabel(l10n("own_settings.privacy_revoke"), icon: .trash)
                                }
                            }
                    }
                } header: {
                    Text(l10n("own_settings.privacy_access"))
                } footer: {
                    Text(l10n("own_settings.privacy_access_hint"))
                }
                HermesPrivacySection()
                Section {
                    Text(l10n("own_settings.privacy_browsers_note"))
                        .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        .listRowBackground(Color.clear)
                }
            }
            .refreshable { reload() }
            .alert(
                revoking.map { l10n("own_settings.privacy_revoke_title", ["name": $0.name]) } ?? "",
                isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } })
            ) {
                Button(l10n("common.cancel"), role: .cancel) { revoking = nil }
                Button(l10n("own_settings.privacy_revoke"), role: .destructive) {
                    guard let target = revoking else { return }
                    revoking = nil
                    Task { await revoke(target, reload) }
                }
                .accessibilityIdentifier("dialog.confirm")
            } message: {
                Text(revoking.map { l10n(OwnSettingsRules.revokeBodyKey($0)) } ?? "")
            }
        }
    }

    private func row(_ token: AppToken) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: Space.s2) {
                LucideIcon(OwnSettingsRules.isDevice(token) ? .smartphone : .keyRound, size: 14).foregroundStyle(Tone.textMuted)
                Text(token.name).font(.system(size: FontSize.sizeSm, weight: .medium)).contentDirection(of: token.name)
            }
            Text(l10n(OwnSettingsRules.isDevice(token) ? "own_settings.privacy_kind_device" : "own_settings.privacy_kind_app")
                + " · " + token.scopes.map(\.rawValue).joined(separator: ", "))
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            Text(l10n("own_settings.privacy_used", ["time": token.lastUsedAt?.shortText(app.language) ?? l10n("own_settings.privacy_never")])
                + " · "
                + l10n("own_settings.privacy_expires", ["time": token.expiresAt?.shortText(app.language) ?? l10n("own_settings.privacy_never")]))
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint)
        }
        .accessibilityIdentifier("privacy.token.\(token.id)")
    }

    private func revoke(_ token: AppToken, _ reload: @escaping () -> Void) async {
        do {
            try await app.api.call { try await AuthAPI.authRevokeAppToken(tokenId: token.id, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        reload()
    }
}

/// Hermes's `privacy.redact_pii` in the selected profile (contract decision §58): on WhatsApp,
/// Telegram, Signal and BlueBubbles Hermes hashes the ids and leaves phone numbers out of what the
/// model is told. Shown only where the profile has Hermes and the hub reads its settings; an admin
/// changes it.
private struct HermesPrivacySection: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var field: SettingsField?
    @State private var note: String?
    @State private var error: String?
    @State private var busy = false

    private var hermes: Agent? { app.agents.first { $0.kind == .hermes } }

    var body: some View {
        Group {
            if let field {
                Section {
                    Toggle(isOn: Binding(get: { SettingValues.on(field) }, set: { on in Task { await set(on) } })) {
                        Text(app.language == .ar ? field.label.ar : field.label.en)
                    }
                    .disabled(!app.isAdmin || busy)
                    .accessibilityIdentifier("privacy.redact")
                    if let error { NoticeView(text: error, tone: .danger) }
                } header: {
                    Text(l10n("own_settings.privacy_redact_title"))
                } footer: {
                    VStack(alignment: .leading, spacing: Space.s1) {
                        if let note { Text(note) }
                        if !app.isAdmin { Text(l10n("own_settings.privacy_redact_admin_only")) }
                    }
                }
            }
        }
        .task(id: "\(app.currentProfile)/\(hermes?.id ?? "")") { await load() }
    }

    private func load() async {
        guard let hermes else {
            field = nil
            return
        }
        let profile = app.currentProfile
        guard let settings = try? await app.api.call({
            try await AgentsAPI.agentsGetSettings(xHubProfile: profile, agentId: hermes.id, apiConfiguration: $0)
        }), let section = settings.sections.first(where: { $0.key == "privacy" }) else {
            field = nil
            return
        }
        field = section.fields.first { $0.key == "redact_pii" }
        note = section.note.map { app.language == .ar ? $0.ar : $0.en }
    }

    private func set(_ on: Bool) async {
        guard let hermes else { return }
        let profile = app.currentProfile
        busy = true
        defer { busy = false }
        do {
            _ = try await app.api.call {
                try await AgentsAPI.agentsUpdateSettings(
                    xHubProfile: profile, agentId: hermes.id,
                    agentSettingsPatch: AgentSettingsPatch(section: "privacy", values: ["redact_pii": .bool(on)]),
                    apiConfiguration: $0
                )
            }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        await load()
    }
}

extension PhonePage {
    static let privacy = PhonePage(.privacy) { _ in PrivacyPage() }
}
