// An agent's Plugins: on and off.
import CoreHubClient
import SwiftUI

struct AgentPluginsPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var error: String?

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call { try await AgentsAPI.agentsListPlugins(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }
        } content: { list, reload in
            List {
                if let error { NoticeView(text: error, tone: .danger) }
                ForEach(list.warnings, id: \.self) { warning in NoticeView(text: warning, tone: .warning) }
                if list.items.isEmpty { EmptyRow(icon: .puzzle) }
                ForEach(list.items, id: \.key) { plugin in
                    Toggle(isOn: Binding(get: { plugin.enabled }, set: { value in
                        Task { await set(plugin, enabled: value, reload: reload) }
                    })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(plugin.name)
                            Text(plugin.status.rawValue.replacingOccurrences(of: "_", with: " "))
                                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                    }
                    .disabled(!plugin.manageable)
                }
            }
            .refreshable { reload() }
        }
    }

    private func set(_ plugin: AgentPlugin, enabled: Bool, reload: @escaping () -> Void) async {
        let profile = app.currentProfile
        do {
            _ = try await app.api.call {
                try await AgentsAPI.agentsUpdatePlugin(xHubProfile: profile, agentId: agent.id, pluginKey: plugin.key, agentsUpdatePluginRequest: AgentsUpdatePluginRequest(enabled: enabled), apiConfiguration: $0)
            }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        reload()
    }
}

extension PhonePage {
    static let agentPlugins = PhonePage(.agentPlugins) { context in
        if let agent = context.agent { AgentPluginsPage(agent: agent) }
    }
}
