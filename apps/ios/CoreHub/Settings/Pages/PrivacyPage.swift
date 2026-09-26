// Settings → Privacy: the app tokens that act as this person, revocable.
import CoreHubClient
import SwiftUI

/// Privacy: what can act as you — app tokens (paired phones among them) and devices.
struct PrivacyPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var error: String?

    var body: some View {
        AsyncContent(key: "tokens") {
            try await app.api.call { try await AuthAPI.authListAppTokens(apiConfiguration: $0) }.items
        } content: { tokens, reload in
            List {
                if let error { NoticeView(text: error, tone: .danger) }
                Section(l10n("privacy.app_tokens")) {
                    if tokens.isEmpty { EmptyRow(icon: .shieldCheck) }
                    ForEach(tokens, id: \.id) { token in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(token.name).font(.system(size: FontSize.sizeSm, weight: .medium))
                            Text(token.scopes.map(\.rawValue).joined(separator: ", "))
                                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            if let used = token.lastUsedAt {
                                Text(l10n("privacy.last_used", ["time": used.shortText(app.language)]))
                                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint)
                            }
                        }
                        .swipeActions {
                            Button(l10n("privacy.revoke"), role: .destructive) { Task { await revoke(token, reload) } }
                        }
                    }
                }
            }
            .refreshable { reload() }
        }
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

extension PhonePage {
    static let privacy = PhonePage(.privacy) { _ in PrivacyPage() }
}
