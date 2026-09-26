// Settings → This device: the phone's own choices (ThisDeviceExtras in SettingsScreen.swift).
import CoreHubClient
import SwiftUI

/// This device: which hub this phone talks to and as whom. Part 3 adds voice input, dictation
/// language and spoken replies here.
struct ThisDevicePage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        Form {
            Section(l10n("device.connection")) {
                FactRow(label: l10n("login.hub_url"), value: app.credentials?.hubURL.absoluteString ?? "—")
                FactRow(label: l10n("device.signed_in_as"), value: app.credentials.map { "\($0.displayName) (@\($0.username))" } ?? "—")
                FactRow(label: l10n("device.how"), value: app.credentials?.kind == .device ? l10n("device.paired") : l10n("device.password"))
                HStack {
                    Text(l10n("device.realtime")).foregroundStyle(Tone.textMuted)
                    Spacer()
                    ConnectionDot()
                }
                .font(.system(size: FontSize.sizeSm))
            }
            ThisDeviceExtras()
            // Whether an agent may ask where this phone is (§105).
            LocationChoiceSection()
            Section {
                Button(l10n("nav.sign_out"), role: .destructive) { Task { await app.signOut() } }
            }
        }
    }
}

extension PhonePage {
    static let thisDevice = PhonePage(.thisDevice) { _ in ThisDevicePage() }
}
