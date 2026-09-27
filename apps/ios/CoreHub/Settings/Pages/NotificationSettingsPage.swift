// The notification settings table (Notifications → Settings).
import AVFoundation
import CoreHubClient
import SwiftUI
import UIKit

struct NotificationSettingsPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var prefs: NotifyPreferences?
    @State private var error: String?

    var body: some View {
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            if let prefs {
                Section {
                    HStack {
                        Spacer()
                        Text(l10n("notify_page.in_app")).frame(width: 64)
                        Text(l10n("notify_page.push")).frame(width: 64)
                    }
                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    ForEach(NoticeKind.allCases, id: \.self) { kind in
                        let value = AdminLogic.value(prefs, kind)
                        HStack {
                            Text(l10n("notify_page.kind_\(kind.rawValue)")).font(.system(size: FontSize.sizeSm))
                            Spacer()
                            Toggle("", isOn: Binding(get: { value.inApp }, set: { save(AdminLogic.set(prefs, kind, inApp: $0)) })).labelsHidden().frame(width: 64)
                            Toggle("", isOn: Binding(get: { value.push }, set: { save(AdminLogic.set(prefs, kind, push: $0)) })).labelsHidden().frame(width: 64)
                        }
                        .accessibilityIdentifier("notify.\(kind.rawValue)")
                    }
                }
                Section {
                    Toggle(l10n("notify_page.quiet_on"), isOn: Binding(get: { prefs.quietHours.enabled }, set: { on in
                        var next = prefs
                        next.quietHours.enabled = on
                        save(next)
                    }))
                    if prefs.quietHours.enabled {
                        QuietHoursRow(label: l10n("notify_page.from"), value: prefs.quietHours.from) { time in
                            var next = prefs
                            next.quietHours.from = time
                            save(next)
                        }
                        QuietHoursRow(label: l10n("notify_page.to"), value: prefs.quietHours.to) { time in
                            var next = prefs
                            next.quietHours.to = time
                            save(next)
                        }
                        FactRow(label: l10n("notify_page.timezone"), value: prefs.quietHours.timezone)
                    }
                } header: {
                    Text(l10n("notify_page.quiet"))
                } footer: {
                    Text(l10n("notify_page.quiet_hint"))
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(l10n("notify_page.settings"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        do {
            prefs = try await app.api.call { try await NotifyAPI.notifyGetPreferences(apiConfiguration: $0) }
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func save(_ next: NotifyPreferences) {
        prefs = next
        Task {
            do {
                prefs = try await app.api.call { try await NotifyAPI.notifySetPreferences(notifyPreferences: next, apiConfiguration: $0) }
                error = nil
            } catch {
                self.error = HubFailure(error).describe(l10n)
            }
        }
    }
}

private struct QuietHoursRow: View {
    let label: String
    let value: String
    let changed: (String) -> Void
    @State private var text = ""

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            TextField("22:00", text: $text)
                .keyboardType(.numbersAndPunctuation)
                .multilineTextAlignment(.trailing)
                .font(.system(size: FontSize.sizeSm, design: .monospaced))
                .environment(\.layoutDirection, .leftToRight)
                .frame(width: 80)
                .onSubmit { if AdminLogic.timeOK(text) { changed(text) } }
        }
        .onAppear { text = value }
    }
}
