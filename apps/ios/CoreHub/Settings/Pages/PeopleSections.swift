// Settings → Users, below the people: everyone's linked messaging accounts (contract decision §79)
// and the addresses that locked themselves after failed sign-ins (Android: PeopleSections.kt).
import CoreHubClient
import SwiftUI

/// Everyone's linked Telegram and WhatsApp accounts, each removable after a question.
struct AllChannelAccountsSection: View {
    let users: [User]
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var links: [ChannelIdentity]?
    @State private var removing: ChannelIdentity?
    @State private var error: String?

    var body: some View {
        Section {
            if let error { NoticeView(text: error, tone: .danger) }
            if let links {
                if links.isEmpty {
                    Text(l10n("admin.links_none")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                        .accessibilityIdentifier("people.links.none")
                }
                ForEach(links, id: \.id) { link in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(PeopleRules.nameOf(link.userId, in: users)).font(.system(size: FontSize.sizeMd, weight: .medium))
                        // The account id reads left to right inside any language (an isolate keeps `@` in place).
                        Text("\(l10n("admin.platform_\(link.platform.rawValue)")) · \u{2066}\(link.senderId)\u{2069}")
                            .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        Text(link.lastUsedAt.map { l10n("admin.links_last_used", ["time": $0.shortText(app.language)]) } ?? l10n("admin.links_never_used"))
                            .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                    .accessibilityIdentifier("link.\(link.id)")
                    .swipeActions { Button(l10n("admin.links_remove"), role: .destructive) { removing = link } }
                    .contextMenu { Button(role: .destructive) { removing = link } label: { LucideLabel(l10n("admin.links_remove"), icon: .trash) } }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        } header: {
            Text(l10n("admin.links_title"))
        } footer: {
            Text(l10n("admin.links_subtitle"))
        }
        .task { await load() }
        .confirmationDialog(
            removing.map { l10n("admin.links_remove_title", ["name": PeopleRules.nameOf($0.userId, in: users)]) } ?? "",
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible
        ) {
            Button(l10n("admin.links_remove"), role: .destructive) {
                if let link = removing { Task { await remove(link) } }
                removing = nil
            }
        } message: {
            Text(l10n("admin.links_remove_body"))
        }
    }

    private func load() async {
        do {
            links = try await app.api.call { try await AuthAPI.authListChannelIdentities(apiConfiguration: $0) }.items
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func remove(_ link: ChannelIdentity) async {
        do {
            try await app.api.call { try await AuthAPI.authDeleteChannelIdentity(identityId: link.id, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        await load()
    }
}

/// The addresses that locked themselves. An empty list is what a healthy hub answers, drawn as such
/// rather than hidden: «nobody is locked out» is information.
struct LockoutsSection: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var lockouts: [Lockout]?
    @State private var cleared: Int?
    @State private var error: String?

    /// «14:30», Latin digits in every language.
    static func until(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }

    var body: some View {
        Section {
            if let error { NoticeView(text: error, tone: .danger) }
            if let cleared { NoticeView(text: l10n("admin.lockouts_cleared", ["count": String(cleared)]), tone: .success) }
            if let lockouts {
                if lockouts.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(l10n("admin.lockouts_none")).font(.system(size: FontSize.sizeMd, weight: .medium))
                        Text(l10n("admin.lockouts_none_body")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                    .accessibilityIdentifier("lockouts.none")
                }
                ForEach(lockouts, id: \.ip) { row in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.ip).font(.system(size: FontSize.sizeMd, design: .monospaced)).environment(\.layoutDirection, .leftToRight)
                            Text(l10n("admin.lockouts_row", [
                                "reason": l10n("admin.lockouts_kind_\(row.kind.rawValue)"),
                                "count": String(row.failures),
                                "time": Self.until(row.lockedUntil),
                            ]))
                            .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                        Spacer()
                        Button(l10n("admin.lockouts_unlock")) { Task { await clear(row.ip) } }
                            .buttonStyle(.borderless)
                            .font(.system(size: FontSize.sizeSm))
                    }
                    .accessibilityIdentifier("lockout.\(row.ip)")
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        } header: {
            HStack {
                Text(l10n("admin.lockouts_title"))
                Spacer()
                if !(lockouts ?? []).isEmpty {
                    Button(l10n("admin.lockouts_clear_all")) { Task { await clear(nil) } }
                        .font(.system(size: FontSize.sizeSm))
                        .textCase(nil)
                        .accessibilityIdentifier("lockouts.clear")
                }
            }
        }
        .task { await load() }
    }

    private func load() async {
        do {
            lockouts = try await app.api.call { try await AuthAPI.authListLockouts(apiConfiguration: $0) }.items
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    /// Every lockout, or the one of `ip`.
    private func clear(_ ip: String?) async {
        do {
            cleared = try await app.api.call { try await AuthAPI.authClearLockouts(ip: ip, apiConfiguration: $0) }.cleared
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        await load()
    }
}
