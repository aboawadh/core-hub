// An agent's Jobs: its schedules.
import CoreHubClient
import SwiftUI

/// The agent's jobs: the Schedules list narrowed to this agent and profile (§٤ rule 8), as on the
/// web — run one now, pause or resume it, delete it (asks first; a Hermes job goes from Hermes's
/// scheduler too). Making or editing one is the Schedules page's, which the page links to.
struct AgentJobsPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var pendingDelete: Schedule?
    @State private var note: String?
    @State private var failure: String?
    @State private var busy: String?

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call {
                try await SchedulesAPI.schedulesList(profile: profile, agentId: agent.id, apiConfiguration: $0)
            }.items
        } content: { schedules, reload in
            List {
                Section {
                    Text(l10n("schedules.jobs.note")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                    Button {
                        app.pendingRoute = .destination(.schedules)
                    } label: {
                        LucideLabel(l10n("nav.schedules"), icon: .calendarClock, size: 16)
                    }
                    .accessibilityIdentifier("agent.jobs.schedules")
                    if let note { NoticeView(text: note, tone: .success) }
                    if let failure { NoticeView(text: failure, tone: .danger) }
                }
                if schedules.isEmpty {
                    EmptyStateView(icon: .rotateCcwClock, title: l10n("schedules.jobs.none"), message: l10n("schedules.jobs.none_body"))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
                ForEach(schedules, id: \.id) { job in
                    VStack(alignment: .leading, spacing: Space.s2) {
                        ScheduleRow(schedule: job, showProfile: false)
                        if let prompt = job.target.prompt, !prompt.isEmpty {
                            Text(prompt).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2)
                                .contentDirection(of: prompt)
                        }
                        HStack(spacing: Space.s3) {
                            Button {
                                Task { await runNow(job, reload) }
                            } label: {
                                LucideLabel(l10n("schedules.run_now"), icon: .play, size: 14)
                            }
                            .accessibilityIdentifier("job.\(job.id).run")
                            Button {
                                Task { await setEnabled(job, !job.enabled, reload) }
                            } label: {
                                LucideLabel(l10n(job.enabled ? "schedules.pause" : "schedules.resume"), icon: job.enabled ? .pause : .play, size: 14)
                            }
                            .accessibilityIdentifier("job.\(job.id).pause")
                            Spacer()
                            Button(role: .destructive) {
                                pendingDelete = job
                            } label: {
                                LucideIcon(.trash, size: 16)
                            }
                            .accessibilityLabel(l10n("kit.delete"))
                            .accessibilityIdentifier("job.\(job.id).delete")
                        }
                        .buttonStyle(.borderless)
                        .font(.system(size: FontSize.sizeSm))
                        .disabled(busy == job.id)
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) { pendingDelete = job } label: { LucideLabel(l10n("kit.delete"), icon: .trash) }
                    }
                }
            }
            .refreshable { reload() }
            .alert(
                pendingDelete.map { l10n("kit.delete_confirm", ["name": $0.name]) } ?? "",
                isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
            ) {
                Button(l10n("common.cancel"), role: .cancel) { pendingDelete = nil }
                Button(l10n("kit.delete"), role: .destructive) {
                    guard let job = pendingDelete else { return }
                    pendingDelete = nil
                    Task { await delete(job, reload) }
                }
                .accessibilityIdentifier("dialog.confirm")
            } message: {
                // A job in Hermes's scheduler goes from there too.
                Text(pendingDelete?.external?.source == .hermes ? l10n("schedules.jobs.delete_body") : l10n("kit.delete_body"))
            }
        }
    }

    private func runNow(_ job: Schedule, _ reload: () -> Void) async {
        busy = job.id
        defer { busy = nil }
        do {
            _ = try await app.api.call {
                try await SchedulesAPI.schedulesRunNow(xHubProfile: job.profile, scheduleId: job.id, apiConfiguration: $0)
            }
            failure = nil
            note = ScheduleRules.fired(job, l10n)
            reload()
        } catch {
            note = nil
            failure = ScheduleRules.refusal(HubFailure(error), l10n)
        }
    }

    private func setEnabled(_ job: Schedule, _ on: Bool, _ reload: () -> Void) async {
        busy = job.id
        defer { busy = nil }
        do {
            _ = try await app.api.call {
                try await SchedulesAPI.schedulesUpdate(xHubProfile: job.profile, scheduleId: job.id, scheduleWrite: ScheduleWrite(enabled: on), apiConfiguration: $0)
            }
            failure = nil
            reload()
        } catch {
            failure = ScheduleRules.refusal(HubFailure(error), l10n)
        }
    }

    private func delete(_ job: Schedule, _ reload: () -> Void) async {
        busy = job.id
        defer { busy = nil }
        do {
            try await app.api.call {
                try await SchedulesAPI.schedulesDelete(xHubProfile: job.profile, scheduleId: job.id, apiConfiguration: $0)
            }
            failure = nil
            note = nil
            reload()
        } catch {
            failure = ScheduleRules.refusal(HubFailure(error), l10n)
        }
    }
}

extension PhonePage {
    static let agentJobs = PhonePage(.agentJobs) { context in
        if let agent = context.agent { AgentJobsPage(agent: agent) }
    }
}
