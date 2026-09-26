// An agent's MCP servers: listed and tested.
import CoreHubClient
import SwiftUI

struct AgentMcpPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var results: [String: String] = [:]

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call { try await AgentsAPI.agentsListMcpServers(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }.items
        } content: { servers, reload in
            List {
                if servers.isEmpty { EmptyRow(icon: .server) }
                ForEach(servers, id: \.name) { server in
                    VStack(alignment: .leading, spacing: Space.s1) {
                        HStack {
                            Text(server.name).font(.system(size: FontSize.sizeMd, weight: .medium))
                            Spacer()
                            StatusPill(text: server.connected ? l10n("mcp.connected") : (server.enabled ? l10n("mcp.not_connected") : l10n("common.off")), kind: server.connected ? .good : .neutral)
                        }
                        Text(l10n("mcp.tools", ["count": String(server.tools.count)]))
                            .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        if let error = server.error { NoticeView(text: error, tone: .danger) }
                        if let result = results[server.name] { NoticeView(text: result, tone: .info) }
                        Button(l10n("mcp.test")) { Task { await test(server) } }
                            .font(.system(size: FontSize.sizeSm))
                    }
                }
            }
            .refreshable { reload() }
        }
    }

    private func test(_ server: McpServer) async {
        let profile = app.currentProfile
        do {
            let result = try await app.api.call {
                try await AgentsAPI.agentsTestMcpServer(xHubProfile: profile, agentId: agent.id, serverName: server.name, apiConfiguration: $0)
            }
            results[server.name] = result.ok
                ? l10n("mcp.test_ok", ["count": String(result.tools.count)])
                : (result.error ?? l10n("mcp.test_failed"))
        } catch {
            results[server.name] = HubFailure(error).describe(l10n)
        }
    }
}

extension PhonePage {
    static let agentMcp = PhonePage(.agentMcp) { context in
        if let agent = context.agent { AgentMcpPage(agent: agent) }
    }
}
