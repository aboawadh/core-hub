// An agent's Channels (apps batch 9, the web's page): each linked platform as a card — its switch, state
// and account, and what it offers (its settings, WhatsApp's mode and reply header, its fields, Unlink or
// Forget identity, Restart when the gateway does not serve it yet); linking a platform (a bot token or
// credentials here; a platform linked by scanning a code (WhatsApp) cannot be scanned from this phone's
// own screen, so the page says to pair it from a computer and opens the web); the senders waiting for
// approval and the approved ones; and the agent's incoming webhooks. Android's AgentChannelsPage.kt is
// its twin.
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

    static func account(_ channel: Channel) -> String? { ChannelRules.account(channel.link) }
}

struct AgentChannelsLinkPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.openURL) private var openURL
    @State private var list: AgentsListChannels200Response?
    @State private var specs: [ChannelPlatform] = []
    @State private var pairing: PairingList?
    @State private var error: String?
    @State private var note: ToolNote?
    @State private var picking = false
    @State private var linking: Keyed<ChannelPlatform>?
    @State private var settings: Keyed<Channel>?
    @State private var mode: Keyed<Channel>?
    @State private var header: Keyed<Channel>?
    @State private var fields: Keyed<Channel>?
    @State private var restarting = false
    @State private var busy: Set<String> = []
    @State private var question: ToolQuestion?

    var body: some View {
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            if let note { NoticeView(text: note.text, tone: note.tone) }
            Section {
                let shown = ChannelRules.shown(list?.items ?? [], pending: pairing?.pending ?? [])
                if list == nil { ProgressView().frame(maxWidth: .infinity) }
                if list != nil && shown.isEmpty {
                    Text(l10n("channels.none")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                }
                if !shown.isEmpty { GatewayNote(gateway: list?.gateway) }
                ForEach(shown, id: \.platform) { channel in
                    let spec = specs.first { $0.platform == channel.platform }
                    ChannelCard(
                        channel: channel, spec: spec, waiting: ChannelRules.waitingOn(pairing?.pending ?? [], channel.platform),
                        canRestart: AgentCardRules.canRestart(agent), restarting: restarting, busy: busy.contains(channel.platform),
                        switched: { on in
                            Task {
                                busy.insert(channel.platform)
                                await act { _ = try await AgentsAPI.agentsUpdateChannel(xHubProfile: $0, agentId: agent.id, platform: channel.platform, channelWrite: ChannelWrite(enabled: on), apiConfiguration: $1) }
                                busy.remove(channel.platform)
                            }
                        },
                        restart: { Task { await restart() } },
                        choose: { action in choose(action, channel, spec) }
                    )
                }
                Button {
                    picking = true
                } label: {
                    LucideLabel(l10n("channels.link"), icon: .link, size: 16)
                }
                .accessibilityIdentifier("channels.link")
            } header: {
                Text(l10n("channels.linked"))
            }
            if let pairing {
                Section(l10n("channels.waiting")) {
                    if pairing.pending.isEmpty {
                        Text(l10n("channels.nobody_waiting")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                    }
                    ForEach(pairing.pending, id: \.requestId) { request in
                        VStack(alignment: .leading, spacing: Space.s1) {
                            Text(request.userName ?? request.userId)
                            Text("\(request.platform) · \(request.userId) · \(request.requestedAt.shortText(app.language))").font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            HStack {
                                Button(l10n("workflows.approve")) { Task { await act { _ = try await AgentsAPI.agentsApprovePairing(xHubProfile: $0, agentId: agent.id, platform: request.platform, requestId: request.requestId, apiConfiguration: $1) } } }
                                    .buttonStyle(.borderedProminent).tint(Tone.accent)
                                    .accessibilityIdentifier("pairing.\(request.requestId).approve")
                                Button(l10n("workflows.deny"), role: .destructive) { Task { await act { try await AgentsAPI.agentsDenyPairing(xHubProfile: $0, agentId: agent.id, platform: request.platform, requestId: request.requestId, apiConfiguration: $1) } } }
                                    .buttonStyle(.bordered)
                                    .accessibilityIdentifier("pairing.\(request.requestId).deny")
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
                                    Text("\(sender.platform) · \(sender.userId)").font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                                }
                                Spacer()
                                Button(l10n("channels.revoke")) { Task { await act { try await AgentsAPI.agentsRevokePairing(xHubProfile: $0, agentId: agent.id, platform: sender.platform, userId: sender.userId, apiConfiguration: $1) } } }
                                    .buttonStyle(ChipButtonStyle(quiet: true))
                            }
                        }
                    }
                }
            }
            WebhooksSection(agent: agent, channels: list?.items ?? [])
        }
        .refreshable { await load() }
        .task(id: app.currentProfile) { await load() }
        .toolQuestion($question)
        .sheet(isPresented: $picking, onDismiss: { Task { await load() } }) {
            NavigationStack { ChannelLinkSheet(agent: agent, start: nil, openWeb: openWeb) }
        }
        .sheet(item: $linking, onDismiss: { Task { await load() } }) { spec in
            NavigationStack { ChannelLinkSheet(agent: agent, start: spec.value, openWeb: openWeb) }
        }
        .sheet(item: $settings) { channel in
            ChannelSettingsSheet(agent: agent, platform: channel.id, name: specs.first { $0.platform == channel.id }?.label ?? channel.value.label, gateway: list?.gateway)
        }
        .sheet(item: $mode, onDismiss: { Task { await load() } }) { channel in
            ChannelModeSheet(agent: agent, channel: channel.value)
        }
        .sheet(item: $header, onDismiss: { Task { await load() } }) { channel in
            ReplyHeaderSheet(agent: agent, channel: channel.value)
        }
        .sheet(item: $fields, onDismiss: { Task { await load() } }) { channel in
            ChannelFieldsSheet(agent: agent, channel: channel.value)
        }
    }

    private func choose(_ action: ChannelRules.Action, _ channel: Channel, _ spec: ChannelPlatform?) {
        switch action {
        case .pair, .link: linking = spec.map { Keyed(id: $0.platform, value: $0) }
        case .settings: settings = Keyed(id: channel.platform, value: channel)
        case .mode: mode = Keyed(id: channel.platform, value: channel)
        case .replyHeader: header = Keyed(id: channel.platform, value: channel)
        case .fields: fields = Keyed(id: channel.platform, value: channel)
        case .unlink:
            let body: String
            switch ChannelRules.unlinkWords(channel) {
            case .telegram: body = l10n("agents2.ch.telegram_unlink_body")
            case .credentials: body = l10n("agents2.ch.platform_unlink_body", ["name": channel.label])
            case .whatsapp: body = l10n("agents2.ch.unlink_body")
            }
            question = ToolQuestion(title: l10n("agents2.ch.unlink_title", ["name": channel.label]), body: body, confirm: l10n("channels.unlink")) {
                Task { await act { _ = try await AgentsAPI.agentsUnlinkChannel(xHubProfile: $0, agentId: agent.id, platform: channel.platform, apiConfiguration: $1) } }
            }
        case .clear:
            question = ToolQuestion(title: l10n("agents2.ch.clear_title", ["name": channel.label]), body: l10n("agents2.ch.clear_body"), confirm: l10n("agents2.ch.clear")) {
                Task { await act { _ = try await AgentsAPI.agentsClearChannel(xHubProfile: $0, agentId: agent.id, platform: channel.platform, apiConfiguration: $1) } }
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
            list = try await app.api.call { try await AgentsAPI.agentsListChannels(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }
            pairing = try? await app.api.call { try await AgentsAPI.agentsListPairing(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }
            if specs.isEmpty {
                specs = (try? await app.api.call { try await AgentsAPI.agentsListChannelPlatforms(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }.items) ?? []
            }
            error = nil
        } catch {
            self.error = AgentToolErrors.describe(error, l10n)
        }
    }

    private func act(_ operation: @escaping (String, CoreHubClientAPIConfiguration) async throws -> Void) async {
        let profile = app.currentProfile
        do {
            try await app.api.call { try await operation(profile, $0) }
            error = nil
        } catch {
            self.error = AgentToolErrors.describe(error, l10n)
        }
        await load()
    }

    /// Restarts the agent's runtime (Hermes's gateway with it), follows the job, then reads the channels again.
    private func restart() async {
        let profile = app.currentProfile
        restarting = true
        note = nil
        defer { restarting = false }
        do {
            let accepted = try await app.api.call { try await AgentsAPI.agentsRestart(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }
            let job = try await AgentJobs.follow(accepted.jobId, profile: profile, api: app.api) { _ in }
            note = job.status == .succeeded ? ToolNote(text: l10n("agents2.ch.restarted")) : ToolNote(text: job.error?.error ?? l10n("agents2.ch.status.error"), tone: .danger)
        } catch is CancellationError {
            return
        } catch {
            note = ToolNote(text: AgentToolErrors.describe(error, l10n), tone: .danger)
        }
        await load()
    }
}

/// A value shown in a sheet, named by its platform.
struct Keyed<Value>: Identifiable {
    let id: String
    let value: Value
}

struct ChannelLinkSheet: View {
    let agent: Agent
    /// A platform to open straight at (a card's Link or Pair), or nil for the list.
    let start: ChannelPlatform?
    let openWeb: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        if let start {
            ChannelLinkForm(agent: agent, platform: start, openWeb: openWeb, done: { dismiss() })
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { dismiss() } }
                }
        } else {
            list
        }
    }

    private var list: some View {
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
                    NoticeView(text: l10n("agents2.ch.qr_note", ["platform": platform.label]), tone: .info)
                        .accessibilityIdentifier("platform.qr_note")
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
