// A coding agent's own config files (§78) in a plain editor (the editing field is DocumentEditor).
import CoreHubClient
import SwiftUI

struct AgentConfigFilesPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var chosen: String?

    var body: some View {
        AsyncContent(key: agent.id) {
            let profile = app.currentProfile
            return try await app.api.call { try await AgentsAPI.agentsListConfigFiles(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }.items
        } content: { files, _ in
            if files.isEmpty {
                EmptyStateView(icon: .fileCog, title: l10n("common.empty"))
            } else {
                let key = chosen ?? files[0].key
                VStack(spacing: Space.s2) {
                    if files.count > 1 {
                        Picker(l10n("nav.config_files"), selection: Binding(get: { key }, set: { chosen = $0 })) {
                            ForEach(files, id: \.key) { file in
                                Text(app.language == .ar ? file.label.ar : file.label.en).tag(file.key)
                            }
                        }
                        .pickerStyle(.segmented)
                        .padding(.horizontal, Space.s4)
                    }
                    ConfigFileEditor(agent: agent, fileKey: key).id(key)
                }
            }
        }
    }
}

struct ConfigFileEditor: View {
    let agent: Agent
    let fileKey: String
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var file: ConfigFile?
    @State private var text = ""
    @State private var note: (text: String, tone: NoticeView.Kind)?
    @State private var changedElsewhere = false
    @State private var busy = false

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            if let file {
                Text(file.path).font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textMuted).lineLimit(2)
                NoticeView(text: l10n("config.shared"), tone: .info)
                if !file.exists { Text(l10n("config.new_file")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
                if let note { NoticeView(text: note.text, tone: note.tone) }
                if changedElsewhere {
                    Button(l10n("config.reload")) { Task { await load() } }.buttonStyle(.bordered)
                }
                DocumentEditor(text: $text, markdown: file.language == .markdown, identifier: "config.editor")
                HStack {
                    Button {
                        Task { await save(file) }
                    } label: {
                        LucideLabel(l10n("common.save"), icon: .check, size: 16)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Tone.accent)
                    .disabled(busy || text == (file.content ?? ""))
                    .accessibilityIdentifier("config.save")
                    Button(l10n("config.revert")) { text = file.content ?? "" }
                        .disabled(text == (file.content ?? ""))
                }
            } else if let note {
                NoticeView(text: note.text, tone: note.tone)
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, Space.s4)
        .padding(.bottom, Space.s3)
        .task { await load() }
    }

    private func load() async {
        let profile = app.currentProfile, key = fileKey
        do {
            let fresh = try await app.api.call { try await AgentsAPI.agentsGetConfigFile(xHubProfile: profile, agentId: agent.id, fileKey: key, apiConfiguration: $0) }
            file = fresh
            text = fresh.content ?? ""
            changedElsewhere = false
            note = nil
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
    }

    private func save(_ current: ConfigFile) async {
        busy = true
        defer { busy = false }
        let profile = app.currentProfile, write = ConfigFileWrite(content: text, revision: current.revision)
        do {
            let saved = try await app.api.call { try await AgentsAPI.agentsPutConfigFile(xHubProfile: profile, agentId: agent.id, fileKey: current.key, configFileWrite: write, apiConfiguration: $0) }
            file = saved
            text = saved.content ?? text
            note = (l10n("config.saved"), .success)
        } catch {
            let failure = HubFailure(error)
            if failure.status == 409 && failure.code == "changed" {
                changedElsewhere = true
                note = (l10n("config.changed"), .danger)
            } else {
                note = (failure.describe(l10n), .danger)
            }
        }
    }
}

extension PhonePage {
    static let agentConfigFiles = PhonePage(.agentConfigFiles) { context in
        if let agent = context.agent { AgentConfigFilesPage(agent: agent) }
    }
}
