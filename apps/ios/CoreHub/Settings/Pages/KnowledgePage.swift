// Settings → Knowledge (the web's KnowledgeTab): the profile's journal, notes and files in one list,
// newest first, with the kind as chips and a search, a page at a time. Nothing is written here: the
// rows come from conversations and agents (the contract has no create, upload or delete for them).
// Android's KnowledgePage.kt is the twin.
import CoreHubClient
import SwiftUI

struct KnowledgePage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var kind: String?
    @State private var typed = ""
    @State private var query = ""
    @State private var list: PagedList<KnowledgeRow>?

    private var key: String { "\(app.currentProfile)/\(kind ?? "all")/\(query)" }

    var body: some View {
        Group {
            if let list {
                ListScaffold(
                    list,
                    key: key,
                    query: $typed,
                    filters: KnowledgeRules.kinds.map { ListFilter(value: $0, label: label($0)) },
                    filter: $kind,
                    emptyIcon: .bookOpen,
                    emptyTitle: l10n("knowledge.empty"),
                    emptyMessage: l10n("knowledge.empty_body"),
                    tag: "knowledge.list"
                ) { row in
                    KnowledgeItemRow(item: row.item, kindLabel: label(row.item.kind.rawValue))
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            Text(l10n("knowledge.intro"))
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Space.s4).padding(.vertical, Space.s2)
                .background(Tone.bg)
        }
        // A new list for each profile, kind and search; the scaffold reads it when its key changes.
        .onChange(of: key, initial: true) { _, _ in
            list = makeList(profile: app.currentProfile, kind: kind, query: query)
        }
        // The hub is asked once the typing pauses, not at every letter.
        .task(id: typed) {
            if typed != query { try? await Task.sleep(for: .milliseconds(300)) }
            if !Task.isCancelled { query = typed }
        }
        .accessibilityIdentifier("knowledge.page")
    }

    private func label(_ kind: String?) -> String {
        l10n("knowledge.filter_\(kind ?? "all")")
    }

    private func makeList(profile: String, kind: String?, query: String) -> PagedList<KnowledgeRow> {
        let api = app.api
        return PagedList { cursor in
            let page = try await api.call {
                try await KnowledgeAPI.knowledgeListItems(
                    xHubProfile: profile, kind: KnowledgeRules.kind(kind), q: KnowledgeRules.query(query),
                    cursor: cursor, limit: 50, apiConfiguration: $0
                )
            }
            return ListPage(items: page.items.map(KnowledgeRow.init(item:)), next: page.nextCursor)
        }
    }
}

/// One row: the kind, the title, the day, the text (three lines) and its tags.
struct KnowledgeItemRow: View {
    let item: KnowledgeItem
    let kindLabel: String
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            HStack(spacing: Space.s2) {
                StatusPill(text: kindLabel, kind: item.kind == .journal ? .good : .neutral)
                Text(item.title ?? l10n("knowledge.untitled"))
                    .font(.system(size: FontSize.sizeSm, weight: .medium)).lineLimit(2)
                    .contentDirection(of: item.title ?? "", fill: false)
                Spacer(minLength: Space.s2)
                Text(KnowledgeRules.dayText(item, language: app.language))
                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
            if let content = item.content, !content.isEmpty {
                Text(content).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(3)
                    .contentDirection(of: content)
            }
            if !item.tags.isEmpty || showsFiles {
                HStack(spacing: Space.s1) {
                    ForEach(item.tags, id: \.self) { StatusPill(text: $0) }
                    if showsFiles { StatusPill(text: l10n("knowledge.files_count", ["count": String(item.attachmentIds.count)])) }
                }
            }
        }
        .accessibilityIdentifier("knowledge.item.\(item.id)")
    }

    private var showsFiles: Bool { item.kind != .file && !item.attachmentIds.isEmpty }
}

extension PhonePage {
    static let knowledge = PhonePage(.knowledge) { _ in KnowledgePage() }
}
