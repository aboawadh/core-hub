// Two things a task offers on the web that the phone now offers too (the web's TaskDialog and
// HandOverDialog): its git worktree — where the work is on disk, on which branch, how it stands —
// removed after a question (`tasks.deleteWorktree`); and handing a card on Hermes's board to
// another profile's Hermes (`tasks.assignTask` with `profile`, not started), asked about first
// while it runs.
import CoreHubClient
import SwiftUI

enum TaskExtrasRules {
    /// A Hermes card that is still open may be handed to another profile.
    static func handable(_ facts: TaskFacts) -> Bool {
        facts.hermes && facts.status != .done && facts.status != .archived
    }

    /// The profiles it may go to: every one the person may enter but its own.
    static func targets(_ profiles: [String], current: String) -> [String] {
        profiles.filter { $0 != current }
    }

    /// The Hermes that does the handing over: the card's own agent, else the profile's Hermes.
    static func hermesID(_ assignee: Assignee?, agents: [Agent]) -> String? {
        if let assignee, assignee.kind == .agent { return assignee.id }
        return agents.first { $0.slug == "hermes" }?.id
    }

    static func statusKind(_ status: Worktree.Status) -> StatusPill.Kind {
        switch status {
        case .ready: return .good
        case .dirty: return .warn
        case .error: return .bad
        default: return .neutral
        }
    }
}

struct TaskWorktreeSection: View {
    let worktree: Worktree
    let running: Bool
    let remove: () -> Void
    @Environment(\.l10n) private var l10n
    @State private var asking = false

    var body: some View {
        Section {
            HStack {
                StatusPill(text: l10n("task_extras.worktree.status.\(worktree.status.rawValue)"), kind: TaskExtrasRules.statusKind(worktree.status))
                    .accessibilityIdentifier("task.worktree.status")
                Spacer()
            }
            FactRow(label: l10n("task_extras.worktree.branch"), value: worktree.branch)
            VStack(alignment: .leading, spacing: 2) {
                Text(l10n("task_extras.worktree.path")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                Text(worktree.path).font(.system(size: FontSize.sizeXs, design: .monospaced)).textSelection(.enabled)
                    .environment(\.layoutDirection, .leftToRight)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if worktree.status == .ready || worktree.status == .dirty {
                FactRow(label: l10n("task_extras.worktree.changes"), value: l10n("task_extras.worktree.counts", [
                    "files": String(worktree.changedFiles), "ahead": String(worktree.ahead), "base": worktree.baseBranch,
                ]))
            }
            if let error = worktree.error, !error.isEmpty {
                Text(error).font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.dangerSoftText)
                    .environment(\.layoutDirection, .leftToRight)
            }
            if worktree.status != .removed {
                Button(l10n("task_extras.worktree.remove"), role: .destructive) { asking = true }
                    .disabled(running)
                    .accessibilityIdentifier("task.worktree.remove")
                if running {
                    Text(l10n("task_extras.worktree.running")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
            }
        } header: {
            Text(l10n("task_extras.worktree.title"))
        }
        .alert(l10n("task_extras.worktree.confirm_remove"), isPresented: $asking) {
            Button(l10n("common.cancel"), role: .cancel) {}
            Button(l10n("task_extras.worktree.remove"), role: .destructive) { remove() }
                .accessibilityIdentifier("dialog.confirm")
        } message: {
            Text(l10n("task_extras.worktree.confirm_remove_body", ["branch": worktree.branch]))
        }
    }
}

struct HandOverSheet: View {
    let facts: TaskFacts
    let done: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var target: String?
    @State private var busy = false
    @State private var error: String?
    @State private var confirming = false

    var body: some View {
        let targets = TaskExtrasRules.targets(app.enterableProfiles, current: facts.profile)
        Form {
            if targets.isEmpty {
                Section { Text(l10n("task_extras.handover.no_workspaces")).foregroundStyle(Tone.textMuted) }
            } else {
                Section {
                    Picker(l10n("task_extras.handover.workspace"), selection: Binding(get: { target ?? targets.first }, set: { target = $0 })) {
                        ForEach(targets, id: \.self) { slug in Text(app.profileName(slug)).tag(String?.some(slug)) }
                    }
                    .accessibilityIdentifier("task.handover.profile")
                } footer: {
                    Text(l10n("task_extras.handover.hint"))
                }
            }
            if let error { Section { NoticeView(text: error, tone: .danger) } }
        }
        .navigationTitle(l10n("task_extras.handover.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(l10n("task_extras.handover.submit")) {
                    if facts.status == .running { confirming = true } else { Task { await handOver(target ?? targets.first) } }
                }
                .disabled(busy || targets.isEmpty)
                .accessibilityIdentifier("task.handover.submit")
            }
        }
        .alert(l10n("task_extras.handover.confirm_running", ["title": facts.title]), isPresented: $confirming) {
            Button(l10n("common.cancel"), role: .cancel) {}
            Button(l10n("task_extras.handover.submit")) { Task { await handOver(target ?? targets.first) } }
        } message: {
            Text(l10n("task_extras.handover.confirm_running_body"))
        }
    }

    private func handOver(_ destination: String?) async {
        guard let destination else { return }
        let agents = app.agentDirectory.agents(facts.profile)
        guard let hermes = TaskExtrasRules.hermesID(facts.assignee, agents: agents) else {
            error = l10n("task_extras.handover.no_hermes")
            return
        }
        busy = true
        defer { busy = false }
        let profile = facts.profile, id = facts.id
        let request = TaskAssign(agentId: hermes, instructions: nil, start: false, profile: destination)
        do {
            _ = try await app.api.call { try await TasksAPI.tasksAssignTask(xHubProfile: profile, taskId: id, taskAssign: request, apiConfiguration: $0) }
            done()
            dismiss()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}
