// Signing a remote MCP server in by OAuth on its row (DECISIONS §122), as the web's MCP page does:
// the server's sign-in state in this profile, and Connect / Reconnect, which has Hermes start its own
// browser sign-in through the hub, opens the provider's page in the system browser and asks the hub
// every two seconds until Hermes says how it went. The provider sends the browser back to the hub,
// not to the app. Nothing here holds a token. Android's McpOAuthRow.kt is its twin.
import CoreHubClient
import SwiftUI

enum McpOAuthRules {
    /// The block says `auth: oauth` (what `McpOAuthState.required` reports; read from the config here so
    /// the rule does not depend on how the generator spells a property named after a Swift keyword).
    static func signsInByOAuth(_ config: [String: JSONValue]) -> Bool {
        if case .string("oauth")? = config["auth"] { return true }
        return false
    }

    /// Whether the row offers a sign-in: a remote server from a hub that reports it (`status` is nil
    /// from an older hub), that signs in by OAuth already or carries no credential of its own in
    /// `headers` (Hermes refuses those).
    static func offers(transport: McpServer.Transport, config: [String: JSONValue], status: McpOAuthState.Status?) -> Bool {
        guard let status, transport != .stdio else { return false }
        var hasHeaders = false
        if case .dictionary(let headers)? = config["headers"] { hasHeaders = !headers.isEmpty }
        return signsInByOAuth(config) || status != .notConnected || !hasHeaders
    }

    static func offers(_ server: McpServer) -> Bool {
        offers(transport: server.transport, config: server.config, status: server.oauth?.status)
    }

    /// Whether a failed test is Hermes saying the server wants a sign-in.
    static func needsSignIn(_ error: String?) -> Bool {
        guard let error = error?.lowercased() else { return false }
        return ["no token found", "no cached tokens", "oauthnoninteractive", "oauth authentication required", "401", "unauthorized"]
            .contains { error.contains($0) }
    }
}

struct McpOAuthRow: View {
    let agent: Agent
    let server: McpServer
    let changed: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.openURL) private var openURL
    @State private var flow: McpOAuthFlow?
    @State private var starting = false
    @State private var error: String?

    var body: some View {
        if let state = server.oauth, McpOAuthRules.offers(server) {
            VStack(alignment: .leading, spacing: Space.s2) {
                HStack(spacing: Space.s2) {
                    if McpOAuthRules.signsInByOAuth(server.config) || state.status != .notConnected {
                        StatusPill(text: l10n("mcp_oauth.status.\(state.status.rawValue)"), kind: kind(state.status))
                            .accessibilityIdentifier("mcp.\(server.name).oauth")
                    }
                    if flow?.status != .pending && (state.status != .connected) {
                        Button {
                            Task { await start() }
                        } label: {
                            if starting {
                                ProgressView()
                            } else {
                                LucideLabel(l10n(state.status == .notConnected ? "mcp_oauth.connect" : "mcp_oauth.reconnect"), icon: .keyRound, size: 14)
                            }
                        }
                        .buttonStyle(ChipButtonStyle())
                        .disabled(starting)
                        .accessibilityIdentifier("mcp.\(server.name).oauth.connect")
                    }
                    Spacer()
                }
                Text(l10n("mcp_oauth.per_profile")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                if let error { NoticeView(text: error, tone: .danger) }
                if let flow {
                    switch flow.status {
                    case .pending:
                        HStack(spacing: Space.s2) {
                            ProgressView()
                            Text(l10n("mcp_oauth.waiting")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                        if let link = flow.authorizationUrl, let url = URL(string: link) {
                            Button { openURL(url) } label: { LucideLabel(l10n("mcp_oauth.open_page"), icon: .externalLink, size: 14) }
                        }
                    case .approved:
                        NoticeView(text: l10n("mcp_oauth.approved", ["count": String(flow.tools.count)]), tone: .success)
                            .accessibilityIdentifier("mcp.\(server.name).oauth.approved")
                    case .failed, .cancelled, .expired:
                        NoticeView(text: l10n("mcp_oauth.\(flow.status.rawValue)") + (flow.error.map { " \($0)" } ?? ""), tone: .danger)
                    }
                }
            }
            .task(id: flow?.id) { await poll() }
        }
    }

    private func kind(_ status: McpOAuthState.Status) -> StatusPill.Kind {
        switch status {
        case .connected: return .good
        case .expired: return .warn
        case .error: return .bad
        case .notConnected: return .neutral
        }
    }

    private func start() async {
        let profile = app.currentProfile
        starting = true
        defer { starting = false }
        error = nil
        do {
            let started = try await app.api.call {
                // No `hub_url`: the hub takes the address this app reached it on.
                try await AgentsAPI.agentsStartMcpOAuth(xHubProfile: profile, agentId: agent.id, serverName: server.name,
                                                        mcpOAuthStart: McpOAuthStart(), apiConfiguration: $0)
            }
            flow = started
            if started.status == .pending, let link = started.authorizationUrl, let url = URL(string: link) { openURL(url) }
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    /// While the sign-in waits, the hub is asked again every two seconds; once it ended, the list is read again.
    private func poll() async {
        let profile = app.currentProfile
        while let current = flow, current.status == .pending {
            try? await Task.sleep(for: .seconds(2))
            if Task.isCancelled { return }
            do {
                let next = try await app.api.call {
                    try await AgentsAPI.agentsGetMcpOAuthFlow(xHubProfile: profile, agentId: agent.id, serverName: server.name, flowId: current.id, apiConfiguration: $0)
                }
                flow = next
                error = nil
                if next.status != .pending { changed() }
            } catch {
                self.error = HubFailure(error).describe(l10n)
            }
        }
    }
}
