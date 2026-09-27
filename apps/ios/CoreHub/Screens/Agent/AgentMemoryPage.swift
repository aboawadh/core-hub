// An agent's Memory, as the web's page (apps batch 8): the persona, what it keeps and what it knows
// about you. The two memories are lists of entries within a budget (decision §102): each entry is edited
// or removed (after asking) on its own, a new one is added, the whole list can be edited at once, and
// the budget is drawn as a bar. The documents are emptied by editing, never deleted. Android's
// AgentMemoryPage.kt is its twin.
import CoreHubClient
import SwiftUI

/// An entry being written: which document, and which entry (nil adds one at the end).
private struct EntryEdit: Identifiable {
    let item: MemoryItem
    let index: Int?
    var id: String { "\(item.id)#\(index ?? -1)" }
}

private struct WholeEdit: Identifiable {
    let item: MemoryItem
    var id: String { item.id }
}

struct AgentMemoryPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var whole: WholeEdit?
    @State private var entry: EntryEdit?
    @State private var question: ToolQuestion?
    @State private var failure: String?

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call { try await AgentsAPI.agentsListMemory(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }.items
        } content: { items, reload in
            List {
                Text(l10n("agents.memory.note")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    .listRowBackground(Color.clear)
                if let failure { NoticeView(text: failure, tone: .danger) }
                if items.isEmpty { EmptyRow(icon: .brain) }
                ForEach(items, id: \.id) { item in
                    Section {
                        document(item, reload)
                    } header: {
                        header(item)
                    }
                }
            }
            .refreshable { reload() }
            .sheet(item: $whole) { edit in
                TextEditorSheet(
                    title: MemoryNames.title(edit.item, l10n), initial: edit.item.content ?? "", markdown: true,
                    subtitle: MemoryRules.isList(edit.item) ? l10n("agents.memory.entries_hint") : MemoryNames.about(edit.item, l10n),
                    tag: "memory.editor", reload: { reload() }
                ) { text in
                    try await save(edit.item, text)
                    reload()
                }
            }
            .sheet(item: $entry) { edit in
                EntrySheet(item: edit.item, index: edit.index) { text in
                    let next = MemoryRules.with(MemoryRules.list(of: edit.item), at: edit.index, typed: text)
                    try await save(edit.item, MemoryRules.join(next))
                    reload()
                }
            }
            .toolQuestion($question)
        }
    }

    @ViewBuilder
    private func header(_ item: MemoryItem) -> some View {
        let list = MemoryRules.isList(item)
        let empty = list ? MemoryRules.list(of: item).isEmpty : (item.content ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        HStack(spacing: Space.s2) {
            Text(MemoryNames.title(item, l10n))
            if MemoryRules.known(item) { Text(item.title).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint).textCase(nil) }
            if item.id == "soul" { StatusPill(text: l10n("agents.memory.persona"), kind: .good).textCase(nil) }
            Spacer()
            if list {
                Button { entry = EntryEdit(item: item, index: nil) } label: { LucideIcon(.plus, size: 16) }
                    .accessibilityLabel(l10n("agents.memory.add_entry"))
                    .accessibilityIdentifier("memory.\(item.id).add")
            }
            Button(l10n(empty ? "agents.memory.write" : list ? "agents.memory.edit_all" : "agents.memory.edit")) { whole = WholeEdit(item: item) }
                .font(.system(size: FontSize.sizeSm))
                .textCase(nil)
                .accessibilityIdentifier("memory.\(item.id).edit")
        }
    }

    @ViewBuilder
    private func document(_ item: MemoryItem, _ reload: @escaping () -> Void) -> some View {
        if MemoryRules.isList(item) {
            let entries = MemoryRules.list(of: item)
            if let limit = item.charLimit, limit > 0 {
                BudgetMeter(count: item.charCount ?? MemoryRules.length(entries), limit: limit)
            }
            if entries.isEmpty {
                Text(MemoryNames.empty(item, l10n)).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
            }
            ForEach(Array(entries.enumerated()), id: \.offset) { index, text in
                Button { entry = EntryEdit(item: item, index: index) } label: {
                    Text(text).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.text)
                        .contentDirection(of: text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button(role: .destructive) { askRemove(item, index, reload) } label: { LucideLabel(l10n("agents.memory.remove"), icon: .trash) }
                }
                .contextMenu {
                    Button { entry = EntryEdit(item: item, index: index) } label: { LucideLabel(l10n("agents.memory.edit_entry"), icon: .pencil) }
                    Button(role: .destructive) { askRemove(item, index, reload) } label: { LucideLabel(l10n("agents.memory.remove"), icon: .trash) }
                }
                .accessibilityIdentifier("memory.\(item.id).entry.\(index)")
            }
        } else if let content = item.content, !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            Button { whole = WholeEdit(item: item) } label: {
                Text(content).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.text).lineLimit(12)
                    .contentDirection(of: content)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
        } else {
            Text(MemoryNames.empty(item, l10n)).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
        }
    }

    private func askRemove(_ item: MemoryItem, _ index: Int, _ reload: @escaping () -> Void) {
        let entries = MemoryRules.list(of: item)
        question = ToolQuestion(title: l10n("agents.memory.remove_title"), body: entries.indices.contains(index) ? entries[index] : nil,
                                confirm: l10n("agents.memory.remove")) {
            Task {
                do {
                    try await save(item, MemoryRules.join(MemoryRules.without(entries, at: index)))
                    failure = nil
                } catch {
                    failure = AgentToolErrors.describe(error, l10n)
                }
                reload()
            }
        }
    }

    /// The document written again, at the revision it was read (a change made elsewhere is refused).
    private func save(_ item: MemoryItem, _ content: String) async throws {
        let profile = app.currentProfile
        let write = MemoryItemWrite(title: item.title, content: content, tags: item.tags, revision: item.revision)
        _ = try await app.api.call {
            try await AgentsAPI.agentsPutMemoryItem(xHubProfile: profile, agentId: agent.id, itemId: item.id, memoryItemWrite: write, apiConfiguration: $0)
        }
    }
}

/// The three documents' names and lines, in the person's words; another item keeps its own title.
enum MemoryNames {
    static func title(_ item: MemoryItem, _ l10n: L10n) -> String {
        MemoryRules.known(item) ? l10n("agents.memory.doc_\(item.id)") : item.title
    }

    static func about(_ item: MemoryItem, _ l10n: L10n) -> String? {
        MemoryRules.known(item) ? l10n("agents.memory.about_\(item.id)") : nil
    }

    static func empty(_ item: MemoryItem, _ l10n: L10n) -> String {
        MemoryRules.known(item) ? l10n("agents.memory.empty_\(item.id)") : l10n("agents.memory.empty")
    }
}

/// One entry written or rewritten on its own, with what the list would count against its budget.
private struct EntrySheet: View {
    let item: MemoryItem
    let index: Int?
    let save: (String) async throws -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.l10n) private var l10n
    @State private var draft = ""
    @State private var started = false
    @State private var saving = false
    @State private var failure: String?

    var body: some View {
        let entries = MemoryRules.list(of: item)
        let count = MemoryRules.length(MemoryRules.with(entries, at: index, typed: draft))
        let current = item.charCount ?? MemoryRules.length(entries)
        let fits = MemoryRules.fits(count, current: current, limit: item.charLimit)
        let name = MemoryNames.title(item, l10n)
        NavigationStack {
            VStack(alignment: .leading, spacing: Space.s2) {
                if let about = MemoryNames.about(item, l10n) {
                    Text(about).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
                if let failure { NoticeView(text: failure, tone: .danger) }
                DocumentEditor(text: $draft, markdown: true, identifier: "memory.entry.text")
                    .frame(minHeight: 140)
                if let limit = item.charLimit, limit > 0 {
                    BudgetMeter(count: count, limit: limit, identifier: "memory.entry.budget")
                }
                if !fits {
                    NoticeView(text: l10n("agents.memory.too_long", ["length": "\(count)", "limit": "\(item.charLimit ?? 0)"]), tone: .danger)
                }
            }
            .padding(Space.s4)
            .navigationTitle(l10n(index == nil ? "agents.memory.add_entry_title" : "agents.memory.edit_entry_title", ["name": name]))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving {
                        ProgressView()
                    } else {
                        Button(l10n("common.save")) { Task { await submit() } }
                            .disabled(!fits || (index == nil && MemoryRules.entries(of: draft).isEmpty))
                            .accessibilityIdentifier("memory.entry.save")
                    }
                }
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            if let index, entries.indices.contains(index) { draft = entries[index] }
        }
    }

    private func submit() async {
        saving = true
        defer { saving = false }
        do {
            try await save(draft)
            dismiss()
        } catch {
            failure = DocumentRules.changedElsewhere(error) ? l10n("kit.changed_elsewhere") : AgentToolErrors.describe(error, l10n)
        }
    }
}

extension PhonePage {
    static let agentMemory = PhonePage(.agentMemory) { context in
        if let agent = context.agent { AgentMemoryPage(agent: agent) }
    }
}
