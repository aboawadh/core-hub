// The actions on an agent's card (apps batch 8), as the web's card and its Updates card: the one
// button it needs now (Install, Update, Restart) and «⋯» with the rest (check for updates, update
// automatically, remove after asking). A job is followed to its end with its progress, then its outcome
// is said and the list read again. Android's AgentCardActions.kt is its twin.
import CoreHubClient
import SwiftUI

struct AgentCardActions: View {
    let agent: Agent
    let reload: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var action: AgentCardRules.Action?
    @State private var job: Job?
    @State private var failure: String?
    @State private var started = false
    @State private var question: ToolQuestion?

    private var running: Bool { started && failure == nil && (job.map { !AgentCardRules.terminal($0.status) } ?? true) }

    var body: some View {
        let primary = AgentCardRules.primary(agent)
        let more = AgentCardRules.menu(agent)
        VStack(alignment: .leading, spacing: Space.s2) {
            if let update = AgentCardRules.update(agent) {
                StatusPill(text: l10n(AgentCardRules.updateUntested(agent) ? "agents.card.update_available_untested" : "agents.card.update_available",
                                      ["version": update]), kind: .neutral)
                    .accessibilityIdentifier("agent.card.\(agent.slug).update")
            }
            if agent.install.newerThanTested { StatusPill(text: l10n("agents.card.newer_than_tested"), kind: .warn) }
            if primary != nil || !more.isEmpty {
                HStack(spacing: Space.s2) {
                    if let primary {
                        Button { ask(primary) } label: {
                            HStack(spacing: Space.s1) {
                                if running && action == primary { ProgressView().controlSize(.mini) } else { LucideIcon(icon(primary), size: 14) }
                                Text(label(primary))
                            }
                        }
                        .buttonStyle(ChipButtonStyle(quiet: primary == .restart))
                        .disabled(running)
                        .accessibilityIdentifier("agent.card.\(agent.slug).\(primary.rawValue)")
                    }
                    if !more.isEmpty {
                        Menu {
                            ForEach(more, id: \.self) { item in
                                Button(role: item == .uninstall ? .destructive : nil) { ask(item) } label: { LucideLabel(label(item), icon: icon(item)) }
                            }
                        } label: {
                            LucideIcon(.ellipsis, size: 16).foregroundStyle(Tone.textMuted).frame(width: 32, height: 32).contentShape(Rectangle())
                        }
                        .disabled(running)
                        .accessibilityLabel(l10n("agents.card.actions"))
                        .accessibilityIdentifier("agent.card.\(agent.slug).more")
                    }
                }
            }
            if started { outcome }
        }
        .toolQuestion($question)
    }

    @ViewBuilder
    private var outcome: some View {
        if let failure {
            NoticeView(text: failure, tone: .danger)
        } else if let job, AgentCardRules.terminal(job.status) {
            switch job.status {
            case .succeeded:
                NoticeView(text: done(job), tone: .success).accessibilityIdentifier("agent.card.\(agent.slug).done")
            case .cancelled:
                NoticeView(text: l10n("agents.card.job_cancelled"), tone: .warning)
            default:
                NoticeView(text: job.error.map { l10n("agents.card.failed_with", ["error": $0.error]) } ?? l10n("agents.card.job_failed"), tone: .danger)
                    .accessibilityIdentifier("agent.card.\(agent.slug).failed")
            }
        } else {
            VStack(alignment: .leading, spacing: Space.s1) {
                ProgressView(value: job.map(AgentCardRules.progress) ?? 0.05)
                    .tint(Tone.accent)
                Text([l10n(job?.status == .running ? "agents.card.job_running" : "agents.card.job_queued"), job?.progress.message]
                        .compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
            }
            .accessibilityIdentifier("agent.card.\(agent.slug).progress")
        }
    }

    private func label(_ action: AgentCardRules.Action) -> String {
        switch action {
        case .install: return l10n("agents.card.install")
        case .uninstall: return l10n("agents.card.remove")
        case .restart: return l10n("agents.card.restart")
        case .checkUpdate: return l10n("agents.card.check_update")
        case .upgrade: return AgentCardRules.update(agent).map { l10n("agents.card.update_to", ["version": $0]) } ?? l10n("agents.card.update_now")
        case .autoUpdateOn: return l10n("agents.card.auto_update")
        case .autoUpdateOff: return l10n("agents.card.auto_update_off")
        }
    }

    private func icon(_ action: AgentCardRules.Action) -> Lucide {
        switch action {
        case .install: return .download
        case .uninstall: return .trash
        case .restart: return .rotateCw
        case .checkUpdate: return .refreshCw
        case .upgrade: return .circleArrowDown
        case .autoUpdateOn, .autoUpdateOff: return .clock
        }
    }

    private func done(_ job: Job) -> String {
        let version = AgentCardRules.result(job, "version")
        switch action {
        case .install: return version.map { l10n("agents.card.done_install_version", ["version": $0]) } ?? l10n("agents.card.done_install")
        case .upgrade: return version.map { l10n("agents.card.done_upgrade_version", ["version": $0]) } ?? l10n("agents.card.done_upgrade")
        case .uninstall: return l10n("agents.card.done_uninstall")
        case .checkUpdate:
            if AgentCardRules.resultFlag(job, "update_available"), let latest = AgentCardRules.result(job, "latest_version") {
                return l10n("agents.card.update_available", ["version": latest])
            }
            return l10n("agents.card.up_to_date")
        default: return l10n("agents.card.done_restart")
        }
    }

    private func ask(_ chosen: AgentCardRules.Action) {
        if chosen == .uninstall {
            question = ToolQuestion(title: l10n("agents.card.remove_title", ["name": agent.name]), body: l10n("agents.card.remove_body"),
                                    confirm: l10n("agents.card.remove")) { Task { await start(chosen) } }
        } else {
            Task { await start(chosen) }
        }
    }

    /// Starts the action; a job is followed to its end, then the list is read again.
    private func start(_ chosen: AgentCardRules.Action) async {
        action = chosen
        job = nil
        failure = nil
        started = true
        let profile = app.currentProfile
        let id = agent.id
        do {
            let jobID: String?
            switch chosen {
            case .install: jobID = try await app.api.call { try await AgentsAPI.agentsInstall(xHubProfile: profile, agentId: id, apiConfiguration: $0) }.jobId
            case .uninstall: jobID = try await app.api.call { try await AgentsAPI.agentsUninstall(xHubProfile: profile, agentId: id, apiConfiguration: $0) }.jobId
            case .restart: jobID = try await app.api.call { try await AgentsAPI.agentsRestart(xHubProfile: profile, agentId: id, apiConfiguration: $0) }.jobId
            case .checkUpdate: jobID = try await app.api.call { try await AgentsAPI.agentsCheckUpdate(xHubProfile: profile, agentId: id, apiConfiguration: $0) }.jobId
            case .upgrade: jobID = try await app.api.call { try await AgentsAPI.agentsUpgrade(xHubProfile: profile, agentId: id, apiConfiguration: $0) }.jobId
            case .autoUpdateOn, .autoUpdateOff:
                let on = chosen == .autoUpdateOn
                _ = try await app.api.call { try await AgentsAPI.agentsUpdate(xHubProfile: profile, agentId: id, agentPatch: AgentPatch(autoUpdate: on), apiConfiguration: $0) }
                jobID = nil
            }
            if let jobID {
                _ = try await AgentJobs.follow(jobID, profile: profile, api: app.api) { job = $0 }
            } else {
                started = false
            }
            reload()
        } catch is CancellationError {
            return
        } catch {
            failure = HubFailure(error).describe(l10n)
        }
    }
}
