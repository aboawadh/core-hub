// Settings → Notifications: the inbox (All / Unread, mark one or all read, tap to open what a notice
// is about) and, beside it, the notification settings. Android's NotificationsPage.kt is the twin.
import CoreHubClient
import SwiftUI

struct NotificationsPage: View {
    @Environment(\.l10n) private var l10n
    @State private var settings = false

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $settings) {
                Text(l10n("own_settings.inbox")).tag(false)
                Text(l10n("own_settings.inbox_settings")).tag(true)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, Space.s4)
            .padding(.vertical, Space.s2)
            .accessibilityIdentifier("notices.tabs")
            if settings {
                NotificationPreferences()
            } else {
                InboxList()
            }
        }
        .background(Tone.bg)
    }
}

/// The inbox's state: the notices read so far, the unread count, and the filter.
@MainActor
@Observable
final class InboxModel {
    private(set) var notices: [Notice] = []
    private(set) var unread = 0
    private(set) var next: String?
    private(set) var loading = true
    private(set) var failure: HubFailure?
    var unreadOnly = false

    @ObservationIgnored private let fetch: (_ unreadOnly: Bool, _ cursor: String?) async throws -> NotifyListNotices200Response
    @ObservationIgnored private let mark: (_ id: String, _ read: Bool) async throws -> Void
    @ObservationIgnored private let markAll: () async throws -> Void

    init(
        fetch: @escaping (Bool, String?) async throws -> NotifyListNotices200Response,
        mark: @escaping (String, Bool) async throws -> Void,
        markAll: @escaping () async throws -> Void
    ) {
        self.fetch = fetch
        self.mark = mark
        self.markAll = markAll
    }

    func refresh() async {
        do {
            let page = try await fetch(unreadOnly, nil)
            notices = page.items
            unread = page.unreadCount
            next = page.nextCursor
            failure = nil
        } catch is CancellationError {
            return
        } catch {
            failure = HubFailure(error)
        }
        loading = false
    }

    func loadMore() async {
        guard let cursor = next else { return }
        do {
            let page = try await fetch(unreadOnly, cursor)
            let known = Set(notices.map(\.id))
            notices += page.items.filter { !known.contains($0.id) }
            unread = page.unreadCount
            next = page.nextCursor
        } catch is CancellationError {
            return
        } catch {
            failure = HubFailure(error)
        }
    }

    /// Read or unread one notice: shown at once, then told to the hub; a refusal reads the list again.
    func set(_ notice: Notice, read: Bool) async {
        let before = notices
        (notices, unread) = OwnSettingsRules.marking(notices, unread: unread, id: notice.id, read: read)
        guard notices != before else { return }
        do {
            try await mark(notice.id, read)
        } catch {
            failure = HubFailure(error)
            await refresh()
        }
    }

    func readAll() async {
        do {
            try await markAll()
            notices = OwnSettingsRules.allRead(notices, unreadOnly: unreadOnly)
            unread = 0
            if unreadOnly { next = nil }
            failure = nil
        } catch {
            failure = HubFailure(error)
        }
    }
}

private struct InboxList: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var model: InboxModel?

    var body: some View {
        Group {
            if let model {
                content(model)
            } else {
                Color.clear
            }
        }
        .onAppear {
            guard model == nil else { return }
            let api = app.api
            model = InboxModel(
                fetch: { unreadOnly, cursor in
                    try await api.call {
                        try await NotifyAPI.notifyListNotices(unread: unreadOnly ? true : nil, cursor: cursor, limit: 50, apiConfiguration: $0)
                    }
                },
                mark: { id, read in
                    _ = try await api.call {
                        try await NotifyAPI.notifyUpdateNotice(noticeId: id, notifyUpdateNoticeRequest: NotifyUpdateNoticeRequest(read: read), apiConfiguration: $0)
                    }
                },
                markAll: {
                    _ = try await api.call {
                        try await NotifyAPI.notifyMarkAllRead(notifyMarkAllReadRequest: NotifyMarkAllReadRequest(), apiConfiguration: $0)
                    }
                }
            )
        }
    }

    @ViewBuilder
    private func content(_ model: InboxModel) -> some View {
        @Bindable var model = model
        List {
            HStack(spacing: Space.s2) {
                Picker(l10n("own_settings.inbox"), selection: $model.unreadOnly) {
                    Text(l10n("own_settings.inbox_all")).tag(false)
                    Text(l10n("own_settings.inbox_unread")).tag(true)
                }
                .pickerStyle(.segmented)
                .fixedSize()
                .accessibilityIdentifier("notices.filter")
                if model.unread > 0 {
                    Text(l10n("own_settings.inbox_unread_count", ["count": String(model.unread)]))
                        .font(.system(size: FontSize.sizeXs, weight: .medium))
                        .foregroundStyle(Tone.accent)
                }
                Spacer(minLength: 0)
                Button {
                    Task { await model.readAll() }
                } label: {
                    LucideLabel(l10n("own_settings.inbox_mark_all"), icon: .check, size: 14)
                }
                .buttonStyle(.borderless)
                .disabled(model.unread == 0)
                .accessibilityIdentifier("notices.mark_all")
            }
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            if let failure = model.failure, !model.notices.isEmpty {
                NoticeView(text: failure.describe(l10n), tone: .danger)
            }
            if model.loading {
                SkeletonList(rows: 4).listRowBackground(Color.clear).listRowSeparator(.hidden)
            } else if model.notices.isEmpty, let failure = model.failure {
                EmptyStateView(icon: .triangleAlert, title: l10n("common.error_title"), message: failure.describe(l10n),
                               actionTitle: l10n("common.retry"), action: { Task { await model.refresh() } })
                    .listRowBackground(Color.clear)
            } else if model.notices.isEmpty {
                EmptyStateView(icon: .bell, title: l10n(model.unreadOnly ? "own_settings.inbox_none_unread" : "own_settings.inbox_none"),
                               message: l10n("own_settings.inbox_none_body"))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .accessibilityIdentifier("notices.empty")
            }
            ForEach(model.notices, id: \.id) { notice in
                row(notice, model)
            }
            if model.next != nil {
                HStack {
                    Spacer()
                    Button(l10n("kit.load_more")) { Task { await model.loadMore() } }
                        .accessibilityIdentifier("notices.more")
                    Spacer()
                }
                .listRowBackground(Color.clear)
                .onAppear { if model.failure == nil { Task { await model.loadMore() } } }
            }
        }
        .refreshable { await model.refresh() }
        .task(id: model.unreadOnly) { await model.refresh() }
        .scrollContentBackground(.hidden)
        .accessibilityIdentifier("notices.list")
    }

    private func row(_ notice: Notice, _ model: InboxModel) -> some View {
        let unread = notice.readAt == nil
        let toggle = LucideLabel(
            l10n(unread ? "own_settings.inbox_mark_read" : "own_settings.inbox_mark_unread"),
            icon: unread ? .check : .mail
        )
        return Button {
            Task {
                if unread { await model.set(notice, read: true) }
                if let route = OwnSettingsRules.route(for: notice, selector: app.currentProfile) { app.pendingRoute = route }
            }
        } label: {
            HStack(alignment: .top, spacing: Space.s2) {
                Circle()
                    .fill(unread ? Tone.accent : Color.clear)
                    .frame(width: 8, height: 8)
                    .padding(.top, 6)
                VStack(alignment: .leading, spacing: 2) {
                    Text(notice.title)
                        .font(.system(size: FontSize.sizeSm, weight: unread ? .semibold : .regular))
                        .foregroundStyle(Tone.text)
                        .contentDirection(of: notice.title)
                    if let body = notice.body {
                        Text(body).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2)
                            .contentDirection(of: body)
                    }
                    Text(notice.createdAt.shortText(app.language))
                        .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint)
                }
            }
        }
        .accessibilityValue(l10n(unread ? "own_settings.inbox_is_unread" : "own_settings.inbox_is_read"))
        .accessibilityIdentifier("notice.\(notice.id)")
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button { Task { await model.set(notice, read: unread) } } label: { toggle }
                .tint(Tone.accent)
        }
        .contextMenu {
            Button { Task { await model.set(notice, read: unread) } } label: { toggle }
        }
    }
}

/// The notification settings: whether this phone can show them, which notice comes in the app and
/// as a push (its own page), and the hub's per-person switches (`auth.setPreferences`).
private struct NotificationPreferences: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: "notification-preferences") {
            try await app.api.call { try await AuthAPI.authGetPreferences(apiConfiguration: $0) }
        } content: { preferences, reload in
            PreferencesForm(initial: preferences, saved: reload) { draft in
                // Whether this phone can show them at all comes first.
                PushStatusSection()
                Section {
                    // Which notice comes in the app and as a push, and quiet hours (phone parity).
                    NavigationLink {
                        NotificationSettingsPage()
                    } label: {
                        LucideLabel(l10n("notify_page.settings"), icon: .slidersHorizontal, size: 16)
                    }
                    .accessibilityIdentifier("notifications.settings")
                }
                Section {
                    Toggle(l10n("notifications.on_complete"), isOn: draft.notifyOnComplete)
                    Toggle(l10n("notifications.on_approval"), isOn: draft.notifyOnApproval)
                    Toggle(l10n("notifications.sound"), isOn: draft.soundOnComplete)
                }
            }
        }
    }
}

extension PhonePage {
    static let notifications = PhonePage(.notifications) { _ in NotificationsPage() }
}
