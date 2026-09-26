// Settings → Account → Messaging accounts: the person's own Telegram / WhatsApp links. A link code
// is sent from that account to the agent's bot; the list is read again every few seconds until the
// new link shows (the web's MyChannelAccounts). Android's ChannelAccountsCard is the twin.
import CoreHubClient
import SwiftUI
import UIKit

struct ChannelAccountsSection: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var items: [ChannelIdentity]?
    @State private var code: ChannelLinkCode?
    @State private var countBefore: Int?
    @State private var linkedNow = false
    @State private var copied = false
    @State private var busy = false
    @State private var failure: String?
    @State private var unlinking: ChannelIdentity?

    var body: some View {
        Section {
            if let failure { NoticeView(text: failure, tone: .danger) }
            if let items {
                if items.isEmpty {
                    Text(l10n("own_settings.channels_none"))
                        .font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                        .accessibilityIdentifier("channels.empty")
                }
                ForEach(items, id: \.id) { identity in
                    row(identity)
                        .swipeActions {
                            Button(l10n("own_settings.channels_unlink"), role: .destructive) { unlinking = identity }
                        }
                        .contextMenu {
                            Button(role: .destructive) { unlinking = identity } label: {
                                LucideLabel(l10n("own_settings.channels_unlink"), icon: .trash)
                            }
                        }
                }
            } else {
                ProgressView()
            }
            if linkedNow {
                NoticeView(text: l10n("own_settings.channels_linked"), tone: .success)
                    .accessibilityIdentifier("channels.linked")
            }
            if let code {
                waiting(code)
            } else {
                Button {
                    Task { await start() }
                } label: {
                    LucideLabel(l10n("own_settings.channels_link"), icon: .link, size: 16)
                }
                .disabled(busy)
                .accessibilityIdentifier("channels.link")
            }
        } header: {
            Text(l10n("own_settings.channels_title"))
        } footer: {
            Text(l10n("own_settings.channels_subtitle"))
        }
        .task { await load() }
        // While a code waits, the list is asked again until the link shows up.
        .task(id: code?.code) {
            guard code != nil else { return }
            while !Task.isCancelled, code != nil {
                try? await Task.sleep(for: .seconds(3))
                if Task.isCancelled { return }
                await load()
            }
        }
        .alert(
            l10n("own_settings.channels_unlink_title"),
            isPresented: Binding(get: { unlinking != nil }, set: { if !$0 { unlinking = nil } })
        ) {
            Button(l10n("common.cancel"), role: .cancel) { unlinking = nil }
            Button(l10n("own_settings.channels_unlink"), role: .destructive) {
                guard let target = unlinking else { return }
                unlinking = nil
                Task { await unlink(target) }
            }
            .accessibilityIdentifier("dialog.confirm")
        } message: {
            Text(l10n("own_settings.channels_unlink_body"))
        }
    }

    private func row(_ identity: ChannelIdentity) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: Space.s2) {
                Text(l10n("own_settings.platform_\(identity.platform.rawValue)"))
                    .font(.system(size: FontSize.sizeSm, weight: .medium))
                Text(identity.senderId)
                    .font(.system(size: FontSize.sizeXs, design: .monospaced))
                    .foregroundStyle(Tone.textMuted)
                    .environment(\.layoutDirection, .leftToRight)
            }
            Text(l10n("own_settings.channels_linked_at", ["time": identity.linkedAt.shortText(app.language)]))
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint)
            if let used = identity.lastUsedAt {
                Text(l10n("own_settings.channels_last_used", ["time": used.shortText(app.language)]))
                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint)
            }
        }
        .accessibilityIdentifier("channels.\(identity.id)")
    }

    @ViewBuilder
    private func waiting(_ code: ChannelLinkCode) -> some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text(l10n("own_settings.channels_send_this")).font(.system(size: FontSize.sizeSm))
            HStack(spacing: Space.s2) {
                Text(code.command)
                    .font(.system(size: FontSize.sizeSm, design: .monospaced))
                    .textSelection(.enabled)
                    .environment(\.layoutDirection, .leftToRight)
                    .accessibilityIdentifier("channels.command")
                Spacer(minLength: 0)
                Button {
                    UIPasteboard.general.string = code.command
                    copied = true
                } label: {
                    LucideLabel(l10n(copied ? "own_settings.channels_copied" : "own_settings.channels_copy"), icon: .copy, size: 14)
                }
                .buttonStyle(.bordered)
            }
            Text(l10n("own_settings.channels_expires", ["time": code.expiresAt.shortText(app.language)]))
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            Text(l10n("own_settings.channels_must_answer"))
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            HStack(spacing: Space.s2) {
                ProgressView()
                Text(l10n("own_settings.channels_waiting")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
            Button(l10n("common.cancel")) { self.code = nil }
                .accessibilityIdentifier("channels.cancel")
        }
    }

    private func load() async {
        do {
            let list = try await app.api.call { try await AuthAPI.authListMyChannelIdentities(apiConfiguration: $0) }.items
            if OwnSettingsRules.linked(waiting: code != nil, before: countBefore, now: list.count) {
                code = nil
                linkedNow = true
            }
            items = list
            failure = nil
        } catch is CancellationError {
            return
        } catch {
            failure = HubFailure(error).describe(l10n)
            if items == nil { items = [] }
        }
    }

    private func start() async {
        busy = true
        defer { busy = false }
        do {
            let issued = try await app.api.call { try await AuthAPI.authCreateChannelLinkCode(apiConfiguration: $0) }
            countBefore = items?.count ?? 0
            linkedNow = false
            copied = false
            failure = nil
            code = issued
        } catch {
            failure = HubFailure(error).describe(l10n)
        }
    }

    private func unlink(_ identity: ChannelIdentity) async {
        do {
            try await app.api.call { try await AuthAPI.authDeleteMyChannelIdentity(identityId: identity.id, apiConfiguration: $0) }
            items?.removeAll { $0.id == identity.id }
            failure = nil
        } catch {
            failure = HubFailure(error).describe(l10n)
        }
    }
}
