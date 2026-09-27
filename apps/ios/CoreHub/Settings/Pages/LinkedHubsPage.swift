// Settings → Linked hubs (ADR 0026), drawn by the phone: other Core Hubs linked to this one. What a
// link allows is said on the page, because it is the whole point: each side sees the names of the
// agents the other shares, and a person here may ask one of them a single question, answered by its
// model without tools, files or memory. Linking takes both owners: one makes an invite (single use,
// 10 minutes), the other pastes it, the first approves after comparing the key fingerprints.
// The web's LinkedHubsScreen is the twin; every call is the contract's `devices.*Peer*`.
import CoreHubClient
import SwiftUI
import UIKit

/// The page's rules, apart from the views so they are unit-tested.
enum LinkedHubsRules {
    /// A hub's refusal in words: the linked hub's own code first, then this hub's reason, then the
    /// hub's sentence.
    static func describe(_ failure: HubFailure, _ l10n: L10n) -> String {
        for reason in [failure.peerCode, failure.reason].compactMap({ $0 }) {
            let key = "linked_hubs.reason.\(reason)"
            if l10n.has(key) { return l10n(key) }
        }
        if failure.status == 429 { return l10n("linked_hubs.reason.rate_limited") }
        return failure.describe(l10n)
    }

    /// Questions per hour as typed: a whole number from 1 to 1,000, or nil.
    static func limit(_ text: String) -> Int? {
        guard let value = Int(text.trimmingCharacters(in: .whitespaces)), (1...1000).contains(value) else { return nil }
        return value
    }

    static func statusKind(_ status: Peer.Status) -> StatusPill.Kind {
        switch status {
        case .linked: return .good
        case .pending: return .warn
        case .waiting: return .neutral
        }
    }

    /// Their agents can be asked only over a link that is on and approved on this side.
    static func usable(_ peer: Peer) -> Bool { peer.enabled && peer.status != .pending }

    /// What the delete button says: a hub still waiting for approval is refused, not unlinked.
    static func removeKey(_ peer: Peer) -> String { peer.status == .pending ? "linked_hubs.refuse" : "linked_hubs.unlink" }
}

struct LinkedHubsPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var generation = 0

    struct Loaded {
        var peers: [Peer]
        var shares: [PeerShare]
    }

    var body: some View {
        AsyncContent(key: "peers/\(generation)") {
            async let peers = app.api.call { try await DevicesAPI.devicesListPeers(apiConfiguration: $0) }
            async let shares = app.api.call { try await DevicesAPI.devicesListPeerShares(apiConfiguration: $0) }
            return try await Loaded(peers: peers.items, shares: shares.items)
        } content: { loaded, reload in
            List {
                Section {
                    Text(l10n("linked_hubs.intro"))
                        .font(.system(size: FontSize.sizeSm))
                        .foregroundStyle(Tone.textMuted)
                        .accessibilityIdentifier("linked_hubs.intro")
                }
                PeerInviteSection()
                PeerRedeemSection(sent: reload)
                Section {
                    if loaded.peers.isEmpty {
                        EmptyStateView(icon: .link, title: l10n("linked_hubs.none"))
                            .listRowBackground(Color.clear)
                            .accessibilityIdentifier("linked_hubs.none")
                    }
                    ForEach(loaded.peers, id: \.id) { peer in
                        NavigationLink {
                            PeerDetailPage(peer: peer, changed: reload)
                        } label: {
                            PeerRow(peer: peer)
                        }
                        .accessibilityIdentifier("peer.\(peer.id)")
                    }
                } header: {
                    Text(l10n("linked_hubs.peers_heading"))
                }
                PeerSharesSection(shares: loaded.shares)
            }
            .refreshable { reload() }
        }
        .accessibilityIdentifier("linked_hubs.page")
    }
}

private struct PeerRow: View {
    let peer: Peer
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: Space.s2) {
                Text(peer.name).font(.system(size: FontSize.sizeSm, weight: .medium)).contentDirection(of: peer.name, fill: false)
                StatusPill(text: l10n("linked_hubs.status.\(peer.status.rawValue)"), kind: LinkedHubsRules.statusKind(peer.status))
                if !peer.enabled { StatusPill(text: l10n("linked_hubs.off")) }
            }
            Text(peer.url)
                .font(.system(size: FontSize.sizeXs, design: .monospaced))
                .foregroundStyle(Tone.textMuted)
                .lineLimit(1)
                .truncationMode(.middle)
                .environment(\.layoutDirection, .leftToRight)
        }
    }
}

/// A fingerprint to compare by eye: monospaced, left to right, selectable.
private struct FingerprintRow: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            Text(value)
                .font(.system(size: FontSize.sizeXs, design: .monospaced))
                .textSelection(.enabled)
                .environment(\.layoutDirection, .leftToRight)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("fingerprint")
        }
    }
}

private struct PeerInviteSection: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var invite: PeerInvite?
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        Section {
            Button {
                Task { await make() }
            } label: {
                LucideLabel(l10n(invite == nil ? "linked_hubs.invite_create" : "linked_hubs.invite_again"), icon: .link)
            }
            .disabled(busy)
            .accessibilityIdentifier("peer.invite.create")
            if let error { NoticeView(text: error, tone: .danger) }
            if let invite {
                HStack(alignment: .top, spacing: Space.s2) {
                    Text(invite.url)
                        .font(.system(size: FontSize.sizeXs, design: .monospaced))
                        .textSelection(.enabled)
                        .environment(\.layoutDirection, .leftToRight)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("peer.invite.url")
                    Button {
                        UIPasteboard.general.string = invite.url
                    } label: {
                        LucideIcon(.copy, size: 16).tapTarget()
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(l10n("linked_hubs.copy"))
                    ShareLink(item: invite.url) { LucideIcon(.share, size: 16).tapTarget() }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(l10n("linked_hubs.share"))
                }
                Text(l10n("linked_hubs.invite_expires", ["time": invite.expiresAt.shortText(app.language)]))
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
                FingerprintRow(label: l10n("linked_hubs.own_fingerprint"), value: invite.fingerprint)
            }
        } header: {
            Text(l10n("linked_hubs.invite_title"))
        } footer: {
            Text(l10n("linked_hubs.invite_hint"))
        }
    }

    private func make() async {
        busy = true
        defer { busy = false }
        do {
            invite = try await app.api.call { try await DevicesAPI.devicesCreatePeerInvite(apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = LinkedHubsRules.describe(HubFailure(error), l10n)
        }
    }
}

private struct PeerRedeemSection: View {
    let sent: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var url = ""
    @State private var name = ""
    @State private var busy = false
    @State private var error: String?
    @State private var done = false

    var body: some View {
        Section {
            TextField(l10n("linked_hubs.redeem_url"), text: $url, prompt: Text(verbatim: "https://…/peer-invite/…"))
                .monoField()
                .keyboardType(.URL)
                .environment(\.layoutDirection, .leftToRight)
                .accessibilityIdentifier("peer.redeem.url")
            TextField(l10n("linked_hubs.redeem_name"), text: $name)
                .accessibilityIdentifier("peer.redeem.name")
            Button(l10n("linked_hubs.redeem_submit")) { Task { await redeem() } }
                .disabled(busy || url.trimmingCharacters(in: .whitespaces).isEmpty)
                .accessibilityIdentifier("peer.redeem.submit")
            if let error { NoticeView(text: error, tone: .danger) }
            if done { NoticeView(text: l10n("linked_hubs.redeem_sent"), tone: .success) }
        } header: {
            Text(l10n("linked_hubs.redeem_title"))
        } footer: {
            Text(l10n("linked_hubs.redeem_hint"))
        }
    }

    private func redeem() async {
        busy = true
        defer { busy = false }
        let link = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = name.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            _ = try await app.api.call {
                try await DevicesAPI.devicesRequestPeer(
                    devicesRequestPeerRequest: DevicesRequestPeerRequest(url: link, name: label.isEmpty ? nil : label),
                    apiConfiguration: $0
                )
            }
            url = ""
            name = ""
            error = nil
            done = true
            sent()
        } catch {
            done = false
            self.error = LinkedHubsRules.describe(HubFailure(error), l10n)
        }
    }
}

/// Which of this hub's agents linked hubs may ask: none until switched on.
private struct PeerSharesSection: View {
    @State var shares: [PeerShare]
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        Section {
            if let error { NoticeView(text: error, tone: .danger) }
            ForEach(shares.indices, id: \.self) { index in
                let share = shares[index]
                Toggle(isOn: Binding(get: { shares[index].shared }, set: { next in Task { await set(index, next) } })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(share.name).font(.system(size: FontSize.sizeSm)).contentDirection(of: share.name, fill: false)
                        Text(app.profileName(share.profile)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                }
                .disabled(busy)
                .accessibilityLabel(l10n("linked_hubs.share_label", ["name": share.name, "profile": app.profileName(share.profile)]))
                .accessibilityIdentifier("peer.share.\(share.profile).\(share.agentId)")
            }
        } header: {
            Text(l10n("linked_hubs.shares_title"))
        } footer: {
            Text(l10n("linked_hubs.shares_hint"))
        }
    }

    private func set(_ index: Int, _ shared: Bool) async {
        guard shares.indices.contains(index) else { return }
        let share = shares[index]
        busy = true
        defer { busy = false }
        do {
            let saved = try await app.api.call {
                try await DevicesAPI.devicesSetPeerShare(
                    peerShareWrite: PeerShareWrite(profile: share.profile, agentId: share.agentId, shared: shared),
                    apiConfiguration: $0
                )
            }
            if shares.indices.contains(index) { shares[index].shared = saved.shared }
            error = nil
        } catch {
            self.error = LinkedHubsRules.describe(HubFailure(error), l10n)
        }
    }
}

/// One linked hub: approve, switch off, limit, rename, its agents, its log, unlink.
struct PeerDetailPage: View {
    @State var peer: Peer
    let changed: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var limit = ""
    @State private var busy = false
    @State private var error: String?
    @State private var renaming = false
    @State private var newName = ""
    @State private var removing = false

    var body: some View {
        Form {
            Section {
                HStack(spacing: Space.s2) {
                    StatusPill(text: l10n("linked_hubs.status.\(peer.status.rawValue)"), kind: LinkedHubsRules.statusKind(peer.status))
                        .accessibilityIdentifier("peer.status")
                    if !peer.enabled { StatusPill(text: l10n("linked_hubs.off")) }
                }
                Text(peer.url)
                    .font(.system(size: FontSize.sizeXs, design: .monospaced))
                    .textSelection(.enabled)
                    .environment(\.layoutDirection, .leftToRight)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(l10n("linked_hubs.direction.\(peer.direction.rawValue)") + (peer.status == .pending ? " " + l10n("linked_hubs.pending_hint") : ""))
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
                FingerprintRow(label: l10n("linked_hubs.their_fingerprint"), value: peer.fingerprint)
                if peer.status == .pending {
                    Button(l10n("linked_hubs.approve")) { Task { await update(PeerPatch(approve: true)) } }
                        .disabled(busy)
                        .accessibilityIdentifier("peer.approve")
                }
            }
            if let error { Section { NoticeView(text: error, tone: .danger) } }
            Section {
                Toggle(l10n("linked_hubs.enabled"), isOn: Binding(get: { peer.enabled }, set: { next in Task { await update(PeerPatch(enabled: next)) } }))
                    .disabled(busy)
                    .accessibilityIdentifier("peer.enabled")
                HStack {
                    Text(l10n("linked_hubs.asks_per_hour"))
                    Spacer()
                    TextField(String(peer.asksPerHour), text: $limit)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 90)
                        .onSubmit { Task { await saveLimit() } }
                        .accessibilityIdentifier("peer.limit")
                }
                if limit != String(peer.asksPerHour) && !limit.isEmpty {
                    Button(l10n("common.save")) { Task { await saveLimit() } }
                        .disabled(busy || LinkedHubsRules.limit(limit) == nil)
                        .accessibilityIdentifier("peer.limit.save")
                }
                Button(l10n("linked_hubs.rename")) {
                    newName = peer.name
                    renaming = true
                }
                .accessibilityIdentifier("peer.rename")
            }
            Section {
                NavigationLink {
                    PeerAgentsPage(peer: peer)
                } label: {
                    LucideLabel(l10n("linked_hubs.their_agents"), icon: .bot)
                }
                .disabled(!LinkedHubsRules.usable(peer))
                .accessibilityIdentifier("peer.agents")
                NavigationLink {
                    PeerLogPage(peer: peer)
                } label: {
                    LucideLabel(l10n("linked_hubs.log"), icon: .scrollText)
                }
                .accessibilityIdentifier("peer.log")
            }
            Section {
                Button(l10n(LinkedHubsRules.removeKey(peer)), role: .destructive) { removing = true }
                    .accessibilityIdentifier("peer.delete")
            }
        }
        .navigationTitle(peer.name)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { limit = String(peer.asksPerHour) }
        .alert(l10n("linked_hubs.rename_title"), isPresented: $renaming) {
            TextField(l10n("linked_hubs.rename_label"), text: $newName)
            Button(l10n("common.cancel"), role: .cancel) {}
            Button(l10n("common.save")) {
                let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { Task { await update(PeerPatch(name: trimmed)) } }
            }
        }
        .alert(l10n("linked_hubs.unlink_title", ["name": peer.name]), isPresented: $removing) {
            Button(l10n("common.cancel"), role: .cancel) {}
            Button(l10n(LinkedHubsRules.removeKey(peer)), role: .destructive) { Task { await remove() } }
                .accessibilityIdentifier("dialog.confirm")
        } message: {
            Text(l10n("linked_hubs.unlink_body"))
        }
    }

    private func saveLimit() async {
        guard let value = LinkedHubsRules.limit(limit) else {
            limit = String(peer.asksPerHour)
            return
        }
        guard value != peer.asksPerHour else { return }
        await update(PeerPatch(asksPerHour: value))
    }

    private func update(_ patch: PeerPatch) async {
        busy = true
        defer { busy = false }
        let id = peer.id
        do {
            peer = try await app.api.call { try await DevicesAPI.devicesUpdatePeer(peerId: id, peerPatch: patch, apiConfiguration: $0) }
            limit = String(peer.asksPerHour)
            error = nil
            changed()
        } catch {
            self.error = LinkedHubsRules.describe(HubFailure(error), l10n)
        }
    }

    private func remove() async {
        busy = true
        defer { busy = false }
        let id = peer.id
        do {
            try await app.api.call { try await DevicesAPI.devicesDeletePeer(peerId: id, apiConfiguration: $0) }
            changed()
            dismiss()
        } catch {
            self.error = LinkedHubsRules.describe(HubFailure(error), l10n)
        }
    }
}

/// The agents a linked hub shares, and one question to one of them.
struct PeerAgentsPage: View {
    let peer: Peer
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var chosen: String?
    @State private var question = ""
    @State private var answer: String?
    @State private var error: String?
    @State private var asking = false

    var body: some View {
        AsyncContent(key: "peer-agents/\(peer.id)") {
            let id = peer.id
            do {
                return try await app.api.call { try await DevicesAPI.devicesListPeerAgents(peerId: id, apiConfiguration: $0) }.items
            } catch {
                // Already in words (a linked hub's own refusal), so the page shows it as is.
                throw HubFailure(kind: .http, status: 400, code: nil, message: LinkedHubsRules.describe(HubFailure(error), l10n), operationID: nil, requestID: nil, detail: "")
            }
        } content: { agents, _ in
            Form {
                if agents.isEmpty {
                    Section {
                        Text(l10n("linked_hubs.their_agents_none")).foregroundStyle(Tone.textMuted)
                            .accessibilityIdentifier("peer.agents.none")
                    }
                } else {
                    Section {
                        ForEach(agents, id: \.id) { agent in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(agent.name).font(.system(size: FontSize.sizeSm, weight: .medium)).contentDirection(of: agent.name)
                                if let about = agent.description, !about.isEmpty {
                                    Text(about).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).contentDirection(of: about)
                                }
                            }
                            .accessibilityIdentifier("peer.agent.\(agent.id)")
                        }
                    }
                    Section {
                        Picker(l10n("linked_hubs.ask_agent"), selection: Binding(get: { chosen ?? agents.first?.id ?? "" }, set: { chosen = $0 })) {
                            ForEach(agents, id: \.id) { agent in Text(agent.name).tag(agent.id) }
                        }
                        .accessibilityIdentifier("peer.ask.agent")
                        TextField(l10n("linked_hubs.ask_question"), text: $question, axis: .vertical)
                            .lineLimit(3...8)
                            .accessibilityIdentifier("peer.ask.question")
                        Button(l10n("linked_hubs.ask_send")) {
                            Task { await ask(chosen ?? agents.first?.id) }
                        }
                        .disabled(asking || question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("peer.ask.send")
                        if asking { ProgressView() }
                        if let error { NoticeView(text: error, tone: .danger) }
                        if let answer {
                            Text(answer)
                                .font(.system(size: FontSize.sizeSm))
                                .textSelection(.enabled)
                                .contentDirection(of: answer)
                                .padding(Space.s3)
                                .background(Tone.surface2, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                                .accessibilityIdentifier("peer.ask.answer")
                        }
                    } footer: {
                        Text(l10n("linked_hubs.ask_hint"))
                    }
                }
            }
        }
        .navigationTitle(l10n("linked_hubs.their_agents"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func ask(_ shareID: String?) async {
        guard let shareID else { return }
        let prompt = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty else { return }
        asking = true
        defer { asking = false }
        let id = peer.id
        do {
            answer = try await app.api.call {
                try await DevicesAPI.devicesAskPeerAgent(peerId: id, shareId: shareID, peerAsk: PeerAsk(prompt: prompt), apiConfiguration: $0)
            }.answer
            error = nil
        } catch {
            answer = nil
            self.error = LinkedHubsRules.describe(HubFailure(error), l10n)
        }
    }
}

/// A linked hub's log: joined, approved, questions in and out, refused calls — never the words.
struct PeerLogPage: View {
    let peer: Peer
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: "peer-log/\(peer.id)") {
            let id = peer.id
            return try await app.api.call { try await DevicesAPI.devicesListPeerEvents(peerId: id, apiConfiguration: $0) }.items
        } content: { events, reload in
            List(events, id: \.id) { event in
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: Space.s2) {
                        let words = l10n("linked_hubs.event.\(event.kind.rawValue)")
                        StatusDot(kind: event.ok ? .good : .bad, label: words)
                        Text(words).font(.system(size: FontSize.sizeSm))
                    }
                    if let detail = event.detail, !detail.isEmpty {
                        Text(detail).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).contentDirection(of: detail)
                    }
                    Text(event.createdAt.shortText(app.language)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint)
                }
                .accessibilityIdentifier("peer.log.\(event.id)")
            }
            .overlay {
                if events.isEmpty { EmptyStateView(icon: .scrollText, title: l10n("linked_hubs.log_empty")) }
            }
            .refreshable { reload() }
        }
        .navigationTitle(l10n("linked_hubs.log"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

extension PhonePage {
    static let linkedHubs = PhonePage(.linkedHubs) { _ in LinkedHubsPage() }
}
