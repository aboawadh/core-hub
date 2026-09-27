// The list scaffold every list page on the phone uses (docs/clients/phone-pages.md): an optional
// search field and filter chips, pull to refresh, the next page loaded near the end (or by «Load
// more»), the empty and failed states, and a row's actions by swiping or a long press. Android's
// ListScaffold.kt is its twin.
import SwiftUI

/// One page of a list answer (`{ items, next_cursor }`).
struct ListPage<Item> {
    let items: [Item]
    let next: String?
}

/// A list read page by page. `refresh()` starts again from the first page; `loadMore()` adds the
/// next one while there is one. A failure keeps what was loaded and says why (`failure`).
@MainActor
@Observable
final class PagedList<Item: Identifiable> {
    private(set) var items: [Item] = []
    private(set) var next: String?
    /// True until the first page came back (or failed).
    private(set) var loading = true
    private(set) var loadingMore = false
    private(set) var failure: HubFailure?
    @ObservationIgnored private let fetch: (_ cursor: String?) async throws -> ListPage<Item>

    var hasMore: Bool { next != nil }

    init(fetch: @escaping (_ cursor: String?) async throws -> ListPage<Item>) {
        self.fetch = fetch
    }

    func refresh() async {
        do {
            let page = try await fetch(nil)
            items = Self.unique(page.items)
            next = page.next
            failure = nil
        } catch is CancellationError {
            return
        } catch {
            failure = HubFailure(error)
        }
        loading = false
    }

    func loadMore() async {
        guard let cursor = next, !loadingMore, !loading else { return }
        loadingMore = true
        defer { loadingMore = false }
        do {
            let page = try await fetch(cursor)
            items = Self.unique(items + page.items)
            next = page.next
            failure = nil
        } catch is CancellationError {
            return
        } catch {
            failure = HubFailure(error)
        }
    }

    /// Takes one row out at once (after a delete), without reading the list again.
    func remove(_ id: Item.ID) {
        items.removeAll { $0.id == id }
    }

    private static func unique(_ list: [Item]) -> [Item] {
        var seen = Set<Item.ID>()
        return list.filter { seen.insert($0.id).inserted }
    }
}

/// An action on one row: in its long-press menu, or a swipe.
struct RowAction: Identifiable {
    let id = UUID()
    let title: String
    let icon: Lucide
    var destructive = false
    let run: () -> Void
}

/// A filter chip over the list; a `nil` value is «All».
struct ListFilter: Hashable {
    let value: String?
    let label: String
}

/// The list page. `row` draws one item; `actions` are its long-press menu; `swipe` its trailing
/// swipe (usually Delete, which then asks). Search and filters show only when given. The page
/// loads when it appears and whenever `key` changes (a filter, the search, the profile).
/// Identifiers: `<tag>`, `<tag>.search`, `<tag>.filter.<value|all>`, `<tag>.more`, `<tag>.empty`.
struct ListScaffold<Item: Identifiable, Row: View>: View {
    let list: PagedList<Item>
    var key: String
    var query: Binding<String>?
    var filters: [ListFilter]
    var filter: Binding<String?>?
    var emptyIcon: Lucide
    var emptyTitle: String?
    var emptyMessage: String?
    var actions: ((Item) -> [RowAction])?
    var swipe: ((Item) -> [RowAction])?
    var tag: String
    @ViewBuilder let row: (Item) -> Row
    @Environment(\.l10n) private var l10n

    init(
        _ list: PagedList<Item>,
        key: String = "",
        query: Binding<String>? = nil,
        filters: [ListFilter] = [],
        filter: Binding<String?>? = nil,
        emptyIcon: Lucide = .box,
        emptyTitle: String? = nil,
        emptyMessage: String? = nil,
        actions: ((Item) -> [RowAction])? = nil,
        swipe: ((Item) -> [RowAction])? = nil,
        tag: String = "list",
        @ViewBuilder row: @escaping (Item) -> Row
    ) {
        self.list = list
        self.key = key
        self.query = query
        self.filters = filters
        self.filter = filter
        self.emptyIcon = emptyIcon
        self.emptyTitle = emptyTitle
        self.emptyMessage = emptyMessage
        self.actions = actions
        self.swipe = swipe
        self.tag = tag
        self.row = row
    }

    private var narrowed: Bool { !(query?.wrappedValue ?? "").isEmpty || filter?.wrappedValue != nil }

    var body: some View {
        List {
            if let query {
                HStack(spacing: Space.s2) {
                    LucideIcon(.search, size: 16).foregroundStyle(Tone.textFaint)
                    TextField(l10n("kit.search"), text: query)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("\(tag).search")
                }
            }
            if !filters.isEmpty, let filter {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Space.s2) {
                        ForEach(filters, id: \.self) { chip in
                            Button(chip.label) { filter.wrappedValue = chip.value }
                                .buttonStyle(ChipButtonStyle(quiet: filter.wrappedValue != chip.value))
                                .accessibilityAddTraits(filter.wrappedValue == chip.value ? .isSelected : [])
                                .accessibilityIdentifier("\(tag).filter.\(chip.value ?? "all")")
                        }
                    }
                }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            if let failure = list.failure, !list.items.isEmpty {
                NoticeView(text: failure.describe(l10n), tone: .danger)
            }
            if list.loading {
                SkeletonList(rows: 4).listRowBackground(Color.clear).listRowSeparator(.hidden)
            } else if list.items.isEmpty, let failure = list.failure {
                EmptyStateView(icon: .triangleAlert, title: l10n("common.error_title"), message: failure.describe(l10n),
                               actionTitle: l10n("common.retry"), action: { Task { await list.refresh() } })
                    .listRowBackground(Color.clear)
            } else if list.items.isEmpty {
                EmptyStateView(icon: emptyIcon, title: narrowed ? l10n("kit.no_matches") : (emptyTitle ?? l10n("common.empty")),
                               message: narrowed ? nil : emptyMessage)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .accessibilityIdentifier("\(tag).empty")
            }
            ForEach(list.items) { item in
                row(item)
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        ForEach(swipe?(item) ?? []) { action in
                            Button(role: action.destructive ? .destructive : nil, action: action.run) {
                                LucideLabel(action.title, icon: action.icon)
                            }
                        }
                    }
                    .contextMenu {
                        ForEach(actions?(item) ?? []) { action in
                            Button(role: action.destructive ? .destructive : nil, action: action.run) {
                                LucideLabel(action.title, icon: action.icon)
                            }
                        }
                    }
            }
            if list.hasMore {
                HStack {
                    Spacer()
                    if list.loadingMore {
                        ProgressView()
                    } else {
                        Button(l10n("kit.load_more")) { Task { await list.loadMore() } }
                            .accessibilityIdentifier("\(tag).more")
                    }
                    Spacer()
                }
                .listRowBackground(Color.clear)
                // The next page comes as the end of the list shows; the button is there for VoiceOver and a failure.
                .onAppear { if list.failure == nil { Task { await list.loadMore() } } }
            }
        }
        .refreshable { await list.refresh() }
        .task(id: key) { await list.refresh() }
        .scrollContentBackground(.hidden)
        .background(Tone.bg)
        .accessibilityIdentifier(tag)
    }
}
