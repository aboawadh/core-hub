// An agent's Channels: linking, unlinking and the senders waiting for approval. A platform linked by
// scanning a code (WhatsApp) cannot be scanned from this phone's own screen: the page says so and
// opens the web for another screen.
import CoreHubClient
import SwiftUI

/// Which channels link from the phone, and what the link sends.
enum ChannelLinks {
    static func onPhone(_ platform: ChannelPlatform) -> Bool { platform.login != .qr }

    static func fields(_ platform: ChannelPlatform) -> [ChannelCredentialField] {
        if platform.login == .token && platform.credentials.isEmpty {
            return [ChannelCredentialField(key: "token", kind: .secret, _required: true)]
        }
        return platform.credentials
    }

    static func missing(_ platform: ChannelPlatform, _ typed: [String: String]) -> [String] {
        fields(platform).filter { $0._required && (typed[$0.key] ?? "").trimmingCharacters(in: .whitespaces).isEmpty }.map(\.key)
    }

    static func request(_ platform: ChannelPlatform, _ typed: [String: String], allowed: String) -> ChannelTokenLink {
        let users = allowed.split(whereSeparator: { $0 == "," || $0 == " " || $0 == "\n" }).map(String.init).filter { !$0.isEmpty }
        let allowedUsers = users.isEmpty || platform.allowedUsersKey == nil ? nil : users
        if platform.login == .token {
            let token = (typed["token"] ?? typed.values.first { !$0.isEmpty } ?? "").trimmingCharacters(in: .whitespaces)
            return ChannelTokenLink(token: token, allowedUsers: allowedUsers)
        }
        let credentials = typed.filter { !$0.value.trimmingCharacters(in: .whitespaces).isEmpty }.mapValues { $0.trimmingCharacters(in: .whitespaces) }
        return ChannelTokenLink(credentials: credentials, allowedUsers: allowedUsers)
    }

    static func account(_ channel: Channel) -> String? {
        guard let link = channel.link, link.linked else { return nil }
        let parts = [link.accountName, link.accountUsername.map { "@\($0)" }, link.accountPhone].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

struct AgentChannelsLinkPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.openURL) private var openURL
    @State private var channels: [Channel]?
    @State private var pairing: PairingList?
    @State private var error: String?
    @State private var linking = false
    @State private var unlinking: Channel?

    var body: some View {
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            Section(l10n("channels.linked")) {
                let linked = (channels ?? []).filter { $0.configured || $0.link?.linked == true }
                if channels == nil { ProgressView().frame(maxWidth: .infinity) }
                if channels != nil && linked.isEmpty {
                    Text(l10n("channels.none")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                }
                ForEach(linked, id: \.platform) { channel in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(channel.label)
                            if let line = ChannelLinks.account(channel) ?? channel.error {
                                Text(line).font(.system(size: FontSize.sizeXs)).foregroundStyle(channel.error != nil ? Tone.danger : Tone.textMuted)
                            }
                        }
                        Spacer()
                        StatusDot(kind: channel.status == .online ? .good : channel.status == .error ? .bad : .neutral, label: l10n("channels.status_\(channel.status.rawValue)"))
                    }
                    .swipeActions {
                        Button(role: .destructive) { unlinking = channel } label: { Text(l10n("channels.unlink")) }
                    }
                    .contextMenu {
                        Button(role: .destructive) { unlinking = channel } label: { Text(l10n("channels.unlink")) }
                    }
                    .accessibilityIdentifier("channel.\(channel.platform)")
                }
                Button {
                    linking = true
                } label: {
                    LucideLabel(l10n("channels.link"), icon: .link, size: 16)
                }
                .accessibilityIdentifier("channels.link")
            }
            if let pairing {
                Section(l10n("channels.waiting")) {
                    if pairing.pending.isEmpty {
                        Text(l10n("channels.nobody_waiting")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                    }
                    ForEach(pairing.pending, id: \.requestId) { request in
                        VStack(alignment: .leading, spacing: Space.s1) {
                            Text(request.userName ?? request.userId)
                            Text("\(request.platform) · \(request.requestedAt.shortText(app.language))").font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            HStack {
                                Button(l10n("workflows.approve")) { Task { await act { _ = try await AgentsAPI.agentsApprovePairing(xHubProfile: $0, agentId: agent.id, platform: request.platform, requestId: request.requestId, apiConfiguration: $1) } } }
                                    .buttonStyle(.borderedProminent).tint(Tone.accent)
                                Button(l10n("workflows.deny"), role: .destructive) { Task { await act { try await AgentsAPI.agentsDenyPairing(xHubProfile: $0, agentId: agent.id, platform: request.platform, requestId: request.requestId, apiConfiguration: $1) } } }
                                    .buttonStyle(.bordered)
                            }
                        }
                        .accessibilityIdentifier("pairing.\(request.requestId)")
                    }
                }
                if !pairing.approved.isEmpty {
                    Section(l10n("channels.approved")) {
                        ForEach(pairing.approved, id: \.userId) { sender in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(sender.userName ?? sender.userId)
                                    Text(sender.platform).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                                }
                                Spacer()
                                Button(l10n("channels.revoke")) { Task { await act { try await AgentsAPI.agentsRevokePairing(xHubProfile: $0, agentId: agent.id, platform: sender.platform, userId: sender.userId, apiConfiguration: $1) } } }
                                    .buttonStyle(ChipButtonStyle(quiet: true))
                            }
                        }
                    }
                }
            }
        }
        .refreshable { await load() }
        .task(id: app.currentProfile) { await load() }
        .sheet(isPresented: $linking, onDismiss: { Task { await load() } }) {
            NavigationStack { ChannelLinkSheet(agent: agent, openWeb: openWeb) }
        }
        .confirmationDialog(unlinking.map { l10n("channels.unlink_confirm", ["platform": $0.label]) } ?? "", isPresented: Binding(get: { unlinking != nil }, set: { if !$0 { unlinking = nil } }), titleVisibility: .visible) {
            Button(l10n("channels.unlink"), role: .destructive) {
                if let channel = unlinking {
                    Task { await act { _ = try await AgentsAPI.agentsUnlinkChannel(xHubProfile: $0, agentId: agent.id, platform: channel.platform, apiConfiguration: $1) } }
                }
                unlinking = nil
            }
        }
    }

    private func openWeb() {
        guard let hub = app.credentials?.hubURL, let path = AppRoutes.routes[.agentChannels]?.replacingOccurrences(of: ":agentId", with: agent.id),
              let url = URL(string: hub.absoluteString + path) else { return }
        openURL(url)
    }

    private func load() async {
        let profile = app.currentProfile
        do {
            channels = try await app.api.call { try await AgentsAPI.agentsListChannels(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }.items
            pairing = try? await app.api.call { try await AgentsAPI.agentsListPairing(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func act(_ operation: @escaping (String, CoreHubClientAPIConfiguration) async throws -> Void) async {
        let profile = app.currentProfile
        do {
            try await app.api.call { try await operation(profile, $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        await load()
    }
}

struct ChannelLinkSheet: View {
    let agent: Agent
    let openWeb: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        AsyncContent(key: agent.id) {
            let profile = app.currentProfile
            return try await app.api.call { try await AgentsAPI.agentsListChannelPlatforms(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }.items
        } content: { platforms, _ in
            List(platforms, id: \.platform) { platform in
                NavigationLink {
                    ChannelLinkForm(agent: agent, platform: platform, openWeb: openWeb, done: { dismiss() })
                } label: {
                    HStack {
                        LucideIcon(ChannelLinks.onPhone(platform) ? .link : .qrCode, size: 16).foregroundStyle(Tone.textMuted)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(platform.label)
                            if !ChannelLinks.onPhone(platform) {
                                Text(l10n("channels.qr_short")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            }
                        }
                    }
                }
                .accessibilityIdentifier("platform.\(platform.platform)")
            }
        }
        .navigationTitle(l10n("channels.link"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { dismiss() } }
        }
    }
}

struct ChannelLinkForm: View {
    let agent: Agent
    let platform: ChannelPlatform
    let openWeb: () -> Void
    let done: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.openURL) private var openURL
    @State private var typed: [String: String] = [:]
    @State private var allowed = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        Form {
            if !ChannelLinks.onPhone(platform) {
                // A code shown on this screen cannot be scanned by this phone's own camera.
                Section {
                    NoticeView(text: l10n("channels.qr_body", ["platform": platform.label]), tone: .info)
                    Button {
                        openWeb()
                    } label: {
                        LucideLabel(l10n("channels.open_web"), icon: .externalLink, size: 16)
                    }
                    .accessibilityIdentifier("platform.web")
                }
            } else {
                if let error { NoticeView(text: error, tone: .danger) }
                Section {
                    ForEach(ChannelLinks.fields(platform), id: \.key) { field in
                        let binding = Binding(get: { typed[field.key] ?? "" }, set: { typed[field.key] = $0 })
                        let label = field.key == "token" ? l10n("channels.bot_token") : field.key + (field._required ? " *" : "")
                        Group {
                            if field.kind == .secret {
                                SecureField(label, text: binding)
                            } else {
                                TextField(label, text: binding)
                                    .keyboardType(field.kind == .email ? .emailAddress : field.kind == .url ? .URL : field.kind == .number ? .numberPad : .default)
                            }
                        }
                        .font(.system(size: FontSize.sizeSm, design: .monospaced))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("link.\(field.key)")
                    }
                    if platform.allowedUsersKey != nil {
                        TextField(l10n("channels.allowed"), text: $allowed)
                            .textInputAutocapitalization(.never)
                    }
                } footer: {
                    if platform.pairs { Text(l10n("channels.pairs_note")) }
                }
                Section {
                    Button {
                        Task { await link() }
                    } label: {
                        LucideLabel(l10n("channels.link_do"), icon: .link, size: 16).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Tone.accent)
                    .disabled(busy || !ChannelLinks.missing(platform, typed).isEmpty)
                    .accessibilityIdentifier("link.submit")
                    if let docs = platform.docsUrl, let url = URL(string: docs) {
                        Button(l10n("channels.docs")) { openURL(url) }
                    }
                }
            }
        }
        .navigationTitle(platform.label)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func link() async {
        busy = true
        defer { busy = false }
        let profile = app.currentProfile, request = ChannelLinks.request(platform, typed, allowed: allowed), name = platform.platform
        do {
            _ = try await app.api.call { try await AgentsAPI.agentsLinkChannel(xHubProfile: profile, agentId: agent.id, platform: name, channelTokenLink: request, apiConfiguration: $0) }
            done()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

extension PhonePage {
    static let agentChannels = PhonePage(.agentChannels) { context in
        if let agent = context.agent { AgentChannelsLinkPage(agent: agent) }
    }
}
