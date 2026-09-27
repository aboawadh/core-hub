// A new chat is a draft (destination `new_chat`): the agent chips above the composer, and the
// session is made on the first message, in the selector's profile, which the screen names.
import CoreHubClient
import SwiftUI

struct NewChatScreen: View {
    /// Opens the conversation the first message made.
    let opened: (_ sessionID: String, _ profile: String, _ firstMessage: OutgoingMessage) -> Void
    /// Text shared from another app, put in the composer to review before sending.
    var seed: String? = nil
    /// Pictures and files shared from another app, attached (and uploaded) as the draft opens.
    var seedFiles: [URL] = []
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var draft = ""
    @State private var tray: AttachmentTray?
    @State private var agentID: String?
    @State private var creating = false
    @State private var error: String?
    /// The composer's chips (apps batch 1): the chat's model and folder, chosen before it exists.
    @State private var controls: ChatControlsModel?
    @State private var model: String?
    @State private var folder: String?

    private var usable: [Agent] {
        app.agents.filter { $0.enabled && ($0.status == .available || $0.status == .limited) }
    }

    private var chosen: Agent? {
        usable.first { $0.id == agentID } ?? usable.first
    }

    var body: some View {
        VStack(spacing: Space.s3) {
            // The empty part of the screen above the composer: a tap there puts the keyboard away.
            VStack(spacing: Space.s3) {
                Spacer()
                BrandMark(size: 48)
                Text(l10n("chat.new_in_profile", ["profile": app.profileName(app.currentProfile)]))
                    .font(.system(size: FontSize.sizeXl, weight: .semibold))
                    .foregroundStyle(Tone.text)
                    .multilineTextAlignment(.center)
                Text(l10n("chat.start_hint"))
                    .font(.system(size: FontSize.sizeSm))
                    .foregroundStyle(Tone.textMuted)
                    .multilineTextAlignment(.center)
                Spacer()
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
            .dismissesKeyboardOnTap()
            if usable.isEmpty {
                NoticeView(text: l10n("chat.no_agent"), tone: .warning)
            } else {
                agentChips
                if draft.isEmpty {
                    // Three things worth doing (the web's starters): a tap puts one in the composer.
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: Space.s2) {
                            ForEach(Starters.suggestions(app.language), id: \.self) { text in
                                Button { draft = text } label: {
                                    Text(text).font(.system(size: FontSize.sizeXs)).lineLimit(1)
                                        .padding(.horizontal, Space.s3).padding(.vertical, Space.s2)
                                        .foregroundStyle(Tone.text)
                                        .background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                                        .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Tone.border))
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("chat.starter")
                            }
                        }
                    }
                }
            }
            if let error {
                NoticeView(text: error, tone: .danger)
            }
            if let controls, let agent = chosen {
                if let failure = controls.error {
                    NoticeView(text: failure, tone: .danger).onTapGesture { controls.error = nil }
                }
                ComposerChips(
                    controls: controls,
                    profile: app.currentProfile,
                    agentID: agent.id,
                    model: model,
                    onModel: { model = $0 },
                    allowDefault: true,
                    folder: $folder
                )
                .onAppear { controls.load(profile: app.currentProfile, agentID: agent.id, folders: true) }
                .onChange(of: agent.id) { _, id in controls.load(profile: app.currentProfile, agentID: id, folders: true) }
                .onChange(of: app.currentProfile) { _, profile in
                    model = nil
                    folder = nil
                    controls.load(profile: profile, agentID: agent.id, folders: true)
                }
            }
            Composer(
                text: $draft,
                placeholder: l10n("chat.placeholder", ["agent": chosen?.name ?? l10n("chat.agent")]),
                busy: false,
                sending: creating || chosen == nil,
                onSend: start,
                onStop: {},
                attachments: tray,
                profile: app.currentProfile
            )
        }
        .padding(.horizontal, Space.s3)
        .padding(.bottom, Space.s2)
        .background(Tone.bg)
        .onAppear {
            if tray == nil { tray = AttachmentTray(app: app) }
            if controls == nil { controls = ChatControlsModel(app: app) }
            if let seed, draft.isEmpty { draft = seed }
            // A profile file the Files page made an attachment of: in the tray, ready.
            if let tray { app.handOff.take(app.currentProfile).forEach { tray.addReady($0) } }
            if !seedFiles.isEmpty, let tray, tray.items.isEmpty {
                for file in seedFiles {
                    tray.addFile(file, profile: app.currentProfile)
                    // Read into the tray: the shared copy in the App Group is not needed any more.
                    try? FileManager.default.removeItem(at: file)
                }
            }
        }
        .navigationTitle(l10n("nav.new_chat"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("screen.new_chat")
    }

    private var agentChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Space.s2) {
                ForEach(usable, id: \.id) { agent in
                    let selected = agent.id == chosen?.id
                    Button {
                        agentID = agent.id
                    } label: {
                        HStack(spacing: Space.s1) {
                            AgentAvatar(identity: .of(agent), profile: agent.profile, size: 20)
                            Text(agent.name)
                        }
                            .font(.system(size: FontSize.sizeSm, weight: selected ? .semibold : .regular))
                            .padding(.horizontal, Space.s3)
                            .frame(height: Control.heightMd)
                            .foregroundStyle(selected ? Tone.accentSoftText : Tone.text)
                            .background(selected ? Tone.accentSoft : Tone.surface, in: Capsule())
                            .overlay(Capsule().strokeBorder(selected ? Tone.accent : Tone.border))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
    }

    private func start() {
        guard let agent = chosen else { return }
        let message = tray?.message(draft) ?? OutgoingMessage(text: draft)
        let profile = app.currentProfile
        let key = ULID.make()
        let create = SessionCreate(agentId: agent.id, model: model, workingDir: folder)
        creating = true
        error = nil
        Task {
            defer { creating = false }
            do {
                let session = try await app.api.call {
                    try await SessionsAPI.sessionsCreate(
                        xHubProfile: profile,
                        sessionCreate: create,
                        idempotencyKey: key,
                        apiConfiguration: $0
                    )
                }
                draft = ""
                tray?.clear()
                opened(session.id, session.profile, message)
            } catch {
                self.error = HubFailure(error).describe(l10n)
            }
        }
    }
}

/// Three things worth doing, under an empty chat's composer (the web's `starters.ts`). Written in
/// both languages here, as the web does, because they are content a person sends — not interface
/// text.
enum Starters {
    static func suggestions(_ language: AppLanguage) -> [String] {
        switch language {
        case .ar:
            return ["اشرح لي بنية هذا المشروع وأين أبدأ", "اقرأ الملفات في مجلد العمل ولخّص ما تجده", "اكتب اختبارًا يفشل للسلوك الذي أصفه لك"]
        case .en:
            return ["Explain this project’s structure and where to start", "Read the files in the working folder and summarise them", "Write a failing test for the behaviour I describe"]
        }
    }
}
