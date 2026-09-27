// An agent's Plugins, as Hermes lists them in the profile (apps batch 8, the web's page): the switch,
// the source and Hermes's own status, Remove (after asking) for what was installed into the profile, and
// Install by catalog name, owner/repo or Git URL — Hermes's install as a job, followed to its end.
// Android's AgentPluginsPage.kt is its twin.
import CoreHubClient
import SwiftUI

struct AgentPluginsPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var error: String?
    @State private var identifier = ""
    @State private var installing = false
    @State private var job: Job?
    @State private var installError: String?
    @State private var busy: String?
    @State private var question: ToolQuestion?

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call { try await AgentsAPI.agentsListPlugins(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }
        } content: { list, reload in
            List {
                Text(l10n("agents.plugin.note")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    .listRowBackground(Color.clear)
                Section {
                    installForm(reload)
                }
                if let error { NoticeView(text: error, tone: .danger) }
                ForEach(list.warnings, id: \.self) { warning in NoticeView(text: warning, tone: .warning) }
                if list.items.isEmpty {
                    EmptyStateView(icon: .puzzle, title: l10n("agents.plugin.none"), message: l10n("agents.plugin.none_body"))
                        .listRowBackground(Color.clear)
                }
                ForEach(list.items, id: \.key) { plugin in
                    PluginRow(plugin: plugin, busy: busy == plugin.key) { on in
                        change(plugin.key, reload) { profile in
                            _ = try await app.api.call {
                                try await AgentsAPI.agentsUpdatePlugin(xHubProfile: profile, agentId: agent.id, pluginKey: plugin.key, agentsUpdatePluginRequest: AgentsUpdatePluginRequest(enabled: on), apiConfiguration: $0)
                            }
                        }
                    } remove: {
                        question = ToolQuestion(title: l10n("agents.plugin.remove_title", ["name": plugin.name]), body: l10n("agents.plugin.remove_body"),
                                                confirm: l10n("agents.plugin.remove")) {
                            change(plugin.key, reload) { profile in
                                try await app.api.call { try await AgentsAPI.agentsDeletePlugin(xHubProfile: profile, agentId: agent.id, pluginKey: plugin.key, apiConfiguration: $0) }
                            }
                        }
                    }
                }
            }
            .refreshable { reload() }
            .toolQuestion($question)
        }
    }

    @ViewBuilder
    private func installForm(_ reload: @escaping () -> Void) -> some View {
        let bad = !identifier.trimmingCharacters(in: .whitespaces).isEmpty && !PluginRules.validIdentifier(identifier)
        HStack(spacing: Space.s2) {
            TextField(l10n("agents.plugin.install_label"), text: $identifier, prompt: Text(verbatim: "owner/repo · https://…"))
                .font(.system(size: FontSize.sizeSm, design: .monospaced))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .environment(\.layoutDirection, .leftToRight)
                .accessibilityIdentifier("plugins.identifier")
            if installing {
                ProgressView()
            } else {
                Button(l10n("agents.plugin.install")) { Task { await install(reload) } }
                    .buttonStyle(ChipButtonStyle())
                    .disabled(!PluginRules.validIdentifier(identifier))
                    .accessibilityIdentifier("plugins.install")
            }
        }
        if bad { Text(l10n("agents.plugin.install_bad")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger) }
        Text(l10n("agents.plugin.install_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
        if let installError { NoticeView(text: installError, tone: .danger) }
        if let job {
            switch job.status {
            case .succeeded:
                NoticeView(text: l10n("agents.plugin.installed", ["name": AgentCardRules.result(job, "name") ?? ""]), tone: .success)
                    .accessibilityIdentifier("plugins.result")
            case .failed, .cancelled:
                NoticeView(text: l10n("agents.tools.refused", ["message": job.error?.error ?? ""]), tone: .danger)
                    .accessibilityIdentifier("plugins.result")
            default:
                NoticeView(text: job.progress.message ?? l10n("agents.plugin.installing"), tone: .info)
                    .accessibilityIdentifier("plugins.progress")
            }
        }
    }

    private func change(_ key: String, _ reload: @escaping () -> Void, _ call: @escaping (String) async throws -> Void) {
        busy = key
        Task {
            do {
                try await call(app.currentProfile)
                error = nil
            } catch {
                self.error = AgentToolErrors.describe(error, l10n)
            }
            busy = nil
            reload()
        }
    }

    /// Hermes's install as a job: started, followed until it ends, then the list read again.
    private func install(_ reload: @escaping () -> Void) async {
        let value = identifier.trimmingCharacters(in: .whitespacesAndNewlines)
        guard PluginRules.validIdentifier(value), !installing else { return }
        installing = true
        defer { installing = false }
        job = nil
        installError = nil
        let profile = app.currentProfile
        do {
            let accepted = try await app.api.call {
                try await AgentsAPI.agentsInstallPlugin(xHubProfile: profile, agentId: agent.id, agentsInstallPluginRequest: AgentsInstallPluginRequest(identifier: value), apiConfiguration: $0)
            }
            identifier = ""
            _ = try await AgentJobs.follow(accepted.jobId, profile: profile, api: app.api) { job = $0 }
            reload()
        } catch is CancellationError {
            return
        } catch {
            installError = AgentToolErrors.describe(error, l10n)
        }
    }
}

struct PluginRow: View {
    let plugin: AgentPlugin
    let busy: Bool
    let switched: (Bool) -> Void
    let remove: () -> Void
    @Environment(\.l10n) private var l10n

    var body: some View {
        HStack(alignment: .top, spacing: Space.s3) {
            VStack(alignment: .leading, spacing: Space.s1) {
                HStack(spacing: Space.s2) {
                    Text(plugin.name).font(.system(size: FontSize.sizeMd, weight: .medium))
                    if let version = plugin.version { Text(version).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
                }
                HStack(spacing: Space.s1) {
                    StatusPill(text: l10n("agents.plugin.source_\(plugin.source.rawValue)"), kind: plugin.source == .bundled ? .neutral : .good)
                    StatusPill(text: l10n("agents.plugin.status_\(plugin.status.rawValue)"),
                               kind: plugin.status == .enabled ? .good : plugin.status == .disabled ? .warn : .neutral)
                }
                if let description = plugin.description, !description.isEmpty {
                    Text(description).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).contentDirection(of: description)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if plugin.removable {
                Button(action: remove) { LucideIcon(.trash, size: 16).foregroundStyle(Tone.textMuted).frame(width: 32, height: 32) }
                    .buttonStyle(.plain)
                    .disabled(busy)
                    .accessibilityLabel(l10n("agents.plugin.remove"))
                    .accessibilityIdentifier("plugin.\(plugin.key).remove")
            }
            Toggle(l10n("agents.plugin.status_enabled"), isOn: Binding(get: { plugin.enabled }, set: { switched($0) }))
                .labelsHidden()
                .disabled(!plugin.manageable || busy)
                .accessibilityIdentifier("plugin.\(plugin.key).switch")
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if plugin.removable {
                Button(role: .destructive, action: remove) { LucideLabel(l10n("agents.plugin.remove"), icon: .trash) }
            }
        }
        .accessibilityIdentifier("plugin.\(plugin.key)")
    }
}

extension PhonePage {
    static let agentPlugins = PhonePage(.agentPlugins) { context in
        if let agent = context.agent { AgentPluginsPage(agent: agent) }
    }
}
