// Pairing a channel by QR (WhatsApp) on the phone, as on the web's pairing dialog: first the person
// says how the number will be used — a bot, or their own «Message yourself» — nothing is picked for
// them; then one `agents.loginChannel` job in that mode draws the code, which the phone shows here,
// redrawn each time Hermes replaces it. The phone that has the WhatsApp account scans it (Linked
// devices → Link a device). Leaving before it is linked cancels the job, and the hub tells Hermes
// to forget the pairing.
import CoreHubClient
import SwiftUI

/// What the job's result says while it pairs (`qr`, `expires_at`, the account once linked), read
/// loosely from its JSON.
struct ChannelPairState: Equatable {
    var qr: String?
    var expiresAt: Date?
    var accountName: String?
    var accountPhone: String?
    var mode: String?
    var applies: String?

    init(_ result: [String: JSONValue]?) {
        func text(_ key: String) -> String? {
            if case .string(let value)? = result?[key], !value.isEmpty { return value }
            return nil
        }
        qr = text("qr")
        accountName = text("account_name")
        accountPhone = text("account_phone")
        mode = text("mode")
        applies = text("applies")
        expiresAt = text("expires_at").flatMap(ChannelPairState.date)
    }

    static func date(_ text: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: text) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: text)
    }

    /// «name · phone», or nil before the account is known.
    var account: String? {
        let parts = [accountName, accountPhone].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// The sentence once linked: whether it answers now, and in which mode (web `doneKey`).
    func doneKey(chosen: ChannelLoginRequest.Mode) -> String {
        let personal = (mode ?? chosen.rawValue) == ChannelLoginRequest.Mode.selfChat.rawValue
        if applies == "now" {
            if personal { return account == nil ? "channel_pair.done_self_now" : "channel_pair.done_as_self_now" }
            return account == nil ? "channel_pair.done_now" : "channel_pair.done_as_now"
        }
        return account == nil ? "channel_pair.done" : "channel_pair.done_as"
    }
}

struct ChannelPairForm: View {
    let agent: Agent
    let platform: ChannelPlatform
    let done: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var choice: ChannelLoginRequest.Mode?
    @State private var mode: ChannelLoginRequest.Mode?
    @State private var job: Job?
    @State private var error: String?
    @State private var follow: Task<Void, Never>?

    private var finished: Bool { job.map { AgentCardRules.terminal($0.status) } ?? false }

    var body: some View {
        Form {
            if let mode {
                pairing(mode)
            } else {
                Section {
                    Picker(l10n("agents2.ch.mode.title"), selection: $choice) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(l10n("agents2.ch.mode.bot"))
                            Text(l10n("agents2.ch.mode.bot_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                        .tag(ChannelLoginRequest.Mode?.some(.bot))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(l10n("agents2.ch.mode.self"))
                            Text(l10n("agents2.ch.mode.self_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                        .tag(ChannelLoginRequest.Mode?.some(.selfChat))
                    }
                    .pickerStyle(.inline)
                    .accessibilityIdentifier("pair.mode")
                } header: {
                    Text(l10n("agents2.ch.mode.title"))
                } footer: {
                    Text(l10n("channel_pair.note"))
                }
                Section {
                    Button {
                        if let choice { start(choice) }
                    } label: {
                        Text(l10n("channel_pair.continue")).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Tone.accent)
                    .disabled(choice == nil)
                    .accessibilityIdentifier("pair.continue")
                }
            }
        }
        .navigationTitle(l10n("channel_pair.title", ["name": platform.label]))
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { leave() }
    }

    @ViewBuilder
    private func pairing(_ mode: ChannelLoginRequest.Mode) -> some View {
        let state = ChannelPairState(job?.result)
        Section {
            if let error {
                NoticeView(text: error, tone: .danger)
            } else if !finished, let qr = state.qr, let image = QRImage.make(qr) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 260)
                    .padding(Space.s3)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                    .frame(maxWidth: .infinity)
                    .accessibilityLabel(l10n("channel_pair.qr_label"))
                    .accessibilityIdentifier("pair.qr")
            } else if !finished {
                HStack(spacing: Space.s2) {
                    ProgressView()
                    Text(l10n("channel_pair.starting")).foregroundStyle(Tone.textMuted)
                }
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("pair.starting")
            }
            if !finished, let message = job?.progress.message, !message.isEmpty {
                Text(message).font(.system(size: FontSize.sizeSm)).contentDirection(of: message)
                    .accessibilityIdentifier("pair.message")
            }
            if !finished, let expires = state.expiresAt {
                Text(l10n("channel_pair.expires", ["time": expires.formatted(Date.FormatStyle(date: .omitted, time: .shortened).locale(app.language.locale))]))
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
            }
        } footer: {
            if !finished { Text(l10n("channel_pair.scan_hint")) }
        }
        if !finished && (state.mode ?? mode.rawValue) != ChannelLoginRequest.Mode.selfChat.rawValue {
            Section { NoticeView(text: l10n("channel_pair.personal_warning"), tone: .warning) }
        }
        if let job, finished {
            Section {
                switch job.status {
                case .succeeded:
                    NoticeView(text: l10n(state.doneKey(chosen: mode), ["account": state.account ?? ""]), tone: .success)
                        .accessibilityIdentifier("pair.done")
                    Button(l10n("common.close")) { done() }
                case .cancelled:
                    NoticeView(text: l10n("channel_pair.cancelled"), tone: .info)
                    Button(l10n("channel_pair.again")) { start(mode) }
                default:
                    NoticeView(text: job.error?.error ?? l10n("channel_pair.failed"), tone: .danger)
                        .accessibilityIdentifier("pair.failed")
                    Button(l10n("channel_pair.again")) { start(mode) }
                        .accessibilityIdentifier("pair.again")
                }
            }
        } else if error != nil {
            Section { Button(l10n("channel_pair.again")) { start(mode) } }
        }
    }

    private func start(_ chosen: ChannelLoginRequest.Mode) {
        follow?.cancel()
        mode = chosen
        job = nil
        error = nil
        let profile = app.currentProfile, agentID = agent.id, name = platform.platform, api = app.api
        follow = Task {
            do {
                let accepted = try await api.call {
                    try await AgentsAPI.agentsLoginChannel(xHubProfile: profile, agentId: agentID, platform: name,
                                                           channelLoginRequest: ChannelLoginRequest(mode: chosen), apiConfiguration: $0)
                }
                // Every second: the code is replaced by Hermes now and then, and a scan ends it.
                _ = try await AgentJobs.follow(accepted.jobId, profile: profile, api: api, every: .seconds(1)) { job = $0 }
            } catch is CancellationError {
                return
            } catch {
                self.error = AgentToolErrors.describe(error, l10n)
            }
        }
    }

    /// Leaving before the phone is linked stops the pairing on the hub.
    private func leave() {
        follow?.cancel()
        follow = nil
        guard let job, !AgentCardRules.terminal(job.status) else { return }
        let profile = app.currentProfile, id = job.id, api = app.api
        Task { _ = try? await api.call { try await JobsAPI.jobsCancel(xHubProfile: profile, jobId: id, apiConfiguration: $0) } }
    }
}
