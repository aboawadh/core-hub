// Settings → Plugins: the hub's plugins.
import CoreHubClient
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// What is installed on this hub (`plugins.list`); an agent's own plugins live under the agent.
struct HubPluginsPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: "plugins") {
            try await app.api.call { try await PluginsAPI.pluginsList(apiConfiguration: $0) }.items
        } content: { plugins, reload in
            List {
                if plugins.isEmpty { EmptyRow(icon: .puzzle) }
                ForEach(plugins, id: \.id) { plugin in
                    HStack {
                        Text(plugin.name)
                        Spacer()
                        Text(plugin.version).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                }
            }
            .refreshable { reload() }
        }
    }
}

extension PhonePage {
    static let plugins = PhonePage(.plugins) { _ in HubPluginsPage() }
}
