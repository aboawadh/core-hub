// «Attach to chat» on the Files page (the web's AttachToChatDialog): a new chat, or one of this
// profile's recent chats (the file belongs to this profile, so another profile's chats are not
// offered). Choosing one makes the attachment on the hub (`knowledge.attachWorkspaceFile`) — the
// phone neither fetches nor re-uploads the bytes — and hands it to that chat's composer
// (`AttachmentHandOff`), where it waits ready. Android's AttachToChatSheet (FilesPage.kt) is the twin.
import CoreHubClient
import SwiftUI

struct FilesAttachSheet: View {
    let entry: WorkspaceFileEntry
    let ops: FilesOps
    /// The attachment made, and where to open it.
    let attached: (Attachment, MainContent) -> Void
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var chats: [Session]?
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        send(.newChat)
                    } label: {
                        Label { Text(l10n("files.attach_new")) } icon: { Image(lucide: .plus) }
                    }
                    .disabled(busy)
                    .accessibilityIdentifier("files.attach.new")
                } footer: {
                    Text(l10n("files.attach_body"))
                }
                if let error {
                    Section { NoticeView(text: error, tone: .danger) }
                }
                Section(l10n("files.attach_recent")) {
                    if let chats {
                        if chats.isEmpty {
                            Text(l10n("files.attach_none")).foregroundStyle(Tone.textMuted)
                        }
                        ForEach(chats, id: \.id) { chat in
                            Button {
                                send(.chat(sessionID: chat.id, profile: chat.profile))
                            } label: {
                                Label {
                                    Text(chat.title.flatMap { $0.isEmpty ? nil : $0 } ?? l10n("files.attach_untitled"))
                                        .lineLimit(2)
                                        .foregroundStyle(Tone.text)
                                } icon: {
                                    Image(lucide: .messagesSquare)
                                }
                            }
                            .disabled(busy)
                            .accessibilityIdentifier("files.attach.chat.\(chat.id)")
                        }
                    } else {
                        ProgressView()
                    }
                }
            }
            .navigationTitle(l10n("files.attach_title", ["a": entry.name]))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(l10n("common.cancel")) { dismiss() }
                }
                if busy {
                    ToolbarItem(placement: .confirmationAction) { ProgressView() }
                }
            }
        }
        .task {
            do { chats = try await ops.recentChats() } catch {
                chats = []
                self.error = FilesRules.say(error, l10n)
            }
        }
        .accessibilityIdentifier("files.attach")
    }

    private func send(_ target: MainContent) {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            do {
                let attachment = try await ops.attach(entry.path)
                attached(attachment, target)
            } catch {
                self.error = FilesRules.say(error, l10n)
            }
            busy = false
        }
    }
}
