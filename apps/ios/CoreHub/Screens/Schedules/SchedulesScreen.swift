// Schedules show every profile the person may enter, with no profile filter (profileScope.alwaysAll,
// ADR 0016): each item carries its profile's badge, and anything done to it goes to its own profile
// without moving the selector. Workflows are Workflows.swift.
import CoreHubClient
import SwiftUI

enum SchedulesHalf: Hashable { case jobs, workflows }

struct SchedulesScreen: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var half: SchedulesHalf = .jobs

    var body: some View {
        // Schedules and workflows share the page, as on the web: one segmented switch above them.
        VStack(spacing: 0) {
            Picker(l10n("nav.schedules"), selection: $half) {
                Text(l10n("schedules.tab_jobs")).tag(SchedulesHalf.jobs)
                Text(l10n("schedules.tab_workflows")).tag(SchedulesHalf.workflows)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, Space.s4)
            .padding(.vertical, Space.s2)
            .accessibilityIdentifier("schedules.half")
            switch half {
            case .jobs: jobs
            case .workflows: WorkflowsList()
            }
        }
        .navigationTitle(l10n("nav.schedules"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("screen.schedules")
    }

    private var jobs: some View {
        AsyncContent(key: "schedules") {
            try await app.api.call { try await SchedulesAPI.schedulesList(profiles: .all, limit: 200, apiConfiguration: $0) }.items
        } content: { schedules, reload in
            List {
                if schedules.isEmpty { Text(l10n("schedules.empty")).foregroundStyle(Tone.textMuted) }
                ForEach(schedules, id: \.id) { schedule in
                    ScheduleRow(schedule: schedule, showProfile: app.enterableProfiles.count > 1, changed: reload)
                }
            }
            .refreshable { reload() }
        }
    }
}

struct ScheduleRow: View {
    let schedule: Schedule
    let showProfile: Bool
    let changed: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var note: String?
    @State private var busy = false

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            HStack(alignment: .firstTextBaseline) {
                Text(schedule.name).font(.system(size: FontSize.sizeMd, weight: .medium))
                    .contentDirection(of: schedule.name)
                if showProfile { ProfileBadge(name: app.profileName(schedule.profile)) }
            }
            HStack(spacing: Space.s2) {
                StatusPill(text: l10n("schedules.state_\(schedule.state.rawValue)"), kind: schedule.state == .running ? .good : schedule.state == .paused ? .warn : .neutral)
                if let display = schedule.trigger.display {
                    Text(display).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
            }
            if let next = schedule.nextRunAt {
                Text(l10n("schedules.next", ["time": next.shortText(app.language)]))
                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
            if let lastError = schedule.lastError {
                Text(lastError).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger).lineLimit(2)
            }
            if let note { NoticeView(text: note, tone: .info) }
            Button {
                Task { await runNow() }
            } label: {
                LucideLabel(l10n("schedules.run_now"), icon: .play, size: 16)
            }
            .font(.system(size: FontSize.sizeSm))
            .disabled(busy)
        }
    }

    private func runNow() async {
        busy = true
        defer { busy = false }
        let profile = schedule.profile
        do {
            _ = try await app.api.call {
                try await SchedulesAPI.schedulesRunNow(xHubProfile: profile, scheduleId: schedule.id, apiConfiguration: $0)
            }
            note = l10n("schedules.started")
            changed()
        } catch {
            note = HubFailure(error).describe(l10n)
        }
    }
}
