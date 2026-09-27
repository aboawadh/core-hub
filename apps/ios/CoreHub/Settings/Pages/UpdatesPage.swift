// Settings → Updates: this app's own update check, then (for owners and admins) where the hub takes
// the app releases from and the shelf of published ones (UpdatesAdmin.swift).
import CoreHubClient
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

struct UpdatesPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: "updates") {
            let version = app.appVersion
            return try await app.api.call {
                try await UpdatesAPI.updatesCheck(platform: .ios, channel: .stable, currentVersion: version, apiConfiguration: $0)
            }
        } content: { check, reload in
            Form {
                Section { Text(l10n("settings.global_note")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
                Section {
                    FactRow(label: l10n("about.app_version"), value: app.appVersion)
                    if let release = check.release, check.available {
                        FactRow(label: l10n("updates.available"), value: release.version)
                        Text(app.language == .ar ? release.notes.ar : release.notes.en)
                            .font(.system(size: FontSize.sizeSm))
                    } else {
                        Text(check.reason == .notConfigured ? l10n("updates.not_configured") : l10n("updates.up_to_date"))
                            .foregroundStyle(Tone.textMuted)
                    }
                    Button(l10n("updates.check")) { reload() }
                }
                // The hub's release source and shelf: the page is for owners and admins.
                if app.isAdmin { UpdatesAdminSections() }
            }
        }
    }
}

extension PhonePage {
    static let updates = PhonePage(.updates) { _ in UpdatesPage() }
}
