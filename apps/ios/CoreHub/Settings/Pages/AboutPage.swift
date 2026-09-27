// Settings → About.
import CoreHubClient
import SwiftUI

struct AboutPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: "meta") {
            try await app.api.call { try await MetaAPI.metaGet(apiConfiguration: $0) }
        } content: { meta, _ in
            Form {
                Section(l10n.productName) {
                    FactRow(label: l10n("about.hub"), value: meta.name)
                    FactRow(label: l10n("about.server_version"), value: meta.serverVersion)
                    FactRow(label: l10n("about.contract_version"), value: meta.contractVersion)
                    FactRow(label: l10n("about.app_version"), value: app.appVersion)
                    FactRow(label: l10n("about.source"), value: "github.com/\(Product.repository)")
                }
            }
        }
    }
}

extension PhonePage {
    static let about = PhonePage(.about) { _ in AboutPage() }
}
