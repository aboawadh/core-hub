// An agent's Memory: the items, edited in place.
import CoreHubClient
import SwiftUI

struct AgentMemoryPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call { try await AgentsAPI.agentsListMemory(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }.items
        } content: { items, reload in
            List {
                if items.isEmpty { EmptyRow(icon: .brain) }
                ForEach(items, id: \.id) { item in
                    NavigationLink {
                        MemoryEditor(agent: agent, item: item, saved: reload)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.title)
                            if let content = item.content {
                                Text(content).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2)
                                    .contentDirection(of: content)
                            }
                        }
                    }
                }
            }
            .refreshable { reload() }
        }
    }
}

struct MemoryEditor: View {
    let agent: Agent
    let item: MemoryItem
    let saved: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: Space.s2) {
            if let error { NoticeView(text: error, tone: .danger) }
            TextEditor(text: $text)
                .font(.system(size: FontSize.sizeSm, design: .monospaced))
                .contentDirection(of: text)
                .padding(Space.s2)
                .background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.md))
        }
        .padding(Space.s3)
        .navigationTitle(item.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(l10n("common.save")) { Task { await save() } }.disabled(busy)
            }
        }
        .onAppear { text = item.content ?? "" }
    }

    private func save() async {
        busy = true
        defer { busy = false }
        let profile = app.currentProfile
        let write = MemoryItemWrite(title: item.title, content: text, tags: item.tags, revision: item.revision)
        do {
            _ = try await app.api.call {
                try await AgentsAPI.agentsPutMemoryItem(xHubProfile: profile, agentId: agent.id, itemId: item.id, memoryItemWrite: write, apiConfiguration: $0)
            }
            saved()
            dismiss()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

extension PhonePage {
    static let agentMemory = PhonePage(.agentMemory) { context in
        if let agent = context.agent { AgentMemoryPage(agent: agent) }
    }
}
