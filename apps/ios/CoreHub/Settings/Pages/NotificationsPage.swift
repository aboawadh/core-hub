// Settings → Notifications: the inbox and the notification settings.
import CoreHubClient
import SwiftUI

struct NotificationsPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: "notifications") {
            async let preferences = app.api.call { try await AuthAPI.authGetPreferences(apiConfiguration: $0) }
            async let notices = app.api.call { try await NotifyAPI.notifyListNotices(limit: 30, apiConfiguration: $0) }
            return try await (preferences, notices)
        } content: { loaded, reload in
            PreferencesForm(initial: loaded.0, saved: reload) { draft in
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
                Section(l10n("notifications.inbox", ["count": String(loaded.1.unreadCount)])) {
                    if loaded.1.items.isEmpty { EmptyRow(icon: .bell) }
                    ForEach(loaded.1.items, id: \.id) { notice in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(notice.title).font(.system(size: FontSize.sizeSm, weight: notice.readAt == nil ? .semibold : .regular))
                                .contentDirection(of: notice.title)
                            if let body = notice.body {
                                Text(body).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2)
                                    .contentDirection(of: body)
                            }
                        }
                    }
                }
            }
        }
    }
}

extension PhonePage {
    static let notifications = PhonePage(.notifications) { _ in NotificationsPage() }
}
