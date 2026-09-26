// Settings → Theme.
import CoreHubClient
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// Theme: the look this phone uses (local) and the one the hub keeps for the person.
struct ThemePage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        Form {
            Section {
                Picker(l10n("nav.theme"), selection: Binding(get: { app.theme }, set: { app.theme = $0 })) {
                    ForEach(ThemeChoice.allCases) { choice in
                        Label { Text(l10n(choice.labelKey)) } icon: { Image(lucide: choice.icon) }.tag(choice)
                    }
                }
                .pickerStyle(.inline)
            }
        }
    }
}

extension PhonePage {
    static let theme = PhonePage(.theme) { _ in ThemePage() }
}
