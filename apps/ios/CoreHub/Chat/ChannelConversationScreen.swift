// A conversation held on Telegram, WhatsApp… as Hermes keeps it (contract decision §61), in the
// chat's own look — the person on the channel on one side, the agent's replies on the other — but
// read-only: the hub cannot write to it, so where the composer would be there is the banner
// saying where the reply is made, and «Continue in Core Hub» (§62), which carries it into a new
// chat with the transcript attached. Read again every half minute while open; older messages a
// page at a time; the pictures the person sent drawn while Hermes keeps them (§103). The web's
// ChannelConversationView is the twin.
import CoreHubClient
import Observation
import SwiftUI

@MainActor
@Observable
final class ChannelConversationModel {
    let conversationID: String
    let profile: String
    private(set) var conversation: ChannelConversation?
    private(set) var latest: [ChannelMessage] = []
    private(set) var older: [ChannelMessage] = []
    private(set) var nextOffset: Int?
    private(set) var hasMore = false
    private(set) var loadingOlder = false
    private(set) var error: String?
    private(set) var loaded = false
    /// Pictures read so far, by attachment id.
    private(set) var pictures: [String: UIImage] = [:]
    @ObservationIgnored private weak var app: AppModel?
    @ObservationIgnored private var poller: Task<Void, Never>?
    @ObservationIgnored private var fetching: Set<String> = []
    @ObservationIgnored private var olderLoaded = false

    init(app: AppModel, conversationID: String, profile: String) {
        self.app = app
        self.conversationID = conversationID
        self.profile = profile
    }

    /// What is shown: the older pages asked for, then the latest page, each message once.
    var messages: [ChannelMessage] { ChannelTranscript.merge(older: older, latest: latest) }

    func start() {
        guard poller == nil else { return }
        poller = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    func stop() {
        poller?.cancel()
        poller = nil
    }

    func refresh() async {
        guard let app else { return }
        let profile = self.profile, id = conversationID
        do {
            let page = try await app.api.call {
                try await SessionsAPI.sessionsListChannelMessages(xHubProfile: profile, conversationId: id, apiConfiguration: $0)
            }
            conversation = page.conversation
            latest = page.items
            if !olderLoaded {
                nextOffset = page.nextOffset
                hasMore = page.hasMore
            }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(app.l10n)
        }
        loaded = true
    }

    /// The page before what is shown, from where the last one stopped (`next_offset`).
    func loadOlder() async {
        guard let app, let offset = nextOffset, !loadingOlder else { return }
        loadingOlder = true
        defer { loadingOlder = false }
        let profile = self.profile, id = conversationID
        do {
            let page = try await app.api.call {
                try await SessionsAPI.sessionsListChannelMessages(xHubProfile: profile, conversationId: id, offset: offset, apiConfiguration: $0)
            }
            older = page.items + older
            nextOffset = page.nextOffset
            hasMore = page.nextOffset != nil
            olderLoaded = true
            error = nil
        } catch {
            self.error = HubFailure(error).describe(app.l10n)
        }
    }

    func picture(_ attachment: ChannelAttachment) -> UIImage? {
        if let image = pictures[attachment.id] { return image }
        guard attachment.available, !fetching.contains(attachment.id), let app else { return nil }
        fetching.insert(attachment.id)
        let profile = self.profile, id = conversationID, pictureID = attachment.id
        Task {
            if let url = try? await app.api.call({ try await SessionsAPI.sessionsGetChannelPicture(xHubProfile: profile, conversationId: id, pictureId: pictureID, apiConfiguration: $0) }),
               let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
                pictures[pictureID] = image
            }
        }
        return nil
    }
}

/// The transcript's rules, apart from the views.
enum ChannelTranscript {
    static func merge(older: [ChannelMessage], latest: [ChannelMessage]) -> [ChannelMessage] {
        let seen = Set(latest.map(\.id))
        return older.filter { !seen.contains($0.id) } + latest
    }

    /// The agent that carries a conversation on (§62): the profile's Hermes — the one the channel
    /// talked to — or else the first agent that can answer.
    static func continuingAgent(_ agents: [Agent]) -> Agent? {
        let ready = agents.filter { $0.enabled && [.available, .updating, .limited].contains($0.status) }
        return ready.first { $0.slug == "hermes" } ?? ready.first
    }
}

struct ChannelConversationScreen: View {
    @State var model: ChannelConversationModel
    /// A new chat made from this conversation, with its first message.
    let continued: (_ sessionID: String, _ profile: String, _ first: OutgoingMessage) -> Void
    /// The conversation is gone from Hermes (deleted here).
    let gone: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var continuing = false
    @State private var deleting: ChannelConversation?
    @State private var note: String?

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { reader in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if let title = model.conversation?.title, title != peer {
                            Text(title).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted).contentDirection(of: title)
                                .padding(.bottom, Space.s2)
                        }
                        if model.hasMore {
                            if model.nextOffset != nil {
                                Button(l10n("session_groups.channels.load_older")) { Task { await model.loadOlder() } }
                                    .disabled(model.loadingOlder)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, Space.s2)
                                    .accessibilityIdentifier("channel.older")
                            } else {
                                Text(l10n("session_groups.channels.has_more")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        if let error = model.error { NoticeView(text: error, tone: .danger) }
                        if !model.loaded {
                            SkeletonList(rows: 4)
                        } else if model.messages.isEmpty {
                            EmptyStateView(icon: .radio, title: l10n("session_groups.channels.no_messages"))
                        }
                        let messages = model.messages
                        ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                            ChannelMessageRow(message: message, peer: peer, startsTurn: index == 0 || messages[index - 1].role != message.role, model: model)
                                .id(message.id)
                        }
                    }
                    .padding(.horizontal, Space.s4)
                    .padding(.vertical, Space.s3)
                }
                .onChange(of: model.latest.last?.id) { _, last in
                    if let last { reader.scrollTo(last, anchor: .bottom) }
                }
            }
            if model.conversation != nil { bottom }
        }
        .background(Tone.bg)
        .navigationTitle(model.conversation.map { _ in peer } ?? l10n("nav.chat"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let conversation = model.conversation {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            Task { await setHidden(conversation, conversation.hidden != true) }
                        } label: {
                            Label { Text(l10n(conversation.hidden == true ? "session_groups.channels.unhide" : "session_groups.channels.hide")) } icon: {
                                Image(lucide: conversation.hidden == true ? .eye : .eyeOff)
                            }
                        }
                        if app.isAdmin {
                            Button(role: .destructive) { deleting = conversation } label: {
                                Label { Text(l10n("session_groups.channels.delete")) } icon: { Image(lucide: .trash) }
                            }
                        }
                    } label: {
                        LucideIcon(.ellipsis, size: 20)
                    }
                    .accessibilityLabel(l10n("session_groups.channels.actions"))
                    .accessibilityIdentifier("channel.actions")
                }
            }
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .sheet(isPresented: $continuing) {
            NavigationStack {
                ContinueChannelSheet(model: model, channel: channelName) { sessionID, profile, first in
                    continuing = false
                    continued(sessionID, profile, first)
                }
            }
            .presentationDetents([.medium, .large])
        }
        .confirmDelete($deleting, name: { _ in peer }, delete: { conversation in
            let profile = conversation.profile, id = conversation.id
            try await app.api.call { try await SessionsAPI.sessionsDeleteChannelConversation(xHubProfile: profile, conversationId: id, apiConfiguration: $0) }
        }, deleted: { _ in gone() })
        .accessibilityIdentifier("screen.channel_conversation")
    }

    private var peer: String {
        model.conversation.map { SessionGroups.title($0, l10n) } ?? ""
    }

    private var channelName: String {
        model.conversation.map { SessionGroups.channelName($0.channel, l10n) } ?? ""
    }

    /// Where the composer would be: why there is none, where the reply is made, and going on here.
    private var bottom: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            if let note { NoticeView(text: note, tone: .success) }
            NoticeView(text: l10n("session_groups.channels.readonly_banner", ["channel": channelName]), tone: .info)
                .accessibilityIdentifier("channel.readonly")
            Button {
                continuing = true
            } label: {
                LucideLabel(l10n("session_groups.channels.continue.button"), icon: .messagesSquare, size: 16)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(Tone.accent)
            .accessibilityIdentifier("channel.continue")
        }
        .padding(Space.s3)
        .background(Tone.bg)
    }

    private func setHidden(_ conversation: ChannelConversation, _ hidden: Bool) async {
        let profile = conversation.profile, id = conversation.id
        do {
            if hidden {
                try await app.api.call { try await SessionsAPI.sessionsHideChannelConversation(xHubProfile: profile, conversationId: id, apiConfiguration: $0) }
                note = l10n("session_groups.channels.hidden_toast", ["title": peer]) + " " + l10n("session_groups.channels.hidden_toast_body")
            } else {
                try await app.api.call { try await SessionsAPI.sessionsUnhideChannelConversation(xHubProfile: profile, conversationId: id, apiConfiguration: $0) }
                note = nil
            }
        } catch {
            note = nil
        }
        await model.refresh()
    }
}

/// One message: the person on the channel as a bubble, the agent's reply as the chat draws it.
private struct ChannelMessageRow: View {
    let message: ChannelMessage
    let peer: String
    let startsTurn: Bool
    let model: ChannelConversationModel
    @Environment(\.l10n) private var l10n
    @Environment(\.chatLook) private var look

    var body: some View {
        let person = message.role == .user
        // The person on the channel where the chat puts its person (the trailing side), the agent
        // where it puts an agent; the sides do not turn with the language.
        VStack(alignment: person ? .trailing : .leading, spacing: Space.s1) {
            if startsTurn {
                Text(person ? peer : l10n("chat.agent"))
                    .font(.system(size: FontSize.sizeSm, weight: .semibold))
                    .foregroundStyle(Tone.textMuted)
            }
            VStack(alignment: .leading, spacing: Space.s2) {
                ForEach(message.attachments ?? [], id: \.id) { attachment in
                    if let image = model.picture(attachment) {
                        Image(uiImage: image).resizable().scaledToFit().frame(maxWidth: 240)
                            .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                            .accessibilityLabel(l10n("session_groups.channels.picture"))
                    } else if !attachment.available {
                        Text(l10n("session_groups.channels.picture_gone")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    } else {
                        ProgressView().frame(width: 120, height: 80)
                    }
                }
                if !message.text.isEmpty {
                    if person {
                        Text(message.text).font(.system(size: look.size(FontSize.sizeMd))).textSelection(.enabled).contentDirection(of: message.text)
                    } else {
                        MarkdownView(text: message.text)
                    }
                }
            }
            .padding(look.padding)
            .foregroundStyle(person ? Tone.userBubbleText : Tone.agentBubbleText)
            .background(person ? Tone.userBubble : Tone.agentBubble, in: BubbleShape(tightCorner: person ? .topTrailing : .topLeading))
            .overlay(BubbleShape(tightCorner: person ? .topTrailing : .topLeading).stroke(person ? Tone.userBubbleBorder : Tone.agentBubbleBorder, lineWidth: 1))
            .frame(maxWidth: .infinity, alignment: person ? .trailing : .leading)
        }
        .padding(.top, look.gap(startsTurn: startsTurn))
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityIdentifier(person ? "channel.message.user" : "channel.message.agent")
    }
}

/// «Continue in Core Hub»: a new chat in this profile with the conversation attached (§62).
struct ContinueChannelSheet: View {
    let model: ChannelConversationModel
    let channel: String
    let done: (_ sessionID: String, _ profile: String, _ first: OutgoingMessage) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var note = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        let agent = ChannelTranscript.continuingAgent(app.agentDirectory.agents(model.profile))
        Form {
            Section {
                Text(l10n("session_groups.channels.continue.hint", ["channel": channel])).font(.system(size: FontSize.sizeSm))
                TextField(l10n("session_groups.channels.continue.note"), text: $note, axis: .vertical)
                    .lineLimit(2...6)
                    .contentDirection(of: note)
                    .accessibilityIdentifier("channel.continue.note")
            }
            if agent == nil {
                Section { NoticeView(text: l10n("session_groups.channels.continue.no_agent"), tone: .warning) }
            }
            if let error { Section { NoticeView(text: error, tone: .danger) } }
        }
        .navigationTitle(l10n("session_groups.channels.continue.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(l10n("session_groups.channels.continue.go")) { Task { await go(agent) } }
                    .disabled(busy || agent == nil)
                    .accessibilityIdentifier("channel.continue.go")
            }
        }
    }

    private func go(_ agent: Agent?) async {
        guard let agent else { return }
        busy = true
        defer { busy = false }
        let profile = model.profile, id = model.conversationID
        let words = note.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let made = try await app.api.call {
                try await SessionsAPI.sessionsContinueChannelConversation(
                    xHubProfile: profile, conversationId: id,
                    channelContinueRequest: ChannelContinueRequest(agentId: agent.id, note: words.isEmpty ? nil : words),
                    apiConfiguration: $0
                )
            }
            done(made.session.id, made.session.profile, OutgoingMessage(text: "", preset: made.firstMessage))
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}
