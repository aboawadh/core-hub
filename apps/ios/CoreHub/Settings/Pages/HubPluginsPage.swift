// Settings → Plugins (the web's PluginsTab): what is installed on this hub, with its kind, version and
// state. The contract answers only the list (`plugins.list`): the hub has no installer, switch or
// settings for hub plugins yet, and the empty state says so rather than promising one. An agent's own
// plugins are the agent's Plugins page. Android's HubPluginsPage.kt is the twin.
import CoreHubClient
import SwiftUI

struct HubPluginsPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: "plugins") {
            try await app.api.call { try await PluginsAPI.pluginsList(apiConfiguration: $0) }.items
        } content: { plugins, reload in
            List {
                Section {
                    if plugins.isEmpty {
                        EmptyStateView(icon: .puzzle, title: l10n("knowledge.plugins_none"), message: l10n("knowledge.plugins_none_body"))
                            .listRowBackground(Color.clear)
                            .accessibilityIdentifier("plugins.empty")
                    }
                    ForEach(plugins, id: \.id) { plugin in
                        HubPluginRow(plugin: plugin)
                    }
                } footer: {
                    Text(l10n("knowledge.plugins_intro"))
                }
            }
            .refreshable { reload() }
        }
        .accessibilityIdentifier("plugins.page")
    }
}

/// One plugin: its name, `slug · version · kind`, and its state.
struct HubPluginRow: View {
    let plugin: HubPlugin
    @Environment(\.l10n) private var l10n

    var body: some View {
        HStack(spacing: Space.s3) {
            LucideIcon(.puzzle, size: 18).foregroundStyle(Tone.textMuted)
            VStack(alignment: .leading, spacing: 2) {
                Text(plugin.name).font(.system(size: FontSize.sizeSm, weight: .medium))
                Text("\(plugin.slug) · \(plugin.version) · \(plugin.kind.rawValue)")
                    .font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textMuted)
                    .environment(\.layoutDirection, .leftToRight)
            }
            Spacer(minLength: Space.s2)
            StatusPill(text: l10n("knowledge.plugins_status_\(plugin.status.rawValue)"), kind: kind)
        }
        .accessibilityIdentifier("plugin.\(plugin.slug)")
    }

    private var kind: StatusPill.Kind {
        switch plugin.status {
        case .running: return .good
        case .error: return .bad
        case .stopped: return .neutral
        }
    }
}

extension PhonePage {
    static let plugins = PhonePage(.plugins) { _ in HubPluginsPage() }
}
